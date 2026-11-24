//! Parity of the small commands with their shell scripts.

mod common;
use common::{parity, Case, Harness};

fn harness(tag: &str) -> Option<Harness> {
    let h = Harness::new(tag);
    if h.is_none() {
        eprintln!("skipped: no sh/bash");
    }
    h
}

const SIMPLE: &[&str] = &[
    "audio-info",
    "camera-info",
    "contact-list",
    "telephony-cellinfo",
    "telephony-deviceinfo",
    "wifi-connectioninfo",
    "wifi-scaninfo",
    "clipboard-get",
    "infrared-frequencies",
    "tts-engines",
    "notification-remove",
];

#[test]
fn simple_commands() {
    let Some(h) = harness("simple") else { return };
    for imp in SIMPLE.iter().filter(|i| tpp_api::has(i)) {
        let reply = ["{\"a\": 1}\n"];
        for args in [
            &[][..],
            &["-h"],
            &["-x"],
            &["extra"],
            &["-h", "-x"],
            &["-x", "-h"],
            &["--"],
        ] {
            parity(&h, Case::new(imp, args).replies(&reply));
        }
    }
}

/// `tpp battery FIELD` is not in the script (see the module's Deviations), so
/// the single-operand case is left out and tested in the module itself.
#[test]
fn battery_status() {
    let Some(h) = harness("battery") else { return };
    let reply = ["{\"percentage\": 85}\n"];
    for args in [
        &[][..],
        &["-h"],
        &["--help"],
        &["-x"],
        &["extra", "more"],
        &["-h", "-x"],
        &["-x", "-h"],
    ] {
        parity(&h, Case::new("battery-status", args).replies(&reply));
    }
}

#[test]
fn api_start_stop() {
    let Some(h) = harness("apistart") else { return };
    for imp in ["api-start", "api-stop"] {
        for args in [&[][..], &["-h"], &["--help"], &["x", "y"]] {
            parity(&h, Case::new(imp, args));
        }
    }
}

#[test]
fn wifi_enable() {
    let Some(h) = harness("wifi") else { return };
    for args in [
        &[][..],
        &["true"],
        &["false"],
        &["maybe"],
        &["true", "false"],
        &["-h"],
        &["--help"],
    ] {
        parity(&h, Case::new("wifi-enable", args));
    }
}

#[test]
fn clipboard_set() {
    let Some(h) = harness("clipset") else { return };
    parity(
        &h,
        Case::new("clipboard-set", &[]).stdin(b"from stdin\nline two\n"),
    );
    parity(
        &h,
        Case::new("clipboard-set", &["hello", "world"]).stdin(b"ignored"),
    );
    parity(&h, Case::new("clipboard-set", &["-h"]));
    parity(&h, Case::new("clipboard-set", &["-q"]));
    parity(&h, Case::new("clipboard-set", &["--", "-x", "y"]));
    parity(&h, Case::new("clipboard-set", &["a b", "c"]));
}

#[test]
fn saf_rm() {
    let Some(h) = harness("safrm") else { return };
    for (args, reply) in [
        (&["content://x/doc"][..], "0\n"),
        (&["content://x/doc"], "1\n"),
        (&["content://x/doc"], "2"),
        (&[], "0"),
        (&["a", "b"], "0"),
        (&["-h"], "0"),
        (&["-z"], "0"),
    ] {
        parity(&h, Case::new("saf-rm", args).replies(&[reply]));
    }
}

#[test]
fn notification_list() {
    let Some(h) = harness("notiflist") else {
        return;
    };
    let ok = "[\n  {\"id\": 1}\n]\n\n";
    let api_error = "{\n  \"API_ERROR\": \"Notification access not granted\"\n}\n";
    let api_error_other = "{\n  \"x\": 1\n}\n";
    for reply in [ok, "", "\n", api_error, api_error_other] {
        parity(&h, Case::new("notification-list", &[]).replies(&[reply]));
    }
    parity(&h, Case::new("notification-list", &["-h"]));
    parity(&h, Case::new("notification-list", &["x"]));
}

#[test]
fn toast() {
    let Some(h) = harness("toast") else { return };
    parity(&h, Case::new("toast", &["hello", "world"]));
    parity(
        &h,
        Case::new(
            "toast",
            &["-s", "-c", "red", "-b", "#FF0000", "-g", "top", "hi"],
        ),
    );
    parity(&h, Case::new("toast", &["-sc", "red", "hi"]));
    parity(&h, Case::new("toast", &[]).stdin(b"from stdin\n\n"));
    parity(&h, Case::new("toast", &[]).stdin(b"two\nlines"));
    parity(&h, Case::new("toast", &[]).stdin(b"before\0after"));
    parity(&h, Case::new("toast", &[]));
    parity(&h, Case::new("toast", &["-n"]));
    parity(&h, Case::new("toast", &["-e"]));
    parity(&h, Case::new("toast", &["-h"]));
    parity(&h, Case::new("toast", &["-x"]));
    parity(&h, Case::new("toast", &["-c"]));
    parity(&h, Case::new("toast", &["--", "-s"]));
}
