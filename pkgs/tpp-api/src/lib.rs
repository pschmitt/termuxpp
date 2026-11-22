//! TermuxPP API command backends as a library.
//!
//! Every `tpp` command that talks to the TermuxPP app (battery, toast, sms,
//! saf, usb, ...) is a function here, dispatched by the name of its
//! implementation (the `imp` column of the `tpp` command table). The commands
//! parse their options like the shell scripts they replace (`getopts`), print
//! the same usage text and messages, and call the app through the native client
//! of the socket protocol in [`native`] (a port of `termux-api.c`).
//!
//! The entry points are [`run`] (with explicit I/O, for tests and for capturing
//! output) and [`run_stdio`] (with the standard streams of the process).

pub mod call;
pub mod cmd;
pub mod getopt_long;
pub mod getopts;
pub mod native;
pub mod stdin_split;
pub mod sys;
pub mod testing;
pub mod transport;

pub use call::{Call, Stdin};
pub use native::{Native, API_VERSION};
pub use transport::{Reply, Transport};

use std::collections::HashMap;
use std::ffi::OsString;
use std::fs::File;
use std::io::{self, Cursor, Read, Write};
use std::mem::ManuallyDrop;
use std::os::unix::io::FromRawFd;
use std::sync::OnceLock;

/// The environment commands see (so that tests need not touch the process
/// environment). Children are started with exactly this environment.
#[derive(Debug, Clone, Default)]
pub struct Env {
    vars: HashMap<String, String>,
}

impl Env {
    /// The environment of this process.
    pub fn process() -> Self {
        Env {
            vars: std::env::vars().collect(),
        }
    }
    pub fn empty() -> Self {
        Env::default()
    }
    pub fn get(&self, name: &str) -> Option<&str> {
        self.vars.get(name).map(String::as_str)
    }
    pub fn set(&mut self, name: &str, value: &str) {
        self.vars.insert(name.to_string(), value.to_string());
    }
    pub fn remove(&mut self, name: &str) {
        self.vars.remove(name);
    }
    pub fn iter(&self) -> impl Iterator<Item = (&String, &String)> {
        self.vars.iter()
    }
}

/// The streams and services one command run uses.
pub struct Io<'a> {
    /// Standard input; the first call that inherits it takes it.
    pub stdin: Option<Box<dyn Read + Send>>,
    pub stdout: &'a mut dyn Write,
    pub stderr: &'a mut dyn Write,
    pub env: Env,
    /// Report invalid public CLI usage with a failure status.
    pub strict_usage: bool,
    pub transport: &'a dyn Transport,
}

impl<'a> Io<'a> {
    /// Write text to standard output as it is (a failing write, such as a
    /// closed pipe, is ignored like `echo` in a script would).
    pub fn out(&mut self, s: &str) {
        let _ = self.stdout.write_all(s.as_bytes());
        let _ = self.stdout.flush();
    }
    /// `echo`: the text and a newline.
    pub fn outln(&mut self, s: &str) {
        self.out(s);
        self.out("\n");
    }
    pub fn err(&mut self, s: &str) {
        let _ = self.stderr.write_all(s.as_bytes());
        let _ = self.stderr.flush();
    }
    /// `echo ... >&2`
    pub fn errln(&mut self, s: &str) {
        self.err(s);
        self.err("\n");
    }

    /// Take the standard input of the command (empty if already taken).
    pub fn take_stdin(&mut self) -> Box<dyn Read + Send> {
        self.stdin.take().unwrap_or_else(|| Box::new(io::empty()))
    }

    /// Read all the standard input (`$(cat)`).
    pub fn read_stdin(&mut self) -> Vec<u8> {
        let mut v = Vec::new();
        let _ = self.take_stdin().read_to_end(&mut v);
        v
    }

    /// Read a public CLI text operand from a pipe or redirected file.
    /// A terminal receives guidance immediately instead of an implicit read.
    pub fn read_message(&mut self, command: &str, example: &str) -> Result<Vec<u8>, i32> {
        use std::io::IsTerminal;
        use std::sync::mpsc;
        use std::time::Duration;

        if io::stdin().is_terminal() {
            self.errln(&format!(
                "{command}: enter a message as an argument, or pipe text into the command."
            ));
            self.errln(&format!("Example: {example}"));
            return Err(2);
        }

        let _guard = sys::InterruptGuard::install();
        let mut stdin = self.take_stdin();
        let (sender, receiver) = mpsc::channel();
        std::thread::spawn(move || {
            let mut text = Vec::new();
            let result = stdin.read_to_end(&mut text).map(|_| text);
            let _ = sender.send(result);
        });
        loop {
            if let Some(signal) = sys::interrupted() {
                return Err(128 + signal);
            }
            match receiver.recv_timeout(Duration::from_millis(50)) {
                Ok(Ok(text)) => return Ok(text),
                Err(mpsc::RecvTimeoutError::Timeout) => continue,
                Ok(Err(error)) => {
                    self.errln(&format!("{command}: cannot read standard input: {error}"))
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => self.errln(&format!(
                    "{command}: standard input reader stopped unexpectedly"
                )),
            }
            return Err(1);
        }
    }

    /// `IFS= read -t SECS -r -d '' VAR`: standard input up to the first NUL,
    /// the end of input or the timeout, whichever comes first (what was read
    /// until then is kept, as `read` does).
    pub fn read_stdin_until_nul(&mut self, timeout: std::time::Duration) -> Vec<u8> {
        use std::sync::mpsc;
        let mut stdin = self.take_stdin();
        let (tx, rx) = mpsc::channel::<Vec<u8>>();
        std::thread::spawn(move || {
            let mut buf = [0u8; 4096];
            loop {
                match stdin.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if tx.send(buf[..n].to_vec()).is_err() {
                            break;
                        }
                    }
                }
            }
        });
        let deadline = std::time::Instant::now() + timeout;
        let mut data = Vec::new();
        loop {
            let left = deadline.saturating_duration_since(std::time::Instant::now());
            match rx.recv_timeout(left) {
                Ok(chunk) => {
                    data.extend_from_slice(&chunk);
                    if let Some(i) = data.iter().position(|b| *b == 0) {
                        data.truncate(i);
                        break;
                    }
                }
                Err(_) => break,
            }
        }
        data
    }

    fn call_stdin(&mut self, call: &Call) -> Box<dyn Read + Send> {
        match &call.stdin {
            Stdin::Inherit => self.take_stdin(),
            Stdin::None => Box::new(io::empty()),
            Stdin::Bytes(b) => Box::new(Cursor::new(b.clone())),
        }
    }

    /// Call the app and stream its output to standard output.
    pub fn call(&mut self, call: Call) -> io::Result<Reply> {
        let stdin = self.call_stdin(&call);
        self.transport
            .call(&call, stdin, self.stdout, self.stderr, &self.env)
    }

    /// Call the app and keep its output (`output=$(termux-api ...)`).
    pub fn call_capture(&mut self, call: Call) -> io::Result<(Vec<u8>, Reply)> {
        let stdin = self.call_stdin(&call);
        let mut out = Vec::new();
        let reply = self
            .transport
            .call(&call, stdin, &mut out, self.stderr, &self.env)?;
        Ok((out, reply))
    }

    /// Like [`Io::call_capture`], as text without trailing newlines, as the
    /// shell's command substitution gives it.
    pub fn call_text(&mut self, call: Call) -> io::Result<String> {
        let (out, mut reply) = self.call_capture(call)?;
        reply.close_fd();
        let mut s = String::from_utf8_lossy(&out).into_owned();
        while s.ends_with('\n') {
            s.pop();
        }
        Ok(s)
    }

    /// Run an external program, streaming its output; its exit status.
    pub fn run_program(&mut self, program: &str, args: &[String]) -> i32 {
        match self
            .transport
            .run_program(program, args, &self.env, self.stdout, self.stderr)
        {
            Ok(status) => status,
            Err(e) => {
                self.errln(&format!("tpp: {program}: {e}"));
                127
            }
        }
    }

    /// The common case: call the app, stream the output, and give the exit
    /// status of the command (0, or 1 after printing the error).
    pub fn run_call(&mut self, call: Call) -> i32 {
        match self.call(call) {
            Ok(mut reply) => {
                reply.close_fd();
                0
            }
            Err(e) => {
                self.errln(&format!("tpp: {e}"));
                1
            }
        }
    }

    /// `realpath PATH`, with the guest-to-Android conversion where one is set
    /// (see [`set_path_converter`]).
    pub fn realpath(&self, path: &str) -> io::Result<String> {
        if let Some(conv) = PATH_CONVERTER.get() {
            return conv(path);
        }
        if let Some(cmd) = self.env.get("TPP_API_PATH_CMD").filter(|c| !c.is_empty()) {
            let out = std::process::Command::new(cmd)
                .arg(path)
                .env_clear()
                .envs(self.env.iter())
                .stdin(std::process::Stdio::null())
                .stderr(std::process::Stdio::inherit())
                .output()?;
            if !out.status.success() {
                return Err(io::Error::other(format!("{cmd} {path}: {}", out.status)));
            }
            return Ok(String::from_utf8_lossy(&out.stdout)
                .trim_end_matches('\n')
                .to_string());
        }
        realpath_default(path)
    }
}

/// `realpath` as GNU coreutils does it by default: every component but the
/// last must exist.
pub fn realpath_default(path: &str) -> io::Result<String> {
    use std::path::Path;
    let p = Path::new(path);
    let resolved = match std::fs::canonicalize(p) {
        Ok(r) => r,
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            let name = p
                .file_name()
                .ok_or_else(|| io::Error::new(e.kind(), format!("{path}: {e}")))?;
            let dir = match p.parent() {
                Some(d) if !d.as_os_str().is_empty() => d,
                _ => Path::new("."),
            };
            std::fs::canonicalize(dir)
                .map_err(|e| io::Error::new(e.kind(), format!("{path}: {e}")))?
                .join(name)
        }
        Err(e) => return Err(io::Error::new(e.kind(), format!("{path}: {e}"))),
    };
    Ok(resolved.to_string_lossy().into_owned())
}

static PATH_CONVERTER: OnceLock<fn(&str) -> io::Result<String>> = OnceLock::new();

/// Install the conversion `realpath` applies to paths handed to the app
/// (the nixpp guest turns guest paths into Android paths). Only the first call
/// takes effect. Without one, `TPP_API_PATH_CMD` (a program that takes the path
/// and prints the converted one) and then plain `realpath` semantics are used.
pub fn set_path_converter(f: fn(&str) -> io::Result<String>) {
    let _ = PATH_CONVERTER.set(f);
}

/// Standard output as a plain unbuffered file descriptor writer, so that
/// binary data and streams are passed on as they arrive.
struct RawFd(ManuallyDrop<File>);

impl RawFd {
    fn new(fd: i32) -> Self {
        // SAFETY: fd 1 and 2 are open for the life of the process; ManuallyDrop
        // keeps them from being closed.
        RawFd(ManuallyDrop::new(unsafe { File::from_raw_fd(fd) }))
    }
}

impl Write for RawFd {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0.write(buf)
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Run the command `imp` with `args` (what the script would receive as `"$@"`)
/// on the given streams. None if `imp` is not a known implementation; else the
/// exit status.
pub fn run(imp: &str, args: &[OsString], io: &mut Io) -> Option<i32> {
    let handler = cmd::lookup(imp)?;
    let args: Vec<String> = args
        .iter()
        .map(|a| a.to_string_lossy().into_owned())
        .collect();
    Some(handler(&args, io))
}

/// Run the command `imp` on the standard streams of this process and the
/// native transport.
pub fn run_stdio(imp: &str, args: &[OsString]) -> Option<i32> {
    cmd::lookup(imp)?;
    let mut out = RawFd::new(1);
    let mut err = RawFd::new(2);
    let mut io = Io {
        stdin: Some(Box::new(io::stdin())),
        stdout: &mut out,
        stderr: &mut err,
        env: Env::process(),
        strict_usage: false,
        transport: &Native,
    };
    run(imp, args, &mut io)
}

/// Every implementation this crate provides, that is every one that is fully
/// ported. [`run`] and [`run_stdio`] return None for any other name.
pub const IMPLS: &[&str] = cmd::IMPLS;

/// Whether `imp` is ported (is in [`IMPLS`]).
pub fn has(imp: &str) -> bool {
    cmd::IMPLS.contains(&imp)
}

/// The usage text of the ported `imp` (what `-h` prints).
pub fn usage(imp: &str) -> Option<&'static str> {
    if has(imp) {
        cmd::usage(imp)
    } else {
        None
    }
}
