//! `usb`: `tpp usb [-l | [-r] [-E] [-e command] [device | vendorId productId]]`.
//!
//! The script is a shell wrapper around the `termux-api` client: with `-e` the
//! client runs the command with a descriptor of the device; the script lets
//! that command keep the real standard output and reads the client's own
//! messages. Here the app's answer carries the descriptor ([`crate::Reply`]),
//! and the command is started by this function.
//!
//! Deviations:
//! - The values the script word-splits (`$PARAMS`, `$CMD`) are passed intact:
//!   a device path with spaces stays one argument and an empty one stays an
//!   (empty) argument. Globbing is not emulated.
//! - The command given with `-e` is split on blanks and newlines into words
//!   (no quoting, no globbing, no shell syntax, as `$command` in the script).
//!   It runs with the environment of this command (plus `TERMUX_USB_FD` for
//!   `-E`); the script's callback also saw `TERMUX_CALLBACK` and an exported
//!   shell function.
//! - The standard input is shared as with the client: the call forwards it to
//!   the app while it lasts, and the command gets what is read after the app's
//!   answer (`crate::stdin_split`, also what the call had not read yet). The
//!   command reads it through a pipe, not the terminal itself.
//! - A command that cannot be started gives status 127 (not found) or 126 with
//!   `tpp usb: COMMAND: ...` on standard error (bash names the script and line).
//! - NUL bytes in the client's messages are dropped silently (bash warns).
use crate::getopts::{Getopts, Opt};
use crate::stdin_split::{Rest, SplitStdin};
use crate::{Call, Env, Io};
use std::fs::OpenOptions;
use std::io::{Read, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::process::ExitStatusExt;
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::thread;

pub const USAGE: &str = include_str!("../help/usb.txt");
const NAME: &str = "tpp usb";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut action = "permission";
    let mut params: Vec<String> = Vec::new();
    let mut command = String::new();

    let mut g = Getopts::new(args, ":hlre:E");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Flag('l') => action = "list",
            Opt::Flag('r') => params.extend(["--ez", "request", "true"].map(String::from)),
            Opt::Arg('e', v) => {
                action = "open";
                command = v;
            }
            Opt::Flag('E') => io.env.set("TERMUX_EXPORT_FD", "true"),
            Opt::Illegal(c) | Opt::MissingArg(c) => return super::illegal_option(io, NAME, c),
            Opt::Flag(_) | Opt::Arg(..) => {}
        }
    }

    let rest = g.rest();
    if action == "list" {
        if !rest.is_empty() {
            io.outln(&format!("{NAME}: too many arguments"));
            return 1;
        }
    } else {
        match rest.len() {
            0 => {
                io.outln(&format!("{NAME}: missing -l or device path"));
                return 1;
            }
            // the device's usbfs path
            1 => params.extend(["--es".to_string(), "device".to_string(), rest[0].clone()]),
            // a vendorId and a productId
            2 => params.extend([
                "--es".to_string(),
                "vendorId".to_string(),
                rest[0].clone(),
                "--es".to_string(),
                "productId".to_string(),
                rest[1].clone(),
            ]),
            _ => {
                io.outln(&format!("{NAME}: too many arguments"));
                return 1;
            }
        }
    }

    let call = Call::new("Usb").arg("-a").arg(action).args(params);
    match action {
        "list" => io.run_call(call),
        "permission" => permission(io, call),
        _ => open(io, call, &command),
    }
}

/// What `OUTPUT="$(...)"` holds: no NUL bytes, no trailing newlines.
fn substitution(out: &[u8]) -> Vec<u8> {
    let mut v: Vec<u8> = out.iter().copied().filter(|b| *b != 0).collect();
    while v.last() == Some(&b'\n') {
        v.pop();
    }
    v
}

/// `printf "%s\n" "$OUTPUT"`
fn print_output(io: &mut Io, output: &[u8]) {
    let _ = io.stdout.write_all(output);
    let _ = io.stdout.write_all(b"\n");
    let _ = io.stdout.flush();
}

fn permission(io: &mut Io, call: Call) -> i32 {
    let (out, mut reply) = match io.call_capture(call) {
        Ok(r) => r,
        Err(e) => {
            io.errln(&format!("tpp: {e}"));
            return 1;
        }
    };
    reply.close_fd();
    let output = substitution(&out);
    match output.as_slice() {
        b"No such device" | b"No such device." => {
            io.outln("No such device.");
            1
        }
        b"yes" => {
            io.outln("Permission granted.");
            0
        }
        b"no" | b"Permission denied." => {
            io.outln("Permission denied.");
            1
        }
        b"Permission request timeout." => {
            io.outln("Permission request timeout.");
            1
        }
        _ => {
            print_output(io, &output);
            0
        }
    }
}

fn open(io: &mut Io, call: Call, command: &str) -> i32 {
    let scratch_ok = scratch_file_works(&io.env);
    // the call and then the callback read the same input
    let split = SplitStdin::new(io.take_stdin());
    io.stdin = Some(Box::new(split.first()));
    let result = io.call_capture(call);
    split.hand_over();
    let (out, mut reply) = match result {
        Ok(r) => r,
        Err(e) => {
            io.errln(&format!("tpp: {e}"));
            return 1;
        }
    };
    // the script's callback marks its start in the scratch file
    let ran = reply.fd.is_some();
    if let Some(fd) = reply.fd {
        let status = run_callback(io, command, fd, split.rest());
        reply.close_fd();
        if status != 0 {
            // `set -e`: a failing callback ends the script with its status
            return status;
        }
    }
    let output = substitution(&out);
    match output.as_slice() {
        b"No such device" | b"No such device." => {
            io.outln("No such device.");
            1
        }
        b"No permission" | b"Permission denied." => {
            io.outln("Permission denied.");
            1
        }
        b"Permission request timeout." => {
            io.outln("Permission request timeout.");
            1
        }
        b"Failed to open device" | b"Open device failed." => {
            io.outln("Open device failed.");
            1
        }
        b"" => {
            // nothing printed: fine if the callback ran (or the script could
            // not tell, without a scratch file), else the app sent no device
            if !scratch_ok || ran {
                0
            } else {
                io.outln("Open device failed.");
                1
            }
        }
        _ => {
            print_output(io, &output);
            0
        }
    }
}

/// `mktemp "${TMPDIR:-$PREFIX/tmp}/termux-usb.XXXXXX"` followed by `rm -f`:
/// whether a scratch file can be made there.
fn scratch_file_works(env: &Env) -> bool {
    let dir = match env.get("TMPDIR").filter(|d| !d.is_empty()) {
        Some(d) => d.to_string(),
        None => {
            let prefix = env
                .get("PREFIX")
                .filter(|p| !p.is_empty())
                .unwrap_or(crate::native::DEFAULT_PREFIX);
            format!("{prefix}/tmp")
        }
    };
    let pid = u64::from(std::process::id());
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| u64::from(d.subsec_nanos()));
    const CHARS: &[u8] = b"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let mut seed = pid.wrapping_mul(6364136223846793005).wrapping_add(nanos);
    for _ in 0..100 {
        let mut suffix = String::new();
        for _ in 0..6 {
            seed = seed
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            suffix.push(CHARS[(seed >> 33) as usize % CHARS.len()] as char);
        }
        let path = format!("{dir}/termux-usb.{suffix}");
        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
        {
            Ok(_) => {
                let _ = std::fs::remove_file(&path);
                return true;
            }
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(_) => return false,
        }
    }
    false
}

/// Run the `-e` command with the descriptor of the device (as the last
/// argument, or as `TERMUX_USB_FD` with `-E`), streaming its output; its exit
/// status. Its standard input is `stdin`, the input that follows the call.
fn run_callback(io: &mut Io, command: &str, fd: std::os::raw::c_int, stdin: Rest) -> i32 {
    let export = io
        .env
        .get("TERMUX_EXPORT_FD")
        .is_some_and(|v| v.starts_with("true"));
    let mut argv: Vec<String> = command
        .split([' ', '\t', '\n'])
        .filter(|w| !w.is_empty())
        .map(String::from)
        .collect();
    if !export {
        argv.push(fd.to_string());
    }
    let Some(program) = argv.first().cloned() else {
        return 0;
    };

    let mut cmd = Command::new(&program);
    cmd.args(&argv[1..])
        .env_clear()
        .envs(io.env.iter())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if export {
        cmd.env("TERMUX_USB_FD", fd.to_string());
    }
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            return if e.kind() == std::io::ErrorKind::NotFound {
                io.errln(&format!("{NAME}: {program}: command not found"));
                127
            } else {
                io.errln(&format!("{NAME}: {program}: {e}"));
                126
            };
        }
    };

    // the input is passed on until the command ends it (by exiting)
    if let Some(mut pipe) = child.stdin.take() {
        let mut stdin = stdin;
        thread::spawn(move || {
            let mut buf = [0u8; 4096];
            loop {
                match stdin.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if pipe
                            .write_all(&buf[..n])
                            .and_then(|_| pipe.flush())
                            .is_err()
                        {
                            break;
                        }
                    }
                }
            }
        });
    }

    // standard output and error are forwarded as they arrive, in order
    let (tx, rx) = mpsc::channel::<(bool, Vec<u8>)>();
    let mut pumps = Vec::new();
    if let Some(stream) = child.stdout.take() {
        pumps.push(pump(stream, false, tx.clone()));
    }
    if let Some(stream) = child.stderr.take() {
        pumps.push(pump(stream, true, tx.clone()));
    }
    drop(tx);
    for (is_err, chunk) in rx {
        if is_err {
            let _ = io.stderr.write_all(&chunk);
            let _ = io.stderr.flush();
        } else {
            let _ = io.stdout.write_all(&chunk);
            let _ = io.stdout.flush();
        }
    }
    for p in pumps {
        let _ = p.join();
    }
    match child.wait() {
        Ok(status) => status
            .code()
            .unwrap_or_else(|| 128 + status.signal().unwrap_or(0)),
        Err(e) => {
            io.errln(&format!("{NAME}: {program}: {e}"));
            1
        }
    }
}

fn pump(
    mut stream: impl Read + Send + 'static,
    is_err: bool,
    tx: mpsc::Sender<(bool, Vec<u8>)>,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match stream.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if tx.send((is_err, buf[..n].to_vec())).is_err() {
                        break;
                    }
                }
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::{Answer, FakeTransport, Outcome};
    use crate::transport::{Reply, Transport};
    use std::fs;
    use std::io;
    use std::os::raw::c_int;
    use std::os::unix::io::AsRawFd;
    use std::path::PathBuf;
    use std::sync::Mutex;

    extern "C" {
        fn dup(fd: c_int) -> c_int;
    }

    /// A scratch directory with a device stand-in (`device`) and a callback
    /// script (`cb.sh`).
    struct Fixture {
        dir: PathBuf,
    }

    impl Fixture {
        fn new(tag: &str, callback: &str) -> Fixture {
            let dir = std::env::temp_dir().join(format!("tpp-usb-{tag}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&dir);
            fs::create_dir_all(dir.join("tmp")).unwrap();
            fs::write(dir.join("device"), "device data\n").unwrap();
            fs::write(dir.join("cb.sh"), callback).unwrap();
            Fixture { dir }
        }

        /// A descriptor of the device stand-in that children inherit, as the
        /// one `recvmsg` hands out.
        fn device_fd(&self) -> c_int {
            let file = fs::File::open(self.dir.join("device")).unwrap();
            // SAFETY: `dup` of a descriptor that is open (file lives until the end
            // of this function); the copy has no close-on-exec flag.
            let fd = unsafe { dup(file.as_raw_fd()) };
            assert!(fd >= 0, "dup failed");
            fd
        }

        fn cb(&self) -> String {
            format!("sh {}", self.dir.join("cb.sh").display())
        }

        fn run_with(
            &self,
            args: &[&str],
            t: &dyn Transport,
            stdin: Option<Box<dyn Read + Send>>,
        ) -> Outcome {
            let path = std::env::var("PATH").unwrap_or_default();
            let tmp = self.dir.join("tmp").display().to_string();
            let env = [("PATH", path.as_str()), ("TMPDIR", tmp.as_str())];
            run_usb_stdin(args, t, &env, stdin)
        }

        fn run(&self, args: &[&str], answer: Answer, extra_env: &[(&str, &str)]) -> Outcome {
            let t = FakeTransport::with_answers(vec![answer]);
            let path = std::env::var("PATH").unwrap_or_default();
            let tmp = self.dir.join("tmp").display().to_string();
            let mut env = vec![("PATH", path.as_str()), ("TMPDIR", tmp.as_str())];
            env.extend_from_slice(extra_env);
            run_usb(args, &t, &env)
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.dir);
        }
    }

    /// `run` on in-memory streams (without going through the command table).
    fn run_usb(args: &[&str], t: &FakeTransport, env: &[(&str, &str)]) -> Outcome {
        run_usb_stdin(args, t, env, None)
    }

    fn run_usb_stdin(
        args: &[&str],
        t: &dyn Transport,
        env: &[(&str, &str)],
        stdin: Option<Box<dyn Read + Send>>,
    ) -> Outcome {
        let args: Vec<String> = args.iter().map(|a| a.to_string()).collect();
        let (mut stdout, mut stderr) = (Vec::new(), Vec::new());
        let mut e = Env::empty();
        for (k, v) in env {
            e.set(k, v);
        }
        let status = {
            let mut io = Io {
                stdin,
                stdout: &mut stdout,
                stderr: &mut stderr,
                env: e,
                strict_usage: false,
                transport: t,
            };
            run(&args, &mut io)
        };
        Outcome {
            status,
            stdout,
            stderr,
        }
    }

    fn with_fd(fd: c_int) -> Answer {
        Answer {
            fd: Some(fd),
            ..Default::default()
        }
    }

    fn have_sh() -> bool {
        Command::new("sh")
            .args(["-c", "true"])
            .status()
            .map(|s| s.success())
            .unwrap_or(false)
    }

    #[test]
    fn the_descriptor_is_the_last_argument() {
        if !have_sh() {
            return;
        }
        let f = Fixture::new("arg", "eval \"cat <&$1\"; echo \"args=$#\"\n");
        let cb = f.cb();
        let o = f.run(
            &["-e", &cb, "/dev/bus/usb/001/002"],
            with_fd(f.device_fd()),
            &[],
        );
        assert_eq!(o.stdout_str(), "device data\nargs=1\n");
        assert_eq!(o.stderr_str(), "");
        assert_eq!(o.status, 0);
    }

    #[test]
    fn dash_e_passes_the_descriptor_in_the_environment() {
        if !have_sh() {
            return;
        }
        let f = Fixture::new(
            "env",
            "eval \"cat <&$TERMUX_USB_FD\"; echo \"args=$# fd=${1:-none}\"\n",
        );
        let cb = f.cb();
        let o = f.run(
            &["-E", "-e", &cb, "0x1", "0x2"],
            with_fd(f.device_fd()),
            &[],
        );
        // `sh cb.sh` has no arguments of its own: the descriptor is not one
        assert_eq!(o.stdout_str(), "device data\nargs=0 fd=none\n");
        assert_eq!(o.status, 0);
    }

    #[test]
    fn extra_words_of_the_command_come_before_the_descriptor() {
        if !have_sh() {
            return;
        }
        let f = Fixture::new("words", "echo \"$# $1 $2\"\n");
        let cb = format!("{}  extra", f.cb());
        let o = f.run(&["-e", &cb, "dev"], with_fd(f.device_fd()), &[]);
        let out = o.stdout_str();
        assert!(out.starts_with("2 extra "), "got {out:?}");
        assert_eq!(o.status, 0);
    }

    #[test]
    fn output_streams_stay_apart() {
        if !have_sh() {
            return;
        }
        let f = Fixture::new("streams", "echo out; echo err >&2\n");
        let cb = f.cb();
        let o = f.run(&["-e", &cb, "dev"], with_fd(f.device_fd()), &[]);
        assert_eq!(o.stdout_str(), "out\n");
        assert_eq!(o.stderr_str(), "err\n");
        assert_eq!(o.status, 0);
    }

    #[test]
    fn a_failing_callback_ends_the_command_with_its_status() {
        if !have_sh() {
            return;
        }
        let f = Fixture::new("fail", "echo before; exit 3\n");
        let cb = f.cb();
        let mut answer = with_fd(f.device_fd());
        answer.output = b"Permission denied.\n".to_vec();
        let o = f.run(&["-e", &cb, "dev"], answer, &[]);
        assert_eq!(o.stdout_str(), "before\n");
        assert_eq!(o.status, 3);
    }

    #[test]
    fn a_missing_command_is_127() {
        let f = Fixture::new("missing", "");
        let o = f.run(
            &["-e", "tpp-usb-no-such-command", "dev"],
            with_fd(f.device_fd()),
            &[],
        );
        assert_eq!(o.status, 127);
        assert_eq!(
            o.stderr_str(),
            "tpp usb: tpp-usb-no-such-command: command not found\n"
        );
    }

    #[test]
    fn the_app_messages_follow_a_successful_callback() {
        if !have_sh() {
            return;
        }
        let f = Fixture::new("msg", "true\n");
        let cb = f.cb();
        let mut answer = with_fd(f.device_fd());
        answer.output = b"some message\n".to_vec();
        let o = f.run(&["-e", &cb, "dev"], answer, &[]);
        assert_eq!(o.stdout_str(), "some message\n");
        assert_eq!(o.status, 0);
    }

    #[test]
    fn empty_answer_without_descriptor_depends_on_the_scratch_file() {
        let f = Fixture::new("scratch", "true\n");
        let cb = f.cb();
        // a scratch file can be made: the app sent no device
        let o = f.run(&["-e", &cb, "dev"], Answer::default(), &[]);
        assert_eq!(o.stdout_str(), "Open device failed.\n");
        assert_eq!(o.status, 1);
        // it cannot (no such directory): the script cannot tell, and succeeds
        let o = f.run(
            &["-e", &cb, "dev"],
            Answer::default(),
            &[("TMPDIR", "/nonexistent/tpp-usb-tmp")],
        );
        assert_eq!(o.stdout_str(), "");
        assert_eq!(o.status, 0);
    }

    #[test]
    fn the_scratch_file_is_removed_again() {
        let f = Fixture::new("cleanup", "true\n");
        let cb = f.cb();
        let _ = f.run(&["-e", &cb, "dev"], Answer::default(), &[]);
        let left: Vec<_> = fs::read_dir(f.dir.join("tmp")).unwrap().collect();
        assert!(left.is_empty(), "left over: {left:?}");
    }

    /// A reader that yields what the test sent and ends when the sender goes.
    struct Channel(std::sync::mpsc::Receiver<Vec<u8>>);

    impl Read for Channel {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            match self.0.recv() {
                Ok(chunk) => {
                    buf[..chunk.len()].copy_from_slice(&chunk);
                    Ok(chunk.len())
                }
                Err(_) => Ok(0),
            }
        }
    }

    /// Input for the test: each chunk is a read of its own.
    fn chunks(items: &[&[u8]]) -> Option<Box<dyn Read + Send>> {
        let (tx, rx) = std::sync::mpsc::channel();
        for item in items {
            tx.send(item.to_vec()).unwrap();
        }
        Some(Box::new(Channel(rx)))
    }

    /// An app that reads `take` bytes of the input and answers with a
    /// descriptor.
    struct Passive {
        fd: c_int,
        take: usize,
        seen: Mutex<Vec<u8>>,
    }

    impl Transport for Passive {
        fn call(
            &self,
            _call: &Call,
            mut stdin: Box<dyn Read + Send>,
            _out: &mut dyn Write,
            _err: &mut dyn Write,
            _env: &Env,
        ) -> io::Result<Reply> {
            let mut buf = vec![0u8; self.take];
            let mut n = 0;
            while n < self.take {
                let k = stdin.read(&mut buf[n..])?;
                if k == 0 {
                    break;
                }
                n += k;
            }
            self.seen.lock().unwrap().extend_from_slice(&buf[..n]);
            Ok(Reply { fd: Some(self.fd) })
        }
    }

    #[test]
    fn the_command_reads_the_input_the_app_did_not() {
        if !have_sh() {
            return;
        }
        let f = Fixture::new("stdin-rest", "cat\n");
        let cb = f.cb();
        let t = Passive {
            fd: f.device_fd(),
            take: 3,
            seen: Mutex::new(Vec::new()),
        };
        let o = f.run_with(&["-e", &cb, "dev"], &t, chunks(&[b"one", b"two"]));
        assert_eq!(t.seen.lock().unwrap().as_slice(), b"one");
        assert_eq!(o.stdout_str(), "two");
        assert_eq!(o.status, 0);
    }

    #[test]
    fn input_the_app_read_to_the_end_is_not_given_to_the_command() {
        if !have_sh() {
            return;
        }
        let f = Fixture::new("stdin-all", "cat; echo end\n");
        let cb = f.cb();
        let t = FakeTransport::with_answers(vec![with_fd(f.device_fd())]);
        let o = f.run_with(
            &["-e", &cb, "dev"],
            &t,
            Some(Box::new(io::Cursor::new(b"data".to_vec()))),
        );
        assert_eq!(t.calls()[0].stdin, b"data");
        assert_eq!(o.stdout_str(), "end\n");
        assert_eq!(o.status, 0);
    }

    #[test]
    fn a_command_that_ignores_its_input_does_not_hang_the_run() {
        if !have_sh() {
            return;
        }
        let f = Fixture::new("stdin-ignored", "echo done\n");
        let cb = f.cb();
        // the input never ends: the command is done after its own output
        let (tx, rx) = std::sync::mpsc::channel::<Vec<u8>>();
        tx.send(b"unread".to_vec()).unwrap();
        let t = Passive {
            fd: f.device_fd(),
            take: 0,
            seen: Mutex::new(Vec::new()),
        };
        let o = f.run_with(&["-e", &cb, "dev"], &t, Some(Box::new(Channel(rx))));
        assert_eq!(o.stdout_str(), "done\n");
        assert_eq!(o.status, 0);
        drop(tx);
    }
}
