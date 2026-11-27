//! `-h`, an illegal option and (where scripts differ) `--help` of every ported
//! command must read exactly like the script's.

mod common;
use common::{parity, Case, Harness};

/// Commands without a termux-api script to compare with: the wake lock was a
/// termux-tools script, not part of termux-api.
const NO_SCRIPT: &[&str] = &["wake-lock", "wake-unlock"];

#[test]
fn wake_lock_asks_the_termuxpp_service() {
    let Some(h) = Harness::new("wake") else {
        eprintln!("skipped: no sh/bash");
        return;
    };
    for (imp, action) in [
        ("wake-lock", "dev.pschmitt.termux.service_wake_lock"),
        ("wake-unlock", "dev.pschmitt.termux.service_wake_unlock"),
    ] {
        let (o, calls) = h.rust(imp, &[], b"", &[]);
        assert_eq!(o.status, 0, "{imp}");
        let calls = format!("{calls:?}");
        assert!(calls.contains("startservice") && calls.contains(action), "{imp}: {calls}");
        assert!(calls.contains("dev.pschmitt.termux/com.termux.app.TermuxService"), "{calls}");
        let (o, calls) = h.rust(imp, &["--help"], b"", &[]);
        assert_eq!((o.status, calls.is_empty()), (0, true), "{imp} --help");
        assert_eq!(o.stdout_str(), tpp_api::usage(imp).unwrap());
        let (o, calls) = h.rust(imp, &["now"], b"", &[]);
        assert_eq!((o.status, calls.is_empty()), (1, true), "{imp} now");
    }
}

#[test]
fn help_and_illegal_option_match_the_scripts() {
    let Some(h) = Harness::new("help") else {
        eprintln!("skipped: no sh/bash");
        return;
    };
    for imp in tpp_api::IMPLS {
        if NO_SCRIPT.contains(imp) {
            continue;
        }
        for args in [&["-h"][..], &["-Z"], &["--help"]] {
            if *imp == "clipboard-get" && args == ["-h"] {
                // The public help documents the integrated app's image modes.
                let (outcome, calls) = h.rust(imp, args, b"", &[]);
                assert_eq!(outcome.status, 0);
                assert_eq!(outcome.stdout_str(), tpp_api::usage(imp).unwrap());
                assert!(calls.is_empty());
                continue;
            }
            // wifi-enable takes the keyword first in the dispatcher's table, but
            // the script itself sees only the user arguments, as we do
            if *imp == "microphone-record" {
                // its illegal-option message starts with bash's own script path
                // (and, for --help, bash's getopts text), which cannot be
                // reproduced: compare everything but stderr
                let (so, sc) = h.oracle(imp, args, b"", &["0\n"]);
                let (ro, rc) = h.rust(imp, args, b"", &["0\n"]);
                assert_eq!(sc, rc, "calls differ for {imp} {args:?}");
                assert_eq!(
                    so.stdout_str(),
                    ro.stdout_str(),
                    "stdout differs for {imp} {args:?}"
                );
                assert_eq!(so.status, ro.status, "status differs for {imp} {args:?}");
                continue;
            }
            let (o, _) = parity(&h, Case::new(imp, args).replies(&["0\n"]));
            let _ = o;
        }
    }
}

#[test]
fn usage_text_is_the_captured_help() {
    let Some(h) = Harness::new("usage") else {
        eprintln!("skipped: no sh/bash");
        return;
    };
    for imp in tpp_api::IMPLS {
        if NO_SCRIPT.contains(imp) {
            continue;
        }
        let (o, _) = h.oracle(imp, &["-h"], b"", &["0\n"]);
        if *imp == "clipboard-get" {
            let usage = tpp_api::usage(imp).unwrap();
            assert!(usage.contains("-i  Write the original clipboard image bytes"));
            assert!(usage.contains("-m  Write the image MIME type"));
        } else if o.status == 0 {
            assert_eq!(
                tpp_api::usage(imp).unwrap(),
                o.stdout_str(),
                "usage of {imp} is not what the script prints"
            );
        }
    }
}
