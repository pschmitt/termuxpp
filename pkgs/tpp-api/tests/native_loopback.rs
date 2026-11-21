//! The native transport against a stand-in plugin over real abstract unix
//! sockets: payload and standard input forwarding, descriptor passing, the
//! lone "@" message, abstract socket names, and the failure paths that must not
//! hang. No app, device or network is involved; the `am` that contacts the
//! plugin is a fake script that records its arguments.
//!
//! Signals are in `interrupt.rs` (a process of their own, since the handlers
//! are process wide).

#![cfg(any(target_os = "linux", target_os = "android"))]

mod common;

use common::plugin::*;
use std::fs;
use std::io::{self, Cursor, Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};
use std::time::{Duration, Instant};
use tpp_api::Call;

#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(not(target_os = "android"))]
use std::os::linux::net::SocketAddrExt;
use std::os::unix::io::AsRawFd;
use std::os::unix::net::{SocketAddr, UnixListener};

macro_rules! fake_am {
    ($tag:expr) => {
        match FakeAm::new($tag, "exit 0") {
            Some(am) => am,
            None => return,
        }
    };
}

/// A reader that yields what the test sends and ends when the sender goes.
struct Channel(mpsc::Receiver<Vec<u8>>);

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

/// A reader that says when it is dropped.
struct Tracked(Cursor<Vec<u8>>, Arc<AtomicBool>);

impl Read for Tracked {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.0.read(buf)
    }
}

impl Drop for Tracked {
    fn drop(&mut self) {
        self.1.store(true, Ordering::SeqCst);
    }
}

fn read_all(mut r: impl Read) -> Vec<u8> {
    let mut v = Vec::new();
    let _ = r.read_to_end(&mut v);
    v
}

fn eventually(what: &str, mut f: impl FnMut() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(10);
    while !f() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(5));
    }
}

#[test]
fn the_call_reaches_the_plugin_and_the_answer_comes_back() {
    let am = fake_am!("roundtrip");
    let plugin = spawn_plugin(&am.argv, |c| {
        let mut reply = connect(&c.reply_socket).unwrap();
        let stdin = connect(&c.stdin_socket).unwrap();
        let input = read_all(&stdin);
        reply.write_all(b"hello ").unwrap();
        reply.write_all(b"world").unwrap();
        (c, input)
    });
    let call = Call::new("Echo").es("text", "a b").ez("flag", "true");
    let done = call_native(
        call,
        Box::new(Cursor::new(b"standard input".to_vec())),
        am.env(),
    );
    assert!(done.result.is_ok(), "{:?}", done.result);
    assert_eq!(done.out, b"hello world");
    assert_eq!(done.err, b"");

    let (contact, input) = plugin.join().unwrap();
    assert_eq!(input, b"standard input");
    // what `am` was told, as termux-api.c does it
    assert_eq!(
        &contact.argv[..4],
        ["broadcast", "--user", "0", "-n"].map(String::from)
    );
    assert_eq!(contact.argv[4], tpp_api::native::RECEIVER);
    assert_eq!(contact.extra("api_method"), Some("Echo"));
    assert_eq!(
        contact.extra("api_server_pid"),
        Some(std::process::id().to_string().as_str())
    );
    let tail: Vec<&str> = contact
        .argv
        .iter()
        .rev()
        .take(5)
        .map(String::as_str)
        .collect();
    assert_eq!(tail, ["true", "flag", "--ez", "a b", "text"]);
}

#[test]
fn standard_input_is_forwarded_while_the_answer_streams() {
    let am = fake_am!("streaming");
    let (tx, rx) = mpsc::channel::<Vec<u8>>();
    let plugin = spawn_plugin(&am.argv, |c| {
        let mut reply = connect(&c.reply_socket).unwrap();
        let mut stdin = connect(&c.stdin_socket).unwrap();
        let mut buf = [0u8; 64];
        loop {
            let n = stdin.read(&mut buf).unwrap();
            if n == 0 {
                break;
            }
            // echo each chunk as soon as it arrives
            reply.write_all(&buf[..n]).unwrap();
        }
    });
    let sender = std::thread::spawn(move || {
        for chunk in [&b"one "[..], b"two ", b"three"] {
            tx.send(chunk.to_vec()).unwrap();
            std::thread::sleep(Duration::from_millis(30));
        }
    });
    let done = call_native(Call::new("Echo"), Box::new(Channel(rx)), am.env());
    assert!(done.result.is_ok());
    assert_eq!(done.out, b"one two three");
    sender.join().unwrap();
    plugin.join().unwrap();
}

#[test]
fn binary_answers_pass_through_unchanged() {
    let am = fake_am!("binary");
    let data: Vec<u8> = (0..(1 << 20))
        .map(|i: u32| (i.wrapping_mul(31) >> 3) as u8)
        .collect();
    let sent = data.clone();
    let plugin = spawn_plugin(&am.argv, move |c| {
        let mut reply = connect(&c.reply_socket).unwrap();
        let _stdin = connect(&c.stdin_socket).unwrap();
        reply.write_all(&sent).unwrap();
    });
    let done = call_native(Call::new("Photo"), Box::new(io::empty()), am.env());
    assert!(done.result.is_ok());
    assert_eq!(done.out.len(), data.len());
    assert!(done.out == data, "the bytes differ");
    plugin.join().unwrap();
}

/// A file with `content`, opened for reading.
fn device(am: &FakeAm, name: &str, content: &[u8]) -> fs::File {
    let path = am.dir.join(name);
    fs::write(&path, content).unwrap();
    fs::File::open(path).unwrap()
}

#[test]
fn a_passed_descriptor_arrives_and_the_lone_at_is_swallowed() {
    let am = fake_am!("fd");
    let file = device(&am, "dev", b"device bytes");
    let plugin = spawn_plugin(&am.argv, move |c| {
        let reply = connect(&c.reply_socket).unwrap();
        let _stdin = connect(&c.stdin_socket).unwrap();
        // a descriptor needs a message: "@" says there is no output
        send_with_fd(&reply, b"@", file.as_raw_fd());
        // later output is passed on
        std::thread::sleep(Duration::from_millis(50));
        let mut reply = reply;
        reply.write_all(b"tail").unwrap();
    });
    let done = call_native(Call::new("Usb"), Box::new(io::empty()), am.env());
    let reply = done.result.unwrap();
    assert_eq!(done.out, b"tail");
    let fd = reply.fd.expect("the descriptor");
    assert_eq!(read_fd(fd), b"device bytes");
    plugin.join().unwrap();
}

#[test]
fn at_with_other_bytes_or_without_a_descriptor_is_output() {
    // "@" without a descriptor
    let am = fake_am!("at-plain");
    let plugin = spawn_plugin(&am.argv, |c| {
        let mut reply = connect(&c.reply_socket).unwrap();
        let _stdin = connect(&c.stdin_socket).unwrap();
        reply.write_all(b"@").unwrap();
    });
    let done = call_native(Call::new("X"), Box::new(io::empty()), am.env());
    let reply = done.result.unwrap();
    assert_eq!(done.out, b"@");
    assert_eq!(reply.fd, None);
    plugin.join().unwrap();

    // "@x" with a descriptor
    let am = fake_am!("at-more");
    let file = device(&am, "dev", b"x");
    let plugin = spawn_plugin(&am.argv, move |c| {
        let reply = connect(&c.reply_socket).unwrap();
        let _stdin = connect(&c.stdin_socket).unwrap();
        send_with_fd(&reply, b"@x", file.as_raw_fd());
    });
    let done = call_native(Call::new("X"), Box::new(io::empty()), am.env());
    let reply = done.result.unwrap();
    assert_eq!(done.out, b"@x");
    assert!(reply.fd.is_some());
    let mut reply = reply;
    reply.close_fd();
    plugin.join().unwrap();
}

#[test]
fn a_later_descriptor_replaces_the_earlier_one() {
    let am = fake_am!("two-fds");
    let first = device(&am, "first", b"first");
    let second = device(&am, "second", b"second");
    let plugin = spawn_plugin(&am.argv, move |c| {
        let reply = connect(&c.reply_socket).unwrap();
        let _stdin = connect(&c.stdin_socket).unwrap();
        send_with_fd(&reply, b"@", first.as_raw_fd());
        std::thread::sleep(Duration::from_millis(50));
        send_with_fd(&reply, b"@", second.as_raw_fd());
    });
    let done = call_native(Call::new("Usb"), Box::new(io::empty()), am.env());
    let reply = done.result.unwrap();
    assert_eq!(done.out, b"");
    assert_eq!(read_fd(reply.fd.unwrap()), b"second");
    plugin.join().unwrap();
}

#[test]
fn the_input_copy_ends_with_the_answer_that_carries_the_descriptor() {
    let am = fake_am!("stop-copy");
    let file = device(&am, "dev", b"d");
    let (tx, rx) = mpsc::channel::<Vec<u8>>();
    tx.send(b"early".to_vec()).unwrap();
    let plugin = spawn_plugin(&am.argv, move |c| {
        let reply = connect(&c.reply_socket).unwrap();
        let mut stdin = connect(&c.stdin_socket).unwrap();
        // the first chunk is forwarded
        let mut buf = [0u8; 5];
        stdin.read_exact(&mut buf).unwrap();
        assert_eq!(&buf, b"early");
        send_with_fd(&reply, b"@", file.as_raw_fd());
        drop(reply); // the answer is complete: the call returns
                     // whatever is read after this is not for the app: the copy ends
        read_all(&stdin)
    });
    let done = call_native(Call::new("Usb"), Box::new(Channel(rx)), am.env());
    let mut reply = done.result.unwrap();
    reply.close_fd();

    // the thread that copies is waiting in a read of the input: when that read
    // returns, its data is dropped and the thread ends
    tx.send(b"late".to_vec()).unwrap();
    assert_eq!(plugin.join().unwrap(), b"");
    eventually("the input to be dropped", || tx.send(Vec::new()).is_err());
}

#[test]
fn a_plugin_that_never_takes_the_input_does_not_leave_the_copy_waiting() {
    let am = fake_am!("no-stdin");
    let dropped = Arc::new(AtomicBool::new(false));
    let stdin = Tracked(Cursor::new(b"unused".to_vec()), dropped.clone());
    let plugin = spawn_plugin(&am.argv, |c| {
        let mut reply = connect(&c.reply_socket).unwrap();
        reply.write_all(b"ok").unwrap();
    });
    let done = call_native(Call::new("X"), Box::new(stdin), am.env());
    assert!(done.result.is_ok());
    assert_eq!(done.out, b"ok");
    plugin.join().unwrap();
    eventually("the copy to end", || dropped.load(Ordering::SeqCst));
}

#[test]
fn an_empty_answer_is_fine() {
    let am = fake_am!("empty");
    let plugin = spawn_plugin(&am.argv, |c| {
        let _reply = connect(&c.reply_socket).unwrap();
        let _stdin = connect(&c.stdin_socket).unwrap();
    });
    let done = call_native(Call::new("X"), Box::new(io::empty()), am.env());
    let reply = done.result.unwrap();
    assert_eq!(done.out, b"");
    assert_eq!(reply.fd, None);
    plugin.join().unwrap();
}

/// The abstract sockets of `/proc/net/unix` (their names, without the "@").
fn abstract_sockets() -> String {
    fs::read_to_string("/proc/net/unix").unwrap_or_default()
}

#[test]
fn the_sockets_are_abstract_unique_and_released_after_the_call() {
    let am = fake_am!("names");
    let plugin = spawn_plugin(&am.argv, |c| {
        // the names are in the abstract namespace (shown with a "@")...
        let listing = abstract_sockets();
        assert!(
            listing.contains(&format!("@{}", c.reply_socket)),
            "reply socket"
        );
        assert!(
            listing.contains(&format!("@{}", c.stdin_socket)),
            "stdin socket"
        );
        // ...and are exact: a longer name is another socket
        assert!(connect(&format!("{}x", c.reply_socket)).is_err());
        let mut reply = connect(&c.reply_socket).unwrap();
        let _stdin = connect(&c.stdin_socket).unwrap();
        reply.write_all(b"x").unwrap();
        c
    });
    let done = call_native(Call::new("X"), Box::new(io::empty()), am.env());
    assert!(done.result.is_ok());
    let c = plugin.join().unwrap();
    assert_ne!(c.reply_socket, c.stdin_socket);
    // the name read by the client is free again once the call returned
    let addr = SocketAddr::from_abstract_name(&c.reply_socket).unwrap();
    UnixListener::bind_addr(&addr).expect("the reply socket is released");

    // another call, other names
    let am2 = fake_am!("names2");
    let plugin = spawn_plugin(&am2.argv, |c| {
        let mut reply = connect(&c.reply_socket).unwrap();
        let _stdin = connect(&c.stdin_socket).unwrap();
        reply.write_all(b"x").unwrap();
        c
    });
    let done = call_native(Call::new("X"), Box::new(io::empty()), am2.env());
    assert!(done.result.is_ok());
    let c2 = plugin.join().unwrap();
    assert_ne!(c.reply_socket, c2.reply_socket);
    assert_ne!(c.stdin_socket, c2.stdin_socket);
    // the output socket is released too, once the copy of the input ended
    eventually("the stdin socket to be released", || {
        let addr = SocketAddr::from_abstract_name(&c.stdin_socket).unwrap();
        UnixListener::bind_addr(&addr).is_ok()
    });
}

#[test]
fn a_failing_am_is_an_error_not_a_hang() {
    let am = match FakeAm::new("am-fails", "exit 3") {
        Some(am) => am,
        None => return,
    };
    let done = call_native(Call::new("X"), Box::new(io::empty()), am.env());
    let e = done.result.expect_err("the call must fail");
    assert!(e.to_string().contains("exit status: 3"), "{e}");
}

#[test]
fn a_missing_am_is_an_error_not_a_hang() {
    let mut env = tpp_api::Env::empty();
    env.set("TPP_API_AM", "/nonexistent/tpp-api-test/am");
    let done = call_native(Call::new("X"), Box::new(io::empty()), env);
    let e = done.result.expect_err("the call must fail");
    assert!(
        (e.kind() == io::ErrorKind::NotFound
            && e.to_string().contains("/nonexistent/tpp-api-test/am"))
            || e.to_string().contains("exit status: 127"),
        "{e}"
    );
}
