//! `-h`, an illegal option and (where scripts differ) `--help` of every ported
//! command must read exactly like the script's.

mod common;
use common::{parity, Case, Harness};

#[test]
fn help_and_illegal_option_match_the_scripts() {
    let Some(h) = Harness::new("help") else {
        eprintln!("skipped: no sh/bash");
        return;
    };
    for imp in tpp_api::IMPLS {
        for args in [&["-h"][..], &["-Z"], &["--help"]] {
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
        let (o, _) = h.oracle(imp, &["-h"], b"", &["0\n"]);
        if o.status == 0 {
            assert_eq!(
                tpp_api::usage(imp).unwrap(),
                o.stdout_str(),
                "usage of {imp} is not what the script prints"
            );
        }
    }
}
