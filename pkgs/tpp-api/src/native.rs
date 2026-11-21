//! The native client of the API socket protocol: a port of `termux-api.c`.
//!
//! The client binds two abstract unix sockets with random names. The app plugin
//! connects to the first (its output, our input) and to the second (its input,
//! our output). To make the plugin connect, the client contacts it: through the
//! plugin's abstract listen socket on Android before 14, otherwise (and when
//! that fails) with `am broadcast` to the plugin's receiver.
//!
//! Compared to the C client the contact runs on a thread instead of a forked
//! process, a failing `am` is an error instead of a wait for ever, and the
//! descriptor passed by the app is returned instead of exec'ing
//! `termux-callback` with it. The copy of the standard input stops with the
//! answer that carries the descriptor, since the callback reads the input from
//! then on (see [`crate::stdin_split`]).
//!
//! While a [`sys::InterruptGuard`] is active the waits (for the app to connect,
//! for its output) wake up regularly to look for a SIGINT or SIGTERM; a signal
//! ends the call early (an error while waiting for the app to connect, the
//! output so far afterwards).

use crate::call::Call;
use crate::sys;
use crate::transport::{Reply, Transport};
use crate::Env;
use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::io::{self, Read, Write};
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(not(target_os = "android"))]
use std::os::linux::net::SocketAddrExt;
use std::os::unix::io::AsRawFd;
use std::os::unix::net::{SocketAddr, UnixListener, UnixStream};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub const PLUGIN_PACKAGE: &str = "dev.pschmitt.termux";
pub const RECEIVER: &str = "dev.pschmitt.termux/dev.pschmitt.termux.api.TermuxApiReceiver";
pub const LISTEN_SOCKET: &str = "dev.pschmitt.termux.api://listen";
pub const DEFAULT_PREFIX: &str = "/data/data/dev.pschmitt.termux/files/usr";
/// What `termux-api --version` prints.
pub const API_VERSION: &str = "0.60.0";

/// The `am` executable: `TPP_API_AM` in the environment, else the path baked in
/// at build time (`TPP_API_AM` when compiling), else `$PREFIX/bin/am`.
pub fn am_path(env: &Env) -> String {
    if let Some(p) = env.get("TPP_API_AM").filter(|p| !p.is_empty()) {
        return p.to_string();
    }
    if let Some(p) = option_env!("TPP_API_AM") {
        return p.to_string();
    }
    let prefix = env
        .get("PREFIX")
        .filter(|p| !p.is_empty())
        .unwrap_or(DEFAULT_PREFIX);
    format!("{prefix}/bin/am")
}

/// Field 22 (`starttime`) of `/proc/PID/stat`, or None.
pub fn process_starttime(pid: u32) -> Option<u64> {
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    // The command name (field 2) may hold spaces and parentheses: go by the
    // last ')'. Field 3 (the state) follows it; starttime is 20 fields later.
    let after = &stat[stat.rfind(')')? + 1..];
    after.split_whitespace().nth(19)?.parse().ok()
}

fn random_u32() -> u32 {
    RandomState::new().build_hasher().finish() as u32
}

/// The name of a socket, shaped like the UUIDs of the C client.
pub fn generate_uuid() -> String {
    format!(
        "{:x}{:x}-{:x}-{:x}-{:x}-{:x}{:x}{:x}",
        random_u32(),
        random_u32(),
        std::process::id(),
        (random_u32() & 0x0fff) | 0x4000,
        random_u32() % 0x3fff + 0x8000,
        random_u32(),
        random_u32(),
        random_u32()
    )
}

/// The argument string the plugin's listen socket takes (see `termux-api.c`):
/// string extras are quoted and their quotes escaped.
pub fn listen_arguments(
    call: &Call,
    input_name: &str,
    output_name: &str,
    pid: i64,
    uid: i64,
    starttime: i64,
) -> Vec<u8> {
    let mut b = Vec::new();
    // The plugin's input is our output and the other way round.
    b.extend_from_slice(format!("--es socket_input \"{output_name}\" ").as_bytes());
    b.extend_from_slice(format!("--es socket_output \"{input_name}\" ").as_bytes());
    b.extend_from_slice(format!("--ei api_server_pid {pid} ").as_bytes());
    b.extend_from_slice(format!("--ei api_server_uid {uid} ").as_bytes());
    b.extend_from_slice(format!("--ei api_server_starttime {starttime} ").as_bytes());
    b.extend_from_slice(format!("--es api_method \"{}\" ", call.method).as_bytes());
    let args = &call.args;
    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if a == "--es" || a == "-e" || a == "--esa" {
            b.extend_from_slice(a.as_bytes());
            b.push(b' ');
            i += 1;
            if let Some(key) = args.get(i) {
                b.extend_from_slice(key.as_bytes());
                b.push(b' ');
            }
            i += 1;
            if let Some(value) = args.get(i) {
                b.push(b'"');
                for c in value.bytes() {
                    if c == b'"' {
                        b.push(b'\\');
                    }
                    b.push(c);
                }
                b.extend_from_slice(b"\" ");
            }
        } else {
            b.extend_from_slice(a.as_bytes());
            b.push(b' ');
        }
        i += 1;
    }
    b
}

/// The `am` command line (without the executable) of the broadcast fallback.
pub fn am_arguments(
    call: &Call,
    input_name: &str,
    output_name: &str,
    pid: i64,
    uid: i64,
    starttime: i64,
) -> Vec<String> {
    let mut v: Vec<String> = [
        "broadcast",
        "--user",
        "0",
        "-n",
        RECEIVER,
        "--es",
        "socket_input",
        output_name,
        "--es",
        "socket_output",
        input_name,
        "--ei",
        "api_server_pid",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    v.push(pid.to_string());
    v.extend([
        "--ei".to_string(),
        "api_server_uid".to_string(),
        uid.to_string(),
    ]);
    v.extend([
        "--ei".to_string(),
        "api_server_starttime".to_string(),
        starttime.to_string(),
    ]);
    v.extend([
        "--es".to_string(),
        "api_method".to_string(),
        call.method.clone(),
    ]);
    v.extend(call.args.iter().cloned());
    v
}

/// Tell the plugin through its listen socket; Ok(true) if it took the call.
#[cfg(target_os = "android")]
fn contact_listen_socket(args: &[u8]) -> io::Result<bool> {
    let addr = SocketAddr::from_abstract_name(LISTEN_SOCKET)?;
    let mut s = UnixStream::connect_addr(&addr)?;
    if sys::peer_uid(s.as_raw_fd())? != sys::uid() {
        return Ok(false);
    }
    let len = u16::try_from(args.len()).map_err(|_| io::ErrorKind::InvalidInput)?;
    s.write_all(&len.to_be_bytes())?;
    s.write_all(args)?;
    let mut first = true;
    let mut ok = false;
    let mut buf = [0u8; 99];
    loop {
        let n = s.read(&mut buf)?;
        if n == 0 {
            break;
        }
        // A single NUL byte as the first message means success; anything else
        // is an error message.
        if n == 1 && buf[0] == 0 && first {
            ok = true;
            break;
        }
        let _ = io::stderr().write_all(&buf[..n]);
        first = false;
    }
    Ok(ok)
}

#[cfg(not(target_os = "android"))]
fn contact_listen_socket(_args: &[u8]) -> io::Result<bool> {
    Ok(false)
}

fn contact_plugin(
    call: &Call,
    env: &Env,
    input_name: &str,
    output_name: &str,
    pid: i64,
    uid: i64,
    starttime: i64,
) -> io::Result<()> {
    // On Android 14 and later a frozen app process still accepts connections on
    // the listen socket but never answers, so only older versions use it.
    if sys::android_api_level() != 0 && sys::android_api_level() < 34 {
        let args = listen_arguments(call, input_name, output_name, pid, uid, starttime);
        if let Ok(true) = contact_listen_socket(&args) {
            return Ok(());
        }
    }
    let am = am_path(env);
    let status = Command::new(&am)
        .args(am_arguments(
            call,
            input_name,
            output_name,
            pid,
            uid,
            starttime,
        ))
        .env_clear()
        .envs(env.iter())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .status()
        .map_err(|e| io::Error::new(e.kind(), format!("{am}: {e}")))?;
    if status.success() {
        Ok(())
    } else {
        Err(io::Error::other(format!("{am} {status}")))
    }
}

/// How often a wait looks for an interrupt while an `InterruptGuard` is active.
const WATCH_INTERVAL: std::time::Duration = std::time::Duration::from_millis(50);

/// `accept`, ending with `Interrupted` as soon as a signal is recorded.
fn accept_watching(listener: &UnixListener) -> io::Result<UnixStream> {
    listener.set_nonblocking(true)?;
    loop {
        match listener.accept() {
            Ok((conn, _)) => {
                conn.set_nonblocking(false)?;
                return Ok(conn);
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                if sys::interrupted().is_some() {
                    return Err(io::Error::new(
                        io::ErrorKind::Interrupted,
                        "interrupted while waiting for the app",
                    ));
                }
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
            Err(e) => return Err(e),
        }
    }
}

/// The native transport.
#[derive(Debug, Default, Clone, Copy)]
pub struct Native;

impl Transport for Native {
    fn call(
        &self,
        call: &Call,
        mut stdin: Box<dyn Read + Send>,
        out: &mut dyn Write,
        err: &mut dyn Write,
        env: &Env,
    ) -> io::Result<Reply> {
        let input_name = generate_uuid(); // read by us
        let output_name = generate_uuid(); // written by us
        let input_addr = SocketAddr::from_abstract_name(&input_name)?;
        let output_addr = SocketAddr::from_abstract_name(&output_name)?;
        let input_l = UnixListener::bind_addr(&input_addr)
            .map_err(|e| io::Error::new(e.kind(), format!("bind(input): {e}")))?;
        let output_l = UnixListener::bind_addr(&output_addr)
            .map_err(|e| io::Error::new(e.kind(), format!("bind(output): {e}")))?;

        // pid and starttime identify this process even if the pid is recycled.
        let pid = std::process::id();
        let uid = sys::uid();
        let starttime = process_starttime(pid).map(|t| t as i64).unwrap_or(-1);

        let failed = Arc::new(AtomicBool::new(false));
        let failure = Arc::new(std::sync::Mutex::new(None::<io::Error>));
        {
            let call = call.clone();
            let env = env.clone();
            let (input_name, output_name) = (input_name.clone(), output_name.clone());
            let (failed, failure, wake) = (failed.clone(), failure.clone(), input_addr.clone());
            std::thread::spawn(move || {
                if let Err(e) = contact_plugin(
                    &call,
                    &env,
                    &input_name,
                    &output_name,
                    pid as i64,
                    uid as i64,
                    starttime,
                ) {
                    *failure.lock().unwrap() = Some(e);
                    failed.store(true, Ordering::SeqCst);
                    // Wake the accept below, which would wait for ever.
                    let _ = UnixStream::connect_addr(&wake);
                }
            });
        }

        let watch = sys::watching_interrupts();
        let conn = if watch {
            match accept_watching(&input_l) {
                Ok(c) => c,
                Err(e) => {
                    // let the stdin thread (if any) and the contact end
                    let _ = UnixStream::connect_addr(&output_addr);
                    return Err(e);
                }
            }
        } else {
            input_l.accept()?.0
        };
        if failed.load(Ordering::SeqCst) {
            let e = failure.lock().unwrap().take();
            return Err(e.unwrap_or_else(|| io::Error::other("contacting the app failed")));
        }

        // The standard input goes to the app as soon as it connects to the
        // output socket. The thread ends with the input; if the app never
        // connects, it is woken below.
        let stop = Arc::new(AtomicBool::new(false));
        {
            let stop = stop.clone();
            std::thread::spawn(move || {
                if let Ok((mut c, _)) = output_l.accept() {
                    let mut buf = [0u8; 1024];
                    // The answer that carries a descriptor ends the copy: what
                    // is read from then on is for the callback.
                    while !stop.load(Ordering::SeqCst) {
                        match stdin.read(&mut buf) {
                            Ok(0) | Err(_) => break,
                            Ok(n) => {
                                if stop.load(Ordering::SeqCst) || c.write_all(&buf[..n]).is_err() {
                                    break;
                                }
                            }
                        }
                    }
                }
            });
        }

        let mut reply = Reply::default();
        let mut buf = [0u8; 1024];
        let fd = conn.as_raw_fd();
        if watch {
            let _ = conn.set_read_timeout(Some(WATCH_INTERVAL));
        }
        loop {
            match sys::recv_with_fd(fd, &mut buf) {
                Ok((0, _)) => break,
                Ok((n, got)) => {
                    if let Some(f) = got {
                        reply.close_fd();
                        reply.fd = Some(f);
                        stop.store(true, Ordering::SeqCst);
                    }
                    // A descriptor must come with a non-empty message, so the
                    // app sends "@" when it does not want any output.
                    if reply.fd.is_some() && n == 1 && buf[0] == b'@' {
                        continue;
                    }
                    let _ = out.write_all(&buf[..n]);
                    let _ = out.flush();
                }
                Err(e)
                    if watch
                        && matches!(
                            e.kind(),
                            io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                        ) =>
                {
                    if sys::interrupted().is_some() {
                        break;
                    }
                }
                Err(e) => {
                    let _ = writeln!(err, "recvmsg(): {e}");
                    break;
                }
            }
        }
        stop.store(true, Ordering::SeqCst);
        // Wake the stdin thread if the app never connected to the output socket.
        let _ = UnixStream::connect_addr(&output_addr);
        Ok(reply)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn listen_arguments_quote_string_extras() {
        let call = Call::new("Toast")
            .es("text_color", "red")
            .ez("short", "true")
            .es("text", "say \"hi\"");
        let b = listen_arguments(&call, "in", "out", 7, 10, 99);
        assert_eq!(
            String::from_utf8(b).unwrap(),
            "--es socket_input \"out\" --es socket_output \"in\" --ei api_server_pid 7 \
             --ei api_server_uid 10 --ei api_server_starttime 99 --es api_method \"Toast\" \
             --es text_color \"red\" --ez short true --es text \"say \\\"hi\\\"\" "
        );
    }

    #[test]
    fn am_arguments_match_the_c_client() {
        let call = Call::new("Battery").ez("a", "true");
        let v = am_arguments(&call, "in", "out", 7, 10, 99);
        assert_eq!(
            v.join(" "),
            format!(
                "broadcast --user 0 -n {RECEIVER} --es socket_input out --es socket_output in \
                 --ei api_server_pid 7 --ei api_server_uid 10 --ei api_server_starttime 99 \
                 --es api_method Battery --ez a true"
            )
        );
    }

    #[test]
    fn starttime_is_field_22() {
        let me = std::process::id();
        assert!(process_starttime(me).is_some());
    }

    #[test]
    fn uuids_differ() {
        assert_ne!(generate_uuid(), generate_uuid());
    }
}
