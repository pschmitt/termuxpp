//! SIGINT and SIGTERM while a call waits for the app: the `sensor` command's
//! cleanup (what the script's trap did) and the native transport giving up its
//! waits. The handlers are process wide, so this is a test binary of its own
//! and the tests run one at a time.

#![cfg(any(target_os = "linux", target_os = "android"))]

mod common;

use common::plugin::*;
use std::io::{self, Read, Write};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{mpsc, Mutex};
use std::time::{Duration, Instant};
use tpp_api::sys::{self, InterruptGuard, SIGINT, SIGTERM};
use tpp_api::testing::{run_with, Outcome};
use tpp_api::{Call, Env, Reply, Transport};

static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

fn serial() -> std::sync::MutexGuard<'static, ()> {
    ONE_AT_A_TIME.lock().unwrap_or_else(|e| e.into_inner())
}

/// An app that never ends its first answer by itself: the first call returns
/// when a signal has been recorded, later ones (the cleanup) at once.
#[derive(Default)]
struct Streaming {
    started: AtomicBool,
    calls: Mutex<Vec<Vec<String>>>,
    count: AtomicUsize,
}

impl Transport for Streaming {
    fn call(
        &self,
        call: &Call,
        _stdin: Box<dyn Read + Send>,
        out: &mut dyn Write,
        _err: &mut dyn Write,
        _env: &Env,
    ) -> io::Result<Reply> {
        self.calls.lock().unwrap().push(call.args.clone());
        if self.count.fetch_add(1, Ordering::SeqCst) == 0 {
            out.write_all(b"reading\n")?;
            self.started.store(true, Ordering::SeqCst);
            let deadline = Instant::now() + Duration::from_secs(20);
            while sys::interrupted().is_none() {
                assert!(Instant::now() < deadline, "no signal arrived");
                std::thread::sleep(Duration::from_millis(5));
            }
        }
        Ok(Reply::default())
    }
}

/// Run `sensor` with `args`; `signal` is sent once the call is under way.
fn sensor(args: &[&str], signal: Option<i32>) -> (Outcome, Vec<Vec<String>>) {
    let t = Streaming::default();
    let tr = &t;
    let outcome = std::thread::scope(|s| {
        if let Some(sig) = signal {
            s.spawn(move || {
                while !tr.started.load(Ordering::SeqCst) {
                    std::thread::sleep(Duration::from_millis(2));
                }
                sys::raise_in_process(sig);
            });
        } else {
            tr.count.store(1, Ordering::SeqCst); // no blocking call
        }
        run_with("sensor", args, b"", tr, &[])
    });
    let calls = t.calls.lock().unwrap().clone();
    (outcome, calls)
}

fn strings(a: &[&str]) -> Vec<String> {
    a.iter().map(|s| s.to_string()).collect()
}

#[test]
fn a_signal_while_listening_to_sensors_runs_the_cleanup() {
    let _g = serial();
    for (sig, status) in [(SIGTERM, 143), (SIGINT, 130)] {
        let (o, calls) = sensor(&["-s", "light"], Some(sig));
        assert_eq!(
            o.stdout_str(),
            "reading\nCaught interrupt.. Finishing...\nPerforming sensor cleanup\n"
        );
        assert_eq!(o.stderr_str(), "");
        assert_eq!(o.status, status);
        assert_eq!(calls.len(), 2);
        assert_eq!(
            calls[0],
            strings(&["-a", "sensors", "--es", "sensors", "light"])
        );
        assert_eq!(calls[1], strings(&["-a", "cleanup"]));
        assert!(!sys::watching_interrupts(), "the handlers must be gone");
    }
}

#[test]
fn a_signal_while_listening_to_all_sensors_runs_the_cleanup() {
    let _g = serial();
    let (o, calls) = sensor(&["-a", "-d", "100"], Some(SIGINT));
    assert_eq!(
        o.stdout_str(),
        "reading\nCaught interrupt.. Finishing...\nPerforming sensor cleanup\n"
    );
    assert_eq!(o.status, 130);
    assert_eq!(calls.last().unwrap(), &strings(&["-a", "cleanup"]));
}

#[test]
fn a_signal_with_nothing_to_clean_up_says_so() {
    let _g = serial();
    let (o, calls) = sensor(&["-n", "3"], Some(SIGTERM));
    assert_eq!(
        o.stdout_str(),
        "reading\nCaught interrupt.. Finishing...\nNo cleanup necessary\n"
    );
    assert_eq!(o.status, 143);
    assert_eq!(calls.len(), 1);
}

#[test]
fn without_a_signal_the_stream_ends_normally() {
    let _g = serial();
    let (o, calls) = sensor(&["-s", "light"], None);
    assert_eq!(o.stdout_str(), "");
    assert_eq!(o.status, 0);
    assert_eq!(calls.len(), 1);
    assert!(!sys::watching_interrupts());
}

#[test]
fn a_waiting_connect_ends_with_the_signal() {
    let _g = serial();
    // the app never connects: without the signal the call would wait for ever
    let am = match FakeAm::new("int-accept", "exit 0") {
        Some(am) => am,
        None => return,
    };
    let guard = InterruptGuard::install();
    let argv = am.argv.clone();
    let raiser = std::thread::spawn(move || {
        wait_for_contact(&argv);
        std::thread::sleep(Duration::from_millis(50));
        sys::raise_in_process(SIGTERM);
    });
    let started = Instant::now();
    let done = call_native(Call::new("X"), Box::new(io::empty()), am.env());
    let e = done.result.expect_err("the call must end with an error");
    assert_eq!(e.kind(), io::ErrorKind::Interrupted, "{e}");
    assert!(started.elapsed() < Duration::from_secs(10));
    raiser.join().unwrap();
    assert_eq!(sys::take_interrupt(), Some(SIGTERM));
    drop(guard);
}

#[test]
fn a_streaming_answer_ends_with_the_signal_and_keeps_what_came() {
    let _g = serial();
    let am = match FakeAm::new("int-stream", "exit 0") {
        Some(am) => am,
        None => return,
    };
    let guard = InterruptGuard::install();
    let (release, hold) = mpsc::channel::<()>();
    let plugin = spawn_plugin(&am.argv, move |c| {
        let mut reply = connect(&c.reply_socket).unwrap();
        let stdin = connect(&c.stdin_socket).unwrap();
        reply.write_all(b"partial").unwrap();
        std::thread::sleep(Duration::from_millis(100));
        sys::raise_in_process(SIGINT);
        // the app goes on streaming: only the signal can end the call
        let _ = hold.recv_timeout(Duration::from_secs(20));
        drop((reply, stdin));
    });
    let started = Instant::now();
    let done = call_native(Call::new("Sensor"), Box::new(io::empty()), am.env());
    assert!(done.result.is_ok(), "{:?}", done.result);
    assert_eq!(done.out, b"partial");
    assert!(started.elapsed() < Duration::from_secs(10));
    assert_eq!(sys::take_interrupt(), Some(SIGINT));
    release.send(()).unwrap();
    plugin.join().unwrap();
    drop(guard);
}

#[test]
fn the_native_transport_does_not_poll_without_a_guard() {
    let _g = serial();
    // nothing to watch: an ordinary call works as before
    let am = match FakeAm::new("int-none", "exit 0") {
        Some(am) => am,
        None => return,
    };
    assert!(!sys::watching_interrupts());
    let plugin = spawn_plugin(&am.argv, |c| {
        let mut reply = connect(&c.reply_socket).unwrap();
        let _stdin = connect(&c.stdin_socket).unwrap();
        reply.write_all(b"ok").unwrap();
    });
    let done = call_native(Call::new("X"), Box::new(io::empty()), am.env());
    assert!(done.result.is_ok());
    assert_eq!(done.out, b"ok");
    plugin.join().unwrap();
}
