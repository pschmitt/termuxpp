//! Parity of brightness, torch, vibrate, volume, infrared-transmit,
//! telephony-call, sms-send, speech-to-text and tts-speak with their scripts.

mod common;
use common::{parity, Case, Harness};

fn harness(tag: &str) -> Option<Harness> {
    let h = Harness::new(tag);
    if h.is_none() {
        eprintln!("skipped: no sh/bash");
    }
    h
}

#[test]
fn brightness() {
    let Some(h) = harness("bright") else { return };
    let cases: &[&[&str]] = &[
        &[],
        &["-h"],
        &["--help"],
        &["-h", "x"],
        &["auto"],
        &["0"],
        &["128"],
        &["255"],
        &["300"],
        &["12abc"],
        &["xauto"],
        &["autox"],
        &["auto", "auto"],
        &["50", "60"],
        &["abc"],
        &["-1"],
        &[""],
        &["--"],
        &["-x"],
        &["Auto"],
    ];
    for args in cases {
        parity(&h, Case::new("brightness", args).replies(&["ok\n"]));
    }
}

#[test]
fn torch() {
    let Some(h) = harness("torch") else { return };
    let cases: &[&[&str]] = &[
        &[],
        &["-h"],
        &["--help"],
        &["-h", "on"],
        &["on"],
        &["off"],
        &["ON"],
        &["maybe"],
        &[""],
        &["on", "off"],
        &["on", "-h"],
        &["-x"],
        &["--"],
    ];
    for args in cases {
        parity(&h, Case::new("torch", args));
    }
    parity(&h, Case::new("torch", &["on"]).replies(&["{\"x\": 1}\n"]));
}

#[test]
fn vibrate() {
    let Some(h) = harness("vibrate") else { return };
    let cases: &[&[&str]] = &[
        &[],
        &["-h"],
        &["-f"],
        &["-d", "500"],
        &["-d500"],
        &["-d", "500", "-f"],
        &["-f", "-d", "250"],
        &["-fd", "300"],
        &["-ffd", "300", "-f"],
        &["-d", "1", "-d", "2"],
        &["-d", ""],
        &["-d", " 7 "],
        &["-d"],
        &["-f", "-d"],
        &["-x"],
        &["-fx"],
        &["-f", "-h"],
        &["-x", "-h"],
        &["-h", "-x"],
        &["extra"],
        &["-f", "extra"],
        &["-d", "5", "extra", "-f"],
        &["--", "-f"],
        &["--"],
        &["-"],
    ];
    for args in cases {
        parity(&h, Case::new("vibrate", args));
    }
}

#[test]
fn volume() {
    let Some(h) = harness("volume") else { return };
    let info = "[\n  {\"stream\": \"music\", \"volume\": 5}\n]\n";
    let cases: &[&[&str]] = &[
        &[],
        &["-h"],
        &["--help"],
        &["-h", "x", "y"],
        &["music", "5"],
        &["alarm", "0"],
        &["ring", "007"],
        &["music"],
        &["music", "5", "6"],
        &["music", "x"],
        &["music", "-5"],
        &["music", "5 "],
        &["music", ""],
        &["", "5"],
        &[" ", "5"],
        &["music ", "5"],
        &["-x", "5"],
        &["--", "5"],
        &["a", "b", "c"],
    ];
    for args in cases {
        parity(&h, Case::new("volume", args).replies(&[info]));
    }
    parity(&h, Case::new("volume", &[]).replies(&[""]));
}

#[test]
fn infrared_transmit() {
    let Some(h) = harness("irtx") else { return };
    let cases: &[&[&str]] = &[
        &[],
        &["-h"],
        &["-f", "38000", "20,50,20,30"],
        &["-f38000", "20,50"],
        &["20,50", "-f", "38000"],
        &["-f", "38000"],
        &["-f", "38000", "a", "b"],
        &["-f", "38000", "20,50", "30"],
        &["-f", "1", "-f", "2", "10,10"],
        &["-f", "", "10,10"],
        &["-f", "38000", ""],
        &["-f", "", ""],
        &["-f"],
        &["-f", "-h"],
        &["-x"],
        &["-x", "-h"],
        &["-h", "-x"],
        &["10,10"],
        &["a", "b"],
        &["-h", "-f", "1", "x"],
        &["--", "-f"],
        &["-f", "38000", "--", "10,10"],
        &["-f", "38000", "-- ", "10,10"],
    ];
    for args in cases {
        parity(&h, Case::new("infrared-transmit", args).replies(&["ok\n"]));
    }
}

#[test]
fn telephony_call() {
    let Some(h) = harness("telcall") else { return };
    let cases: &[&[&str]] = &[
        &[],
        &["-h"],
        &["-x"],
        &["-x", "-h"],
        &["-h", "-x"],
        &["+4912345"],
        &["+49 123 45"],
        &[""],
        &["1", "2"],
        &["--", "-5"],
        &["--"],
        &["-"],
        &["1", "-h"],
        &["-hx"],
    ];
    for args in cases {
        parity(&h, Case::new("telephony-call", args).replies(&["done\n"]));
    }
    parity(&h, Case::new("telephony-call", &["1"]).replies(&[""]));
    parity(
        &h,
        Case::new("telephony-call", &["1"]).replies(&["{\"API_ERROR\": \"no permission\"}\n"]),
    );
}

#[test]
fn sms_send() {
    let Some(h) = harness("smssend") else { return };
    let cases: &[(&[&str], &[u8])] = &[
        (&[], b""),
        (&["-h"], b""),
        (&["-x"], b""),
        (&["-n"], b""),
        (&["-n", "123"], b"from stdin\nline two\n"),
        (&["-n", "123"], b""),
        (&["-n123"], b"x"),
        (&["-n", "123,456,789"], b"hi"),
        (&["-n", "123", "hello", "world"], b"ignored"),
        (&["-n", "123", "a  b", "c"], b""),
        (&["-n", "123", "-s", "1", "text"], b""),
        (&["-s", "1", "-n", "123", "text"], b""),
        (&["-s", "1", "text"], b""),
        (&["-n", "123", "-s", "2"], b"stdin"),
        (&["-n", "123", "-s"], b""),
        (&["-n", "1", "-n", "2", "x"], b""),
        (&["-n", "1", "-s", "1", "-s", "2", "x"], b""),
        (&["-n", "", "x"], b""),
        (&["-n", "1", "-s", "", "x"], b""),
        (&["-n", "123", "-h"], b""),
        (&["-x", "-h"], b""),
        (&["-h", "-x"], b""),
        (&["-n", "123", "--", "-s", "x"], b""),
        (&["-n", "123", "-n"], b""),
        (&["-n", "123", "-n", "x"], b""),
        (&["-n", "123", "-e", "x"], b""),
        (&["-n", "123", "text", "-s", "1"], b""),
        // echo options of the shell
        (&["-n", "1", "-n", "text"], b""),
        (&["-n", "1", "--", "-n", "text"], b""),
        (&["-n", "1", "--", "-ne", "text"], b""),
        (&["-n", "1", "--", "-e", "a\\tb\\nc"], b""),
        (&["-n", "1", "--", "-e", "a\\x41\\0101\\101\\\\z\\q"], b""),
        (&["-n", "1", "--", "-e", "a\\cb"], b""),
        (&["-n", "1", "--", "-E", "a\\tb"], b""),
        (&["-n", "1", "--", "-e", "-E", "a\\tb"], b""),
        (&["-n", "1", "--", "-x", "a"], b""),
        (&["-n", "1", "--", "-n"], b""),
        (&["-n", "1", "--", "-", "a"], b""),
        (&["-n", "1", "--", "-n", "-n", "a", "-n"], b""),
        (&["-n", "1", ""], b""),
        (&["-n", "1", "", ""], b""),
        (&["-n", "1", "a\\nb"], b""),
        (&["-n", "1", "ünï"], b""),
    ];
    for (args, stdin) in cases {
        parity(&h, Case::new("sms-send", args).stdin(stdin));
    }
    parity(
        &h,
        Case::new("sms-send", &["-n", "1", "x"]).replies(&["sent\n"]),
    );
}

#[test]
fn speech_to_text() {
    let Some(h) = harness("stt") else { return };
    let replies = [
        "",
        "\n",
        "hello",
        "hello\n",
        "partial\nfinal\n",
        "partial\nfinal",
        "a\nb\n\n",
        "a\n\n\nlast one\n",
        "{\"x\": 1}\n",
        "{\n  \"API_ERROR\": \"no mic\"\n}\n",
    ];
    for reply in replies {
        for args in [
            &[][..],
            &["-p"],
            &["-h"],
            &["-x"],
            &["x"],
            &["-p", "x"],
            &["-ph"],
        ] {
            parity(&h, Case::new("speech-to-text", args).replies(&[reply]));
        }
    }
    for args in [
        &["-p", "-p"][..],
        &["--"],
        &["--", "-p"],
        &["-px"],
        &["-xp"],
        &["-p", "-h"],
    ] {
        parity(&h, Case::new("speech-to-text", args).replies(&["a\nb\n"]));
    }
    // standard input reaches the app
    parity(
        &h,
        Case::new("speech-to-text", &["-p"])
            .stdin(b"in")
            .replies(&["a\n"]),
    );
    parity(
        &h,
        Case::new("speech-to-text", &[])
            .stdin(b"in")
            .replies(&["a\nb\n"]),
    );
}

#[test]
fn tts_speak() {
    let Some(h) = harness("tts") else { return };
    let cases: &[(&[&str], &[u8])] = &[
        (&[], b""),
        (&[], b"read from stdin\nsecond\n"),
        (&["-h"], b""),
        (&["-x"], b""),
        (&["-e"], b""),
        (&["-p"], b""),
        (&["hello", "world"], b"ignored"),
        (&["hello"], b""),
        (&["-e", "eng"], b"x"),
        (&["-e", "eng", "text"], b""),
        (&["-l", "de"], b""),
        (&["-n", "DE"], b""),
        (&["-v", "var"], b""),
        (&["-p", "1.5"], b""),
        (&["-r", "0.5"], b""),
        (&["-s", "ALARM"], b""),
        (
            &[
                "-e", "e", "-l", "l", "-n", "n", "-v", "v", "-p", "1", "-r", "2", "-s", "MUSIC",
                "hi",
            ],
            b"",
        ),
        (
            &[
                "-s", "MUSIC", "-r", "2", "-p", "1", "-v", "v", "-n", "n", "-l", "l", "-e", "e",
            ],
            b"in",
        ),
        (&["-ede", "-lde"], b"x"),
        (&["-l", "de", "-l", "en", "x"], b""),
        (&["-l", ""], b""),
        (&["-l", "", "x"], b""),
        (&["-p", " 1 "], b""),
        (&["-e"], b"x"),
        (&["-l"], b""),
        (&["-s"], b""),
        (&["-p", "1", "-r"], b""),
        (&["-h", "-x"], b""),
        (&["-x", "-h"], b""),
        (&["-l", "de", "-h"], b""),
        (&["--", "-l", "de"], b""),
        (&["-l", "de", "--", "-n"], b""),
        (&["-l", "de", "text", "-p", "1"], b""),
        (&["a  b", "c"], b""),
        (&[""], b""),
        (&["", ""], b""),
        (&["--", "-n", "text"], b""),
        (&["--", "-ne", "text"], b""),
        (&["--", "-e", "a\\tb\\nc"], b""),
        (&["--", "-e", "a\\x41\\0101\\101\\\\z\\q"], b""),
        (&["--", "-e", "a\\cb"], b""),
        (&["--", "-E", "a\\tb"], b""),
        (&["--", "-e", "-E", "a\\tb"], b""),
        (&["--", "-x", "a"], b""),
        (&["--", "-n"], b""),
        (&["--", "-", "a"], b""),
        (&["--", "-n", "-n", "a", "-n"], b""),
        (&["-l", "de", "a\\nb"], b""),
        (&["-l", "de", "ünï"], b""),
    ];
    for (args, stdin) in cases {
        parity(&h, Case::new("tts-speak", args).stdin(stdin));
    }
    parity(&h, Case::new("tts-speak", &["x"]).replies(&["spoken\n"]));
}

/// A failing call: with `-p` the status of the call counts (1), without it
/// the script's `| tail -1` hides it (0).
#[test]
fn speech_to_text_failing_call() {
    use tpp_api::testing::{run_with, Answer, FakeTransport};
    let failing = || {
        FakeTransport::with_answers(vec![Answer {
            error: Some("no app".into()),
            ..Default::default()
        }])
    };
    let t = failing();
    let o = run_with("speech-to-text", &[], b"", &t, &[]);
    assert_eq!((o.status, o.stdout_str()), (0, String::new()));
    assert!(o.stderr_str().contains("no app"));
    let t = failing();
    let o = run_with("speech-to-text", &["-p"], b"", &t, &[]);
    assert_eq!((o.status, o.stdout_str()), (1, String::new()));
    assert!(o.stderr_str().contains("no app"));
}
