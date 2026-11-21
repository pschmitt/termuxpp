//! A stand-in for the app side of the socket protocol, to run the native
//! transport against real (abstract) unix sockets without an app: a fake `am`
//! that records the arguments it was given, and helpers for the thread that then
//! plays the plugin by connecting to the two sockets those arguments name.

use std::fs;
use std::io::{self, Read};
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(not(target_os = "android"))]
use std::os::linux::net::SocketAddrExt;
use std::os::raw::{c_int, c_void};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::io::AsRawFd;
use std::os::unix::net::{SocketAddr, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};
use tpp_api::sys::{CmsgHdr, IoVec, MsgHdr, SCM_RIGHTS, SOL_SOCKET};
use tpp_api::{Call, Env, Native, Reply, Transport};

extern "C" {
    fn sendmsg(fd: c_int, msg: *const MsgHdr, flags: c_int) -> isize;
}

fn which(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|d| d.join(name))
        .find(|p| p.is_file())
}

/// A scratch directory with a fake `am`. The script writes its arguments, one
/// per line, to `argv` (atomically) and then runs `tail`.
pub struct FakeAm {
    pub dir: PathBuf,
    pub script: PathBuf,
    pub argv: PathBuf,
}

impl FakeAm {
    /// None (the test is skipped) when the system has no `sh`.
    pub fn new(tag: &str, tail: &str) -> Option<FakeAm> {
        let sh = which("sh")?;
        let dir = std::env::temp_dir().join(format!("tpp-api-net-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let script = dir.join("am");
        let argv = dir.join("argv");
        let body = format!(
            "#!{}\nprintf '%s\\n' \"$@\" > \"$TPP_FAKE_ARGV.tmp\"\nmv \"$TPP_FAKE_ARGV.tmp\" \"$TPP_FAKE_ARGV\"\n{tail}\n",
            sh.display()
        );
        fs::write(&script, body).unwrap();
        fs::set_permissions(&script, fs::Permissions::from_mode(0o755)).unwrap();
        Some(FakeAm { dir, script, argv })
    }

    /// The environment that makes the native transport use this `am`.
    pub fn env(&self) -> Env {
        let mut env = Env::empty();
        env.set("TPP_API_AM", &self.script.display().to_string());
        env.set("TPP_FAKE_ARGV", &self.argv.display().to_string());
        env.set("PATH", &std::env::var("PATH").unwrap_or_default());
        env
    }
}

impl Drop for FakeAm {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.dir);
    }
}

/// What the fake `am` was told.
#[derive(Debug, Clone)]
pub struct Contact {
    /// every argument of `am`
    pub argv: Vec<String>,
    /// the socket the plugin reads the standard input from (`socket_input`)
    pub stdin_socket: String,
    /// the socket the plugin writes its answer to (`socket_output`)
    pub reply_socket: String,
}

impl Contact {
    /// The value of the `--es NAME VALUE` extra.
    pub fn extra(&self, name: &str) -> Option<&str> {
        let i = self.argv.iter().position(|a| a == name)?;
        self.argv.get(i + 1).map(String::as_str)
    }
}

/// Wait for the fake `am` to be run and read what it was given.
pub fn wait_for_contact(argv: &Path) -> Contact {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if let Ok(text) = fs::read_to_string(argv) {
            let argv: Vec<String> = text.lines().map(String::from).collect();
            let mut c = Contact {
                argv,
                stdin_socket: String::new(),
                reply_socket: String::new(),
            };
            c.stdin_socket = c.extra("socket_input").expect("socket_input").to_string();
            c.reply_socket = c.extra("socket_output").expect("socket_output").to_string();
            return c;
        }
        assert!(Instant::now() < deadline, "the fake am was never run");
        thread::sleep(Duration::from_millis(5));
    }
}

/// Connect to an abstract socket by name.
pub fn connect(name: &str) -> io::Result<UnixStream> {
    UnixStream::connect_addr(&SocketAddr::from_abstract_name(name)?)
}

/// `send` of `data` with the descriptor `fd` attached (`SCM_RIGHTS`).
pub fn send_with_fd(stream: &UnixStream, data: &[u8], fd: c_int) {
    const fn align(n: usize) -> usize {
        let a = std::mem::size_of::<usize>();
        (n + a - 1) & !(a - 1)
    }
    let hdr = align(std::mem::size_of::<CmsgHdr>());
    let mut control = [0u64; 8];
    let len = hdr + std::mem::size_of::<c_int>();
    assert!(len <= std::mem::size_of_val(&control));
    let base = control.as_mut_ptr() as *mut u8;
    // SAFETY: the control buffer is 8-byte aligned and large enough for one
    // header and one int; the writes stay inside it.
    unsafe {
        let h = base as *mut CmsgHdr;
        (*h).len = len;
        (*h).level = SOL_SOCKET;
        (*h).kind = SCM_RIGHTS;
        *(base.add(hdr) as *mut c_int) = fd;
    }
    let mut iov = IoVec {
        base: data.as_ptr() as *mut c_void,
        len: data.len(),
    };
    let msg = MsgHdr {
        name: std::ptr::null_mut(),
        namelen: 0,
        iov: &mut iov,
        iovlen: 1,
        control: control.as_mut_ptr().cast(),
        controllen: align(len),
        flags: 0,
    };
    // SAFETY: msg points at live buffers of the sizes it states.
    let n = unsafe { sendmsg(stream.as_raw_fd(), &msg, 0) };
    assert_eq!(
        n,
        data.len() as isize,
        "sendmsg: {}",
        io::Error::last_os_error()
    );
}

/// What `Native::call` produced.
pub struct Done {
    pub result: io::Result<Reply>,
    pub out: Vec<u8>,
    pub err: Vec<u8>,
}

/// Run a call over the native transport on a thread; panics if it takes longer
/// than 20 seconds (the point of several tests is that it must not hang).
pub fn call_native(call: Call, stdin: Box<dyn Read + Send>, env: Env) -> Done {
    let (tx, rx) = mpsc::channel();
    thread::spawn(move || {
        let (mut out, mut err) = (Vec::new(), Vec::new());
        let result = Native.call(&call, stdin, &mut out, &mut err, &env);
        let _ = tx.send(Done { result, out, err });
    });
    rx.recv_timeout(Duration::from_secs(20))
        .expect("the call did not return")
}

/// Run `plugin` on a thread once the fake `am` has been run.
pub fn spawn_plugin<T: Send + 'static>(
    argv: &Path,
    plugin: impl FnOnce(Contact) -> T + Send + 'static,
) -> thread::JoinHandle<T> {
    let argv = argv.to_path_buf();
    thread::spawn(move || plugin(wait_for_contact(&argv)))
}

/// Read a descriptor to its end (consumes it).
pub fn read_fd(fd: c_int) -> Vec<u8> {
    use std::os::unix::io::FromRawFd;
    // SAFETY: the descriptor was received by the transport and is owned by us.
    let mut f = unsafe { fs::File::from_raw_fd(fd) };
    let mut v = Vec::new();
    f.read_to_end(&mut v).unwrap();
    v
}
