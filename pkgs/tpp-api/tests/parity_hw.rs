//! Parity of `sensor`, `keystore`, `microphone-record` and `usb` with their
//! shell scripts. The descriptor path of `usb -e` cannot be driven by the
//! script harness (its fake client passes no descriptor); `src/cmd/usb.rs` has
//! direct tests for it.

mod common;
use common::{parity, Case, Harness};
use std::process::{Command, Stdio};
use tpp_api::testing::{run_with, Answer, FakeTransport, Outcome};

fn harness(tag: &str) -> Option<Harness> {
    let h = Harness::new(tag);
    if h.is_none() {
        eprintln!("skipped: no sh/bash");
    }
    h
}

fn have_base64() -> bool {
    Command::new("base64")
        .arg("--version")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

/// Same calls, standard output and status as the script; standard error need
/// only be empty on both sides or non-empty on both (the shell's own messages
/// name the script and the line).
fn loose(h: &Harness, imp: &str, args: &[&str], replies: &[&str]) {
    let (so, sc) = h.oracle(imp, args, b"", replies);
    let (ro, rc) = h.rust(imp, args, b"", replies);
    let label = format!("{imp} {args:?}");
    assert_eq!(
        sc, rc,
        "calls differ (left: script, right: rust) for {label}"
    );
    assert_eq!(
        so.stdout_str(),
        ro.stdout_str(),
        "stdout differs for {label}"
    );
    assert_eq!(so.status, ro.status, "status differs for {label}");
    assert_eq!(
        so.stderr.is_empty(),
        ro.stderr.is_empty(),
        "stderr differs (script {:?}, rust {:?}) for {label}",
        so.stderr_str(),
        ro.stderr_str()
    );
}

/// [`parity`] for a command that looks at `TMPDIR` (the script runs with the
/// harness's scratch directory there).
fn parity_tmp(h: &Harness, imp: &str, args: &[&str], replies: &[&str]) -> Outcome {
    let (so, sc) = h.oracle(imp, args, b"", replies);
    let answers: Vec<Answer> = replies.iter().map(|r| Answer::text(r)).collect();
    let t = FakeTransport::with_answers(answers);
    let tmp = h.root.join("tmp").display().to_string();
    let ro = run_with(imp, args, b"", &t, &[("TMPDIR", tmp.as_str())]);
    let label = format!("{imp} {args:?} replies={replies:?}");
    assert_eq!(
        sc,
        t.calls(),
        "calls differ (left: script, right: rust) for {label}"
    );
    assert_eq!(
        so.stdout_str(),
        ro.stdout_str(),
        "stdout differs for {label}"
    );
    assert_eq!(
        so.stderr_str(),
        ro.stderr_str(),
        "stderr differs for {label}"
    );
    assert_eq!(so.status, ro.status, "status differs for {label}");
    ro
}

#[test]
fn sensor() {
    let Some(h) = harness("hw-sensor") else {
        return;
    };
    if !tpp_api::has("sensor") {
        return;
    }
    let cases: &[&[&str]] = &[
        &[],
        &["-h"],
        &["-a"],
        &["-c"],
        &["-l"],
        &["-s", "accelerometer"],
        &["-s", "accelerometer,gyroscope", "-d", "100"],
        &["-s", "a b", "-n", "5"],
        &["-a", "-d", "250"],
        &["-a", "-d", "250", "-n", "3"],
        &["-d", "100"],
        &["-n", "5"],
        &["-d", "abc", "-s", "x"],
        &["-n", "abc", "-s", "x"],
        &["-d", "", "-s", "x"],
        &["-d", "-5", "-s", "x"],
        &["-d", "1 2", "-s", "x"],
        &["-a", "-s", "x"],
        &["-c", "-a"],
        &["-a", "-c"],
        &["-c", "-l"],
        &["-l", "-c"],
        &["-l", "-s", "x"],
        &["-s", "x", "-l"],
        &["-c", "-d", "5"],
        &["-x"],
        &["-s"],
        &["-d"],
        &["-n"],
        &["-h", "-x"],
        &["-x", "-h"],
        &["-ac"],
        &["-as", "x"],
        &["-sx"],
        &["-s", "x", "-s", "y"],
        &["--"],
        &["foo"],
        &["-a", "foo"],
        &["-a", "--", "-c"],
    ];
    for args in cases {
        parity(&h, Case::new("sensor", args).replies(&["ok\n"]));
    }
    // option "," is valid in the script's option string and then fails
    loose(&h, "sensor", &["-,"], &[""]);
    loose(&h, "sensor", &["-a", "-,"], &[""]);
}

#[test]
fn keystore() {
    let Some(h) = harness("hw-keystore") else {
        return;
    };
    if !tpp_api::has("keystore") {
        return;
    }
    let reply = ["[{\"alias\": \"k\"}]\n"];
    let plain: &[&[&str]] = &[
        &[],
        &["-h"],
        &["help"],
        &["bogus"],
        &["list"],
        &["list", "-d"],
        &["list", "x"],
        &["list", "-d", "x"],
        &["delete"],
        &["delete", "a"],
        &["delete", "a b"],
        &["delete", "a", "b"],
        &["delete", ""],
        &["sign"],
        &["sign", "a"],
        &["sign", "a", "b", "c"],
        &["verify"],
        &["verify", "a", "b"],
        &["verify", "a", "b", "c", "d"],
        &["generate"],
        &["generate", "key"],
        &["generate", "key", "-a", "RSA"],
        &["generate", "key", "-a", "RSA", "-s", "2048"],
        &["generate", "key", "-a", "RSA", "-s", "3072"],
        &["generate", "key", "-a", "RSA", "-s", "4096"],
        &["generate", "key", "-a", "RSA", "-s", "1024"],
        &["generate", "key", "-s", "4096"],
        &["generate", "key", "-s", "abc"],
        &["generate", "key", "-a", "EC"],
        &["generate", "key", "-a", "EC", "-s", "256"],
        &["generate", "key", "-a", "EC", "-s", "384"],
        &["generate", "key", "-a", "EC", "-s", "521"],
        &["generate", "key", "-a", "EC", "-s", "2048"],
        &["generate", "key", "-a", "DSA"],
        &["generate", "key", "-a", ""],
        &["generate", "key", "-u", "3600"],
        &["generate", "key", "-a", "EC", "-u", "1", "-s", "384"],
        &["generate", "key", "-c", "x"],
        &["generate", "key", "-c", "x", "-a", "EC", "-s", "521"],
        &["generate", "key", "-a", "EC", "-s", "384", "-a", "RSA"],
        &["generate", "my key", "-a", "EC"],
        &["generate", "-a", "EC"],
        &["generate", "key", "extra"],
        &["generate", "key", "--", "-a", "EC"],
    ];
    for args in plain {
        parity(&h, Case::new("keystore", args).replies(&reply));
    }
    if have_base64() {
        let signs: &[(&str, &[&str])] = &[
            ("QUJD\n", &["sign", "k", "SHA256withRSA"]),
            ("QUJDRA==\n", &["sign", "k", "SHA256withRSA"]),
            ("", &["sign", "k", "SHA256withRSA"]),
            ("QUJD\nRA==\n", &["sign", "k", "SHA256withRSA"]),
            ("QUJD!!!!\n", &["sign", "k", "SHA256withRSA"]),
            ("QQ=\n", &["sign", "k", "SHA256withRSA"]),
            ("QQ\n", &["sign", "k", "SHA256withRSA"]),
            ("QR==\n", &["sign", "k", "SHA256withRSA"]),
            ("Q\n", &["sign", "k", "SHA256withRSA"]),
            ("QUJD RA==\n", &["sign", "k", "SHA256withRSA"]),
            ("+/+/\n", &["sign", "k", "SHA256withRSA"]),
            ("/w==", &["sign", "k", "SHA256withRSA"]),
            ("\n\nQUJD\n\n", &["sign", "k", "SHA256withRSA"]),
            ("QUJDRA==QUJD\n", &["sign", "k", "SHA256withRSA"]),
        ];
        for (reply, args) in signs {
            parity(&h, Case::new("keystore", args).replies(&[reply]));
        }
    } else {
        eprintln!("skipped: keystore sign needs base64");
    }
    // verify with a signature file that exists; the path becomes absolute
    std::fs::write(h.work_dir().join("sig.bin"), b"sig").unwrap();
    std::fs::create_dir_all(h.work_dir().join("sub")).unwrap();
    std::fs::write(h.work_dir().join("sub/s ig.bin"), b"sig").unwrap();
    for args in [
        &["verify", "k", "SHA256withRSA", "sig.bin"][..],
        &["verify", "k", "SHA256withRSA", "./sig.bin"],
        &["verify", "k", "SHA256withRSA", "sub/../sig.bin"],
        &["verify", "k", "SHA256withRSA", "sub/s ig.bin"],
        &["verify", "k", "SHA256withRSA", "sub"],
        &["verify", "k", "SHA256withRSA", "."],
    ] {
        parity(&h, Case::new("keystore", args).replies(&["true\n"]));
    }
    // a signature that is not there: realpath's message, then the call goes on
    loose(
        &h,
        "keystore",
        &["verify", "k", "SHA256withRSA", "missing.bin"],
        &["false\n"],
    );
    loose(
        &h,
        "keystore",
        &["verify", "k", "SHA256withRSA", "nodir/missing.bin"],
        &["false\n"],
    );
    loose(
        &h,
        "keystore",
        &["verify", "k", "SHA256withRSA", ""],
        &["false\n"],
    );
    // getopts' own messages
    loose(&h, "keystore", &["generate", "key", "-x"], &[""]);
    loose(&h, "keystore", &["generate", "key", "-a"], &[""]);
    loose(
        &h,
        "keystore",
        &["generate", "key", "-a", "EC", "-x", "-s", "384"],
        &[""],
    );
    loose(&h, "keystore", &["generate", "key", "-s"], &[""]);
}

#[test]
fn microphone_record() {
    let Some(h) = harness("hw-mic") else { return };
    if !tpp_api::has("microphone-record") {
        return;
    }
    std::fs::create_dir_all(h.work_dir().join("rec dir")).unwrap();
    let cases: &[&[&str]] = &[
        &[],
        &["-h"],
        &["-i"],
        &["-q"],
        &["-i", ""],
        &["-i", "extra"],
        &["-q", "extra"],
        &["-i", "-q"],
        &["-q", "-i"],
        &["-d"],
        &["-f", "out.wav"],
        &["-f", "./out.wav"],
        &["-f", "rec dir/out file.wav"],
        &["-f", "/"],
        &["-f", "a/"],
        &["-f", ""],
        &["-f", "."],
        &["-f", ".."],
        &["-f", "../work/x.wav"],
        &["-l", "10"],
        &["-l", "0"],
        &["-l", "010"],
        &["-l", "0x10"],
        &["-l", "2*3"],
        &["-l", "2+3*4"],
        &["-l", "(2+3)*4"],
        &["-l", "-5"],
        &["-l", "abc"],
        &["-l", ""],
        &["-l", " 7 "],
        &["-l", "1<<4"],
        &["-l", "7%4"],
        &["-l", "3>2"],
        &["-l", "1?5:6"],
        &["-l", "2#101"],
        &["-l", "9223372036854775807"],
        &["-l", "-9223372036854775808"],
        &["-b", "64"],
        &["-b", "1+1"],
        &["-r", "8000"],
        &["-r", "010"],
        &["-r", "44100*2"],
        &["-c", "2"],
        &["-c", "1+1"],
        &["-e", "aac"],
        &["-e", ""],
        &["-e", "amr nb"],
        &[
            "-f", "a.wav", "-l", "5", "-b", "128", "-r", "16000", "-c", "1", "-e", "aac",
        ],
        &["-d", "-l", "5"],
        &["-l", "5", "-d"],
        &["-l", "5", "-i"],
        &["-i", "-l", "5"],
        &["-q", "-d"],
        &["-d", "-q"],
        &["-d", "-i"],
        &["-r", "1", "-r", "2"],
        &["-f", "a.wav", "-f", "b.wav"],
        &["-x"],
        &["-l"],
        &["-f"],
        &["-e"],
        &["-,"],
        &["-h", "-x"],
        &["-x", "-h"],
        &["-dl", "3"],
        &["-ie"],
        &["--"],
        &["foo"],
        &["-d", "foo"],
        &["-d", "--", "-q"],
    ];
    for args in cases {
        // stderr only has to agree in being empty or not, as the shell words
        // its arithmetic and getopts messages by itself
        let (so, _) = h.oracle("microphone-record", args, b"", &[""]);
        if so.stderr.is_empty() {
            parity(
                &h,
                Case::new("microphone-record", args).replies(&["recorded\n"]),
            );
        } else {
            loose(&h, "microphone-record", args, &["recorded\n"]);
        }
    }
}

#[test]
fn microphone_record_arithmetic_errors_end_the_option_loop_only() {
    let Some(h) = harness("hw-mic-arith") else {
        return;
    };
    if !tpp_api::has("microphone-record") {
        return;
    }
    for args in [
        &["-l", "1.5"][..],
        &["-l", "1 2"],
        &["-l", "1/0"],
        &["-l", "09"],
        &["-l", "1.5", "-r", "8000"],
        &["-r", "8000", "-l", "1.5", "-c", "2"],
        &["-d", "-b", "7%0", "-e", "aac"],
        &["-l", "(1"],
        &["-l", "2**-1"],
        &["-l", "1,2"],
        &["-c", "$"],
    ] {
        loose(&h, "microphone-record", args, &["ok\n"]);
    }
}

#[test]
fn usb() {
    let Some(h) = harness("hw-usb") else { return };
    if !tpp_api::has("usb") {
        return;
    }
    let argsets: &[&[&str]] = &[
        &[],
        &["-h"],
        &["-l"],
        &["-l", "x"],
        &["-l", "x", "y"],
        &["-x"],
        &["-e"],
        &["-r"],
        &["-E"],
        &["/dev/bus/usb/001/002"],
        &["/dev/bus/usb/001/002", "extra"],
        &["1234", "5678"],
        &["a", "b", "c"],
        &["-r", "/dev/bus/usb/001/002"],
        &["-r", "1234", "5678"],
        &["-r", "-l"],
        &["-l", "-r"],
        &["-r", "-l", "x"],
        &["-h", "-x"],
        &["-x", "-h"],
        &["--"],
        &["--", "-l"],
        &["-l", "--", "x"],
        &["-r", "-r", "dev"],
        &["-lr"],
        &["-rl"],
    ];
    let permission_replies = [
        "",
        "yes\n",
        "no\n",
        "Permission denied.\n",
        "Permission request timeout.\n",
        "No such device\n",
        "No such device.\n",
        "something else\n",
        "yes",
        "yes\n\n\n",
    ];
    for args in argsets {
        for reply in permission_replies {
            parity_tmp(&h, "usb", args, &[reply]);
        }
    }

    // -e: the script only gets a descriptor from a real app
    let open_args: &[&[&str]] = &[
        &["-e", "true", "/dev/bus/usb/001/002"],
        &["-e", "true", "1234", "5678"],
        &["-e", "", "dev"],
        &["-e", "cat", "-r", "dev"],
        &["-E", "-e", "cat", "dev"],
        &["-e", "cat", "-E", "dev"],
        &["-e", "cat", "-l"],
        &["-l", "-e", "cat"],
        &["-e", "cat", "-l", "dev"],
        &["-e", "cat"],
        &["-e", "cat", "a", "b", "c"],
        &["-e", "my cmd with args", "dev"],
        &["-e", "cat", "-r", "-r", "dev"],
    ];
    let open_replies = [
        "",
        "No such device\n",
        "No such device.\n",
        "No permission\n",
        "Permission denied.\n",
        "Permission request timeout.\n",
        "Failed to open device\n",
        "Open device failed.\n",
        "text\n",
        "text",
        "yes\n",
    ];
    for args in open_args {
        for reply in open_replies {
            parity_tmp(&h, "usb", args, &[reply]);
        }
    }
}
