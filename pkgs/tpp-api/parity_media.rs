//! Parity of wallpaper, share, media-scan, media-player, fingerprint,
//! location, call-log, nfc, camera-photo and notification-channel with their
//! shell scripts.

mod common;
use common::{parity, Case, Harness};
use tpp_api::testing::run_fake;

fn harness(tag: &str) -> Option<Harness> {
    let h = Harness::new(tag);
    if h.is_none() {
        eprintln!("skipped: no sh/bash");
    }
    h
}

/// Files the scripts can work on (in the work directory, their cwd).
fn fixtures(h: &Harness) {
    let w = h.work_dir();
    std::fs::write(w.join("a.txt"), "a").unwrap();
    std::fs::write(w.join("b.txt"), "b").unwrap();
    std::fs::write(w.join("b,c.txt"), "bc").unwrap();
    std::fs::write(w.join("sp ace.txt"), "s").unwrap();
    std::fs::create_dir_all(w.join("sub")).unwrap();
    let _ = std::os::unix::fs::symlink("nowhere", w.join("broken"));
    let _ = std::os::unix::fs::symlink("a.txt", w.join("link"));
}

fn abs(h: &Harness, name: &str) -> String {
    h.work_dir().join(name).to_string_lossy().into_owned()
}

/// Run every argument list against the script and the Rust command.
fn all(h: &Harness, imp: &str, cases: &[&[&str]], replies: &[&str]) {
    for args in cases {
        parity(h, Case::new(imp, args).replies(replies));
    }
}

const JSON: &str = "{\n  \"ok\": 1\n}\n";
const ERR: &str = "{\n  \"API_ERROR\": \"nope\"\n}\n";

#[test]
fn wallpaper() {
    let Some(h) = harness("wallpaper") else {
        return;
    };
    fixtures(&h);
    let a = abs(&h, "a.txt");
    let nodir = abs(&h, "nodir/x");
    let sub = abs(&h, "sub");
    for replies in [&[""][..], &[JSON], &[ERR]] {
        all(
            &h,
            "wallpaper",
            &[
                &[],
                &["-h"],
                &["-x"],
                &["-f"],
                &["-u"],
                &["-l"],
                &["-,"],
                &["-,x"],
                &["-x", "-h"],
                &["-h", "-x"],
                &["-lh"],
                &["-u", "http://x/y.jpg"],
                &["-uhttp://x/y.jpg"],
                &["-l", "-u", "http://x/y.jpg"],
                &["-lu", "http://x/y.jpg"],
                &["-u", "http://x/y.jpg", "-l"],
                &["-u", "http://x/y.jpg", "extra"],
                &["-u", "http://x/y.jpg", "--", "extra"],
                &["-u", "a b"],
                &["-u", ""],
                &["-l", "-u", ""],
                &["-u", "x", "-u", "y"],
                &["-f", "a.txt"],
                &["-f", &a],
                &["-fa.txt"],
                &["-f", "link"],
                &["-l", "-f", "a.txt"],
                &["-f", "a.txt", "-l"],
                &["-f", "a.txt", "extra"],
                &["-f", "a.txt", "-f", "b.txt"],
                &["-f", "a.txt", "-f", "nofile"],
                &["-f", "sp ace.txt"],
                &["-f", "nofile"],
                &["-f", "nofile", "-h"],
                &["-h", "-f", "nofile"],
                &["-f", "nofile", "-x"],
                &["-f", "sub"],
                &["-f", &sub],
                &["-f", &nodir],
                &["-f", "nodir/x", "-u", "y"],
                &["-f", ""],
                &["-f", "a.txt", "-u", "http://x"],
                &["-u", "http://x", "-f", "a.txt"],
                &["-u", "", "-f", "a.txt"],
                &["-f", "a.txt", "-u", ""],
                &["--"],
                &["--", "-u", "x"],
                &["-:"],
            ],
            replies,
        );
    }
}

#[test]
fn share() {
    let Some(h) = harness("share") else { return };
    fixtures(&h);
    let a = abs(&h, "a.txt");
    let sub = abs(&h, "sub");
    for replies in [&[""][..], &[JSON], &[ERR]] {
        all(
            &h,
            "share",
            &[
                &[],
                &["-h"],
                &["-x"],
                &["-a"],
                &["-c"],
                &["-t"],
                &["-d"],
                &["-a", "edit"],
                &["-a", "send"],
                &["-a", "view"],
                &["-aview"],
                &["-a", "bad"],
                &["-a", ""],
                &["-a", "bad", "-h"],
                &["-h", "-a", "bad"],
                &["-x", "-h"],
                &["-c", "text/plain"],
                &["-t", "title"],
                &["-d", "-d"],
                &["-dt", "title"],
                &["-a", "send", "-c", "image/png", "-d", "-t", "tt"],
                &["-t", "tt", "-d", "-c", "x/y", "-a", "edit"],
                &["-a", "edit", "-a", "view"],
                &["-a", "edit", "-a", "bad"],
                &["a.txt"],
                &["--", "a.txt"],
                &[&a],
                &["-d", "a.txt"],
                &["-a", "send", "-t", "tt", "sp ace.txt"],
                &["b,c.txt"],
                &["link"],
                &["a.txt", "-d"],
                &["nofile"],
                &["sub"],
                &[&sub],
                &[""],
                &["a.txt", "b.txt"],
                &["a.txt", "nofile"],
                &["nofile", "b.txt"],
                &["-a", "bad", "a.txt", "b.txt"],
                &["-a", "edit", "nofile"],
            ],
            replies,
        );
    }
    // text on standard input
    for args in [
        &[][..],
        &["-a", "send"],
        &["-d", "-t", "x"],
        &["a.txt"],
        &["nofile"],
    ] {
        parity(&h, Case::new("share", args).stdin(b"hello\nworld\n"));
    }
    parity(&h, Case::new("share", &[]).stdin(b""));
    parity(&h, Case::new("share", &["-h"]).stdin(b"data"));
}

#[test]
fn media_scan() {
    let Some(h) = harness("mscan") else { return };
    fixtures(&h);
    let a = abs(&h, "a.txt");
    let bc = abs(&h, "b,c.txt");
    let sub = abs(&h, "sub");
    let nonexist_abs = abs(&h, "new/file.mp3");
    for replies in [&[""][..], &[JSON], &["scan\n"]] {
        all(
            &h,
            "media-scan",
            &[
                &[],
                &["-h"],
                &["-x"],
                &["-r"],
                &["-v"],
                &["-r", "-v"],
                &["-h", "-x"],
                &["-x", "-h"],
                &["a.txt"],
                &["--", "a.txt"],
                &[&a],
                &["-r", "a.txt"],
                &["-v", "a.txt"],
                &["-r", "-v", "a.txt"],
                &["-v", "-r", "a.txt"],
                &["-rv", "a.txt"],
                &["-vr", "a.txt"],
                &["-rr", "a.txt"],
                &["-v", "-r", "-v", "a.txt"],
                &["-vx", "a.txt"],
                &["a.txt", "b.txt"],
                &["a.txt", "-r"],
                &["b,c.txt"],
                &[&bc],
                &["a.txt", "b,c.txt", "b.txt"],
                &["sp ace.txt"],
                &["sub"],
                &[&sub],
                &["."],
                &[".."],
                &["./a.txt"],
                &["sub/../a.txt"],
                &["link"],
                &["broken"],
                &["nofile"],
                &["nofile.mp3", "a.txt"],
                &["x,y"],
                &["nodir/x,y.mp3", "nodir/z"],
                &["/nonexistent/path/file.mp3"],
                &["/nonexistent/a,b"],
                &[&nonexist_abs],
                &[""],
                &["", "a.txt"],
                &["-v", "/nonexistent/x", "a.txt", "nofile"],
            ],
            replies,
        );
    }
}

#[test]
fn media_player() {
    let Some(h) = harness("mplayer") else { return };
    fixtures(&h);
    let a = abs(&h, "a.txt");
    let sub = abs(&h, "sub");
    for replies in [&[""][..], &[JSON], &[ERR]] {
        all(
            &h,
            "media-player",
            &[
                &[],
                &[""],
                &["", "x"],
                &["help"],
                &["help", "x"],
                &["-h"],
                &["--help"],
                &["-h", "x"],
                &["-x"],
                &["foo"],
                &["foo", "bar"],
                &["INFO"],
                &["info"],
                &["pause"],
                &["stop"],
                &["info", "x"],
                &["pause", "x", "y"],
                &["stop", ""],
                &["play"],
                &["play", "a.txt"],
                &["play", "./a.txt"],
                &["play", &a],
                &["play", "link"],
                &["play", "sp ace.txt"],
                &["play", "nofile"],
                &["play", ""],
                &["play", "sub"],
                &["play", &sub],
                &["play", "-x"],
                &["play", "a.txt", "b.txt"],
                &["play", "a.txt", "nofile"],
                &["play", "nofile", "b.txt"],
                &["play", "a", "b", "c"],
            ],
            replies,
        );
    }
}

#[test]
fn fingerprint() {
    let Some(h) = harness("fprint") else { return };
    let ok = "{\n  \"errors\": [],\n  \"failed_attempts\": 0,\n  \"auth_result\": \"AUTH_RESULT_SUCCESS\"\n}\n";
    for replies in [&[""][..], &[ok], &[ERR]] {
        all(
            &h,
            "fingerprint",
            &[
                &[],
                &["-h"],
                &["-x"],
                &["-t"],
                &["-d"],
                &["-s"],
                &["-c"],
                &["-h", "-x"],
                &["-x", "-h"],
                &["-t", "Title"],
                &["-d", "Desc"],
                &["-s", "Sub"],
                &["-c", "Cancel"],
                &["-tTitle"],
                &["-t", "a title with spaces"],
                &["-t", ""],
                &["-t", "", "-d", ""],
                &["-t", "T", "-d", "D", "-s", "S", "-c", "C"],
                &["-c", "C", "-s", "S", "-d", "D", "-t", "T"],
                &["-s", "S", "-t", "T"],
                &["-t", "one", "-t", "two"],
                &["-t", "one", "-d", "two", "-t", "three"],
                &["-t", "-h"],
                &["-t", "$HOME", "-d", "*"],
                &["-t", "x", "-h"],
                &["-t", "x", "operand"],
                &["operand"],
                &["--"],
                &["--", "-t", "x"],
                &["-t", "x", "--", "-d", "y"],
                &["-t", "x", "-c"],
            ],
            replies,
        );
    }
}

#[test]
fn location() {
    let Some(h) = harness("location") else { return };
    let loc = "{\n  \"latitude\": 1.5,\n  \"longitude\": 2.5\n}\n";
    for replies in [&[""][..], &[loc], &[ERR]] {
        all(
            &h,
            "location",
            &[
                &[],
                &["-h"],
                &["-x"],
                &["-p"],
                &["-r"],
                &["-x", "-h"],
                &["-h", "-x"],
                &["-p", "gps"],
                &["-p", "network"],
                &["-p", "passive"],
                &["-pgps"],
                &["-p", "bad"],
                &["-p", ""],
                &["-p", "GPS"],
                &["-r", "once"],
                &["-r", "last"],
                &["-r", "updates"],
                &["-r", "bad"],
                &["-r", ""],
                &["-p", "gps", "-r", "last"],
                &["-r", "last", "-p", "gps"],
                &["-p", "gps", "-p", "network"],
                &["-r", "once", "-r", "updates"],
                &["-p", "bad", "-r", "bad"],
                &["-r", "bad", "-p", "bad"],
                &["-r", "bad", "-p", "gps"],
                &["-p", "gps", "-r", "bad"],
                &["-p", "gps", "-h"],
                &["-p", "bad", "-h"],
                &["-h", "-p", "bad"],
                &["extra"],
                &["-p", "gps", "extra"],
                &["--"],
                &["--", "extra"],
                &["-p", "bad", "extra"],
                &["-p", "gps", "-r"],
            ],
            replies,
        );
    }
}

#[test]
fn call_log() {
    let Some(h) = harness("calllog") else { return };
    let log = "[\n  {\n    \"name\": \"X\"\n  }\n]\n";
    for replies in [&[""][..], &[log], &[ERR]] {
        all(
            &h,
            "call-log",
            &[
                &[],
                &["-h"],
                &["-x"],
                &["-l"],
                &["-o"],
                &["-h", "-x"],
                &["-x", "-h"],
                &["-l", "5"],
                &["-l5"],
                &["-o", "3"],
                &["-l", "5", "-o", "3"],
                &["-o", "3", "-l", "5"],
                &["-l", "0"],
                &["-l", "007"],
                &["-l", "5", "-l", "6"],
                &["-l", "abc"],
                &["-o", "abc"],
                &["-l", ""],
                &["-o", ""],
                &["-l", "-5"],
                &["-l", "5 "],
                &["-l", " 5"],
                &["-l", "1.5"],
                &["-l", "5\n"],
                &["-l", "abc", "-o", "xyz"],
                &["-l", "5", "-o", "abc"],
                &["-l", "abc", "-o", "5"],
                &["-l", "abc", "extra"],
                &["-l", "5", "extra"],
                &["extra"],
                &["--", "extra"],
                &["-l", "abc", "-h"],
                &["-l", "5", "-h"],
                &["-h", "-l", "abc"],
                &["-l", "5", "-o"],
            ],
            replies,
        );
    }
}

#[test]
fn nfc() {
    let Some(h) = harness("nfc") else { return };
    let tag = "{\n  \"id\": \"04A1\"\n}\n";
    for replies in [&[""][..], &[tag], &[ERR]] {
        all(
            &h,
            "nfc",
            &[
                &[],
                &["-h"],
                &["--help"],
                &["-h", "x"],
                &["-h", "-w"],
                &["-w", "-h"],
                &["-hx"],
                &["-x"],
                &["-x", "-w"],
                &["-w", "-x"],
                &["-r"],
                &["-t"],
                &["-w", "-r"],
                &["-r", "short"],
                &["-r", "full"],
                &["-rshort"],
                &["-r", "other"],
                &["-w"],
                &["-t", "text"],
                &["-t", "hello world"],
                &["-t", ""],
                &["-t", "a", "-t", "b"],
                &["-w", "-t", "text"],
                &["-t", "text", "-w"],
                &["-wt", "text"],
                &["-r", "short", "-t", "x"],
                &["-t", "x", "-r", "full"],
                &["-r", "short", "-w"],
                &["-w", "-r", "short"],
                &["-r", "short", "-w", "-t", "x"],
                &["-r", "full", "-r", "short"],
                &["x"],
                &["-w", "x"],
                &["-r", "short", "x"],
                &["-r", "short", "-w", "x"],
                &["--"],
                &["--", "x"],
                &["-w", "--"],
                &["-w", "--", "-t"],
                &["-w", "-x", "-t", "x"],
            ],
            replies,
        );
    }
}

#[test]
fn camera_photo() {
    let Some(h) = harness("cphoto") else { return };
    fixtures(&h);
    let a = abs(&h, "a.txt");
    let sub = abs(&h, "sub");
    let newabs = abs(&h, "newabs.jpg");
    let nodir = abs(&h, "nodir/x.jpg");
    for replies in [&[""][..], &[JSON], &[ERR]] {
        all(
            &h,
            "camera-photo",
            &[
                &[],
                &["-h"],
                &["-x"],
                &["-c"],
                &["-c", "1"],
                &["-x", "-h"],
                &["-h", "-x"],
                &["-c", "1", "-h"],
                &["photo.jpg"],
                &["--", "photo.jpg"],
                &["-c", "1", "photo.jpg"],
                &["-c1", "photo.jpg"],
                &["-c", "0", "-c", "1", "photo.jpg"],
                &["photo.jpg", "-c", "1"],
                &["a.txt"],
                &[&a],
                &[&newabs],
                &["link"],
                &["sp ace.jpg"],
                &["b,c.jpg"],
                &["./rel.jpg"],
                &["sub/../up.jpg"],
                &["sub"],
                &[&sub],
                &["photo.jpg", "other.jpg"],
                &["-c", "1", "a", "b", "c"],
                &["nodir/x.jpg"],
                &[&nodir],
                &["a.txt/x.jpg"],
                &[""],
                &["broken"],
            ],
            replies,
        );
    }
}

#[test]
fn notification_channel() {
    let Some(h) = harness("nchannel") else { return };
    for replies in [&[""][..], &[JSON], &[ERR]] {
        all(
            &h,
            "notification-channel",
            &[
                &[],
                &[""],
                &["", "x"],
                &["-h"],
                &["--help"],
                &["-h", "x"],
                &["-x"],
                &["-d"],
                &["-d", "id"],
                &["-d", "id", "extra"],
                &["-d", "-d"],
                &["id"],
                &["id", "name"],
                &["id", "name", "extra"],
                &["a", "b", "c", "d"],
                &["-x", "y"],
                &["id", "-d"],
                &["id", "-h"],
                &["--", "id"],
                &["-hx"],
                &["-dx", "y"],
            ],
            replies,
        );
    }
}

// The scripts split the unquoted variables at white space; the Rust commands
// pass the value as one argument (the documented deviation).

#[test]
fn values_with_spaces_stay_together() {
    let (_, calls) = run_fake("share", &["-t", "my title", "-c", "a b"], b"", "");
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].method, "Share");
    assert_eq!(
        calls[0].args,
        ["--es", "title", "my title", "--es", "content-type", "a b"]
    );

    let (_, calls) = run_fake("nfc", &["-r", "a b"], b"", "");
    assert_eq!(
        calls[0].args,
        ["--es", "mode", "read", "--es", "param", "a b"]
    );

    let (_, calls) = run_fake("notification-channel", &["my id", "my name"], b"", "");
    assert_eq!(
        calls[0].args,
        ["--es", "id", "my id", "--es", "name", "my name"]
    );
    let (_, calls) = run_fake("notification-channel", &["-d", "my id"], b"", "");
    assert_eq!(
        calls[0].args,
        ["--ez", "delete", "true", "--es", "id", "my id"]
    );
}

#[test]
fn camera_id_with_spaces_stays_together() {
    let Some(h) = harness("cphoto2") else { return };
    let file = abs(&h, "x.jpg");
    let (o, calls) = run_fake("camera-photo", &["-c", "a b", &file], b"", "");
    assert_eq!(o.status, 0);
    assert_eq!(calls[0].args[..2], ["--es", "camera"]);
    assert_eq!(calls[0].args[2], "a b");
    assert_eq!(calls[0].args[3..5], ["--es", "file"]);
}

#[test]
fn media_scan_uses_pwd_of_the_environment_only_when_it_is_the_cwd() {
    use tpp_api::testing::{run_with, FakeTransport};
    let t = FakeTransport::new();
    let o = run_with(
        "media-scan",
        &["new.txt"],
        b"",
        &t,
        &[("PWD", "/definitely/not/here")],
    );
    assert_eq!(o.status, 0);
    let cwd = std::env::current_dir().unwrap();
    let args = &t.calls()[0].args;
    assert_eq!(args[0], "--esa");
    assert_eq!(args[2], format!("{}/new.txt", cwd.display()));
}
