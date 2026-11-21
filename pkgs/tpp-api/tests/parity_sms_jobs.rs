//! Parity of `sms-list` and `job-scheduler` with their shell scripts.

mod common;
use common::{parity, Case, Harness};
use std::path::PathBuf;

fn harness(tag: &str) -> Option<Harness> {
    let h = Harness::new(tag);
    if h.is_none() {
        eprintln!("skipped: no sh/bash");
    }
    h
}

fn on_path(name: &str) -> bool {
    std::env::var_os("PATH")
        .map(|p| {
            std::env::split_paths(&p)
                .map(|d| d.join(name))
                .any(|f: PathBuf| f.is_file())
        })
        .unwrap_or(false)
}

const LIST: &str = "[\n  {\"body\": \"hi\"}\n]\n";

// ---------------------------------------------------------------- sms-list

#[test]
fn sms_list_help_and_usage_errors() {
    let Some(h) = harness("smshelp") else { return };
    for args in [
        &["-h"][..],
        &["--help"],
        &["-h", "-x"],
        &["-x", "-h"],
        &["--help", "--bogus"],
        &["-vv", "-h"],
        &["-q", "-h"],
        &["-x"],
        &["-xh"],
        &["-:"],
        &["--bogus"],
        &["--help=1"],
        &["--help=x"],
        &["--quiet=1"],
        &["--conversation-list=true"],
        &["---"],
        &["--=x"],
        &["--="],
        &["-vx"],
        &["-c", "--nope"],
    ] {
        parity(&h, Case::new("sms-list", args).replies(&[LIST]));
    }
}

#[test]
fn sms_list_missing_values() {
    let Some(h) = harness("smsmissing") else {
        return;
    };
    for args in [
        &["-f"][..],
        &["-l"],
        &["-o"],
        &["-t"],
        &["-c", "-f"],
        &["-vf"],
        &["-v-"],
        &["--conversation-limit"],
        &["--conversation-limit="],
        &["--conversation-offset"],
        &["--conversation-offset="],
        &["--conversation-selection"],
        &["--conversation-selection="],
        &["--conversation-sort-order"],
        &["--conversation-sort-order="],
        &["--message-address"],
        &["--message-address="],
        &["--message-limit"],
        &["--message-limit="],
        &["--message-offset"],
        &["--message-offset="],
        &["--message-selection"],
        &["--message-selection="],
        &["--message-sort-order"],
        &["--message-sort-order="],
        &["--message-type"],
        &["--message-type="],
        // the separate value form does not exist
        &["--message-limit", "5"],
        &["--message-type", "inbox"],
        &["--message-limit", "5", "--message-offset=2"],
    ] {
        parity(&h, Case::new("sms-list", args).replies(&[LIST]));
    }
}

#[test]
fn sms_list_plain_and_short_options() {
    let Some(h) = harness("smsshort") else { return };
    for args in [
        &[][..],
        &["-c"],
        &["-d"],
        &["-n"],
        &["-dn"],
        &["-cdn"],
        &["-f", "+4912345"],
        &["-f+4912345"],
        &["-f", ""],
        &["-f", "a b"],
        &["-l", "3"],
        &["-l3"],
        &["-l", ""],
        &["-l", "abc"],
        &["-o", "7"],
        &["-o7"],
        &["-o", "-1"],
        &["-t", "inbox"],
        &["-tsent"],
        &["-t", "draft"],
        &["-t", "outbox"],
        &["-t", "failed"],
        &["-t", "queued"],
        &["-t", "all"],
        &["-d", "-n", "-l", "2", "-o", "1", "-t", "sent", "-f", "666"],
        &["-dnl2o1tsent"],
        &["-l", "1", "-l", "2"],
        &["-t", "inbox", "-t", "sent"],
        &["--"],
        &["-q"],
        &["-qc"],
    ] {
        parity(&h, Case::new("sms-list", args).replies(&[LIST]));
    }
}

#[test]
fn sms_list_message_type_validation() {
    let Some(h) = harness("smstype") else { return };
    for args in [
        &["-t", "bogus"][..],
        &["-t", ""],
        &["-t", "INBOX"],
        &["-t", "inbox "],
        &["-t", "1"],
        &["--message-type=bogus"],
        &["--message-type=all|inbox"],
        &["-vv", "-t", "bogus"],
        &["-q", "-t", "bogus"],
        &["-c", "-t", "bogus"],
    ] {
        parity(&h, Case::new("sms-list", args).replies(&[LIST]));
    }
}

#[test]
fn sms_list_long_options() {
    let Some(h) = harness("smslong") else { return };
    for args in [
        &["--quiet"][..],
        &["--conversation-list"],
        &["--conversation-list", "--conversation-limit=3"],
        &["--conversation-list", "--conversation-offset=4"],
        &[
            "--conversation-list",
            "--conversation-selection=address == '666'",
        ],
        &[
            "--conversation-list",
            "--conversation-sort-order=thread_id ASC",
        ],
        &[
            "--conversation-list",
            "--conversation-return-multiple-messages",
        ],
        &["--conversation-list", "--conversation-return-nested-view"],
        &[
            "--conversation-list",
            "--conversation-return-no-order-reverse",
        ],
        &[
            "--conversation-list",
            "--conversation-return-no-order-reverse",
            "--conversation-sort-order=x",
        ],
        &[
            "--conversation-list",
            "--conversation-sort-order=thread_id ASC",
        ],
        &[
            "--conversation-list",
            "--conversation-return-multiple-messages",
            "--conversation-return-nested-view",
            "--conversation-limit=1",
            "--message-limit=5",
        ],
        &[
            "--conversation-list",
            "--conversation-return-multiple-messages",
            "--conversation-return-nested-view",
            "--conversation-sort-order=thread_id ASC",
            "--conversation-return-no-order-reverse",
            "--conversation-limit=2",
            "--conversation-offset=3",
            "--conversation-selection=thread_id == 6",
        ],
        // conversation options without --conversation-list are not passed
        &["--conversation-limit=3"],
        &["--conversation-offset=3"],
        &["--conversation-selection=x"],
        &["--conversation-sort-order=x"],
        &["--conversation-return-multiple-messages"],
        &["--conversation-return-nested-view"],
        &["--conversation-return-no-order-reverse"],
        &["-c", "--conversation-limit=3"],
        &["--message-address=666"],
        &["--message-address=a=b"],
        &["--message-limit=1"],
        &["--message-limit=a b"],
        &["--message-offset=5"],
        &["--message-return-no-order-reverse"],
        &["--message-selection=type == 1"],
        &["--message-selection=type == 1 and address == '666' and body LIKE 'Foo %'"],
        &["--message-selection==x"],
        &["--message-sort-order=date ASC"],
        &[
            "--message-sort-order=date ASC",
            "--message-return-no-order-reverse",
        ],
        &["--message-type=inbox"],
        &["--message-type=sent", "--message-limit=1"],
        &["--message-limit=1", "--message-limit=2"],
        &["-l", "4", "--message-limit=2"],
        &["--message-limit=2", "-l", "4"],
        &["-d", "--message-limit=2", "-n"],
        &["-v-", ""],
        &["-v-", "message-limit=3"],
        &["-v-", "bogus"],
    ] {
        parity(&h, Case::new("sms-list", args).replies(&[LIST]));
    }
}

#[test]
fn sms_list_log_levels() {
    let Some(h) = harness("smslog") else { return };
    for args in [
        &["-v"][..],
        &["-vv"],
        &["-v", "-v", "-v"],
        &["-vvv"],
        &["-vvvv"],
        &["-vvvvv"],
        &["-vvvvvv"],
        &["-v", "-v", "-v", "-v", "-v", "-v"],
        &["-vq"],
        &["-qv"],
        &["-q", "-vv"],
        &["-vv", "-q"],
        &["-vv", "--quiet"],
        &["--quiet", "-vv"],
        &["-vv", "-c"],
        &[
            "-vv",
            "-c",
            "--conversation-limit=2",
            "-d",
            "-n",
            "-f",
            "x y",
        ],
        &["-vv", "-t", "sent", "--message-selection=type == 2"],
        &["-vv", "--help"],
        &["-vv", "-x"],
        &["-vv", "--message-limit"],
    ] {
        parity(&h, Case::new("sms-list", args).replies(&[LIST]));
    }
}

#[test]
fn sms_list_app_answers() {
    let Some(h) = harness("smsanswer") else {
        return;
    };
    for reply in [LIST, "", "\n", "no newline", "{\"API_ERROR\": \"x\"}\n"] {
        parity(&h, Case::new("sms-list", &[]).replies(&[reply]));
        parity(&h, Case::new("sms-list", &["-vv"]).replies(&[reply]));
        parity(&h, Case::new("sms-list", &["-c"]).replies(&[reply]));
    }
    // standard input is of no concern to the script, the call inherits it
    parity(
        &h,
        Case::new("sms-list", &[]).stdin(b"data\n").replies(&[LIST]),
    );
}

/// Operands make the script call the undefined `logger__log_error`; bash names
/// its own path in that message, which the Rust command cannot know.
#[test]
fn sms_list_operands() {
    let Some(h) = harness("smsoperands") else {
        return;
    };
    for args in [
        &["foo"][..],
        &["foo", "bar"],
        &["--", "foo"],
        &["-c", "foo"],
        &["-l", "3", "foo", "-c"],
        &["-vv", "foo"],
        &["-vv", "foo", "-c"],
        &["--", "-l"],
        &["-c", "--", "-l"],
        &["-q", "foo"],
        &["-"],
        &[""],
        &["-v-", "", "foo"],
    ] {
        let case = Case::new("sms-list", args).replies(&[LIST]);
        let (so, sc) = h.oracle(case.imp, case.args, case.stdin, case.replies);
        let (ro, rc) = h.rust(case.imp, case.args, case.stdin, case.replies);
        let script = format!("{}/scripts/sms-list", h.root.display());
        let stderr = so
            .stderr_str()
            .replace(&format!("{script}:"), "tpp sms list:");
        assert_eq!(sc, rc, "calls differ for {args:?}");
        assert_eq!(
            so.stdout_str(),
            ro.stdout_str(),
            "stdout differs for {args:?}"
        );
        assert_eq!(stderr, ro.stderr_str(), "stderr differs for {args:?}");
        assert_eq!(so.status, ro.status, "status differs for {args:?}");
        assert_eq!(ro.status, 64, "status for {args:?}");
        assert!(
            ro.stderr_str()
                .ends_with("line 377: logger__log_error: command not found\n"),
            "{args:?}: {}",
            ro.stderr_str()
        );
    }
}

#[test]
fn sms_list_calls_are_what_the_app_expects() {
    let Some(h) = harness("smscalls") else { return };
    let (_, calls) = parity(&h, Case::new("sms-list", &[]));
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].method, "SmsInbox");
    assert_eq!(
        calls[0].args,
        ["--ei", "limit", "10", "--ei", "offset", "0", "--ei", "type", "0"]
    );
    let (o, calls) = parity(&h, Case::new("sms-list", &["-t", "bogus"]));
    assert!(calls.is_empty());
    assert_eq!(o.status, 1);
    let (o, calls) = parity(&h, Case::new("sms-list", &["-x"]));
    assert!(calls.is_empty());
    assert_eq!(o.status, 64);
    assert_eq!(o.stdout_str(), tpp_api::cmd::sms_list::USAGE);
    let (o, _) = parity(&h, Case::new("sms-list", &["--help"]));
    assert_eq!(o.stdout_str(), tpp_api::cmd::sms_list::USAGE);
}

// ------------------------------------------------------------ job-scheduler

fn job_harness(tag: &str) -> Option<Harness> {
    if !on_path("getopt") {
        eprintln!("skipped: no util-linux getopt");
        return None;
    }
    harness(tag)
}

#[test]
fn job_scheduler_help_and_errors() {
    let Some(h) = job_harness("jobhelp") else {
        return;
    };
    for args in [
        &[][..],
        &["-h"],
        &["--help"],
        &["--hel"],
        &["--he"],
        &["-ph"],
        &["-hp"],
        &["-h", "-x"],
        &["-x", "-h"],
        &["--help", "--bogus"],
        &["-p", "--help"],
        &["foo", "-h"],
        &["-h", "foo"],
        &["-x"],
        &["-xyz"],
        &["-:"],
        &["-W"],
        &["--bogus"],
        &["--bogus=1"],
        &["--help=1"],
        &["--pending=x"],
        &["--pend=x"],
        &["--cancel=x"],
        &["--ca"],
        &["--cancel-a"],
        &["--c"],
        &["--ch"],
        &["--pe"],
        &["--per"],
        &["--p"],
        &["--s"],
        &["--st"],
        &["--t"],
        &["--tr"],
        &["--trigger-content-"],
        &["--trigger-content-f", "2"],
        &["--n", "any"],
        &["--j", "3"],
        &["--b", "true"],
        &["-s"],
        &["--script"],
        &["--script="],
        &["--job-id"],
        &["--period-ms"],
        &["-ps"],
        &["-x", "-s"],
        &["-x", "--bogus", "-y"],
        &["-ph", "-x"],
        &["--"],
        &["--", "--help"],
        &["--", "-h"],
        &["-", "-h"],
        &["foo"],
        &["foo", "bar"],
        &["-p", "foo"],
        &["foo", "-p"],
        &["-p", "--", "foo"],
        &["-", "-p"],
        &[""],
        &["-p", ""],
    ] {
        parity(&h, Case::new("job-scheduler", args).replies(&["ok\n"]));
    }
}

#[test]
fn job_scheduler_options() {
    let Some(h) = job_harness("jobopts") else {
        return;
    };
    for args in [
        &["-p"][..],
        &["--pending"],
        &["--pend"],
        &["--cancel"],
        &["--cancel-all"],
        &["--cancel-al"],
        &["--cancel", "--cancel-all"],
        &["-s", "/path/to/script.sh"],
        &["-s/path/to/script.sh"],
        &["-s=x"],
        &["--script", "/a b/c"],
        &["--script=/a b/c"],
        &["--scr", "x"],
        &["--scr=x"],
        &["--script=a=b"],
        &["--script", "--pending"],
        &["-s", "-p"],
        &["-s", "--"],
        &["-s", ""],
        &["--script="],
        &["--script", ""],
        &["--job-id", "7"],
        &["--job-id=7"],
        &["--job", "7"],
        &["--job-id=abc"],
        &["--job-id", ""],
        &["--period-ms", "900000"],
        &["--period-ms=900000"],
        &["--period", "5"],
        &["--period-ms=-1"],
        &["--network", "unmetered"],
        &["--network=cellular"],
        &["--net", "any"],
        &["--battery-not-low", "true"],
        &["--battery-not-low=false"],
        &["--battery", "maybe"],
        &["--storage-not-low", "true"],
        &["--storage-not-low=false"],
        &["--storage", "x"],
        &["--charging", "true"],
        &["--charging=false"],
        &["--charg", "x"],
        &["--persisted", "true"],
        &["--persisted=false"],
        &["--pers", "x"],
        &["--trigger-content-uri", "content://x/y"],
        &["--trigger-content-uri=content://x/y"],
        &["--trigger-content-u", "x"],
        &["--trigger-content-flag", "1"],
        &["--trigger-content-flag=2"],
        &["--trigger-content-fl", "2"],
        &[
            "-s",
            "/s.sh",
            "--job-id",
            "3",
            "--period-ms=900000",
            "--network",
            "any",
            "--battery-not-low",
            "true",
            "--storage-not-low",
            "false",
            "--charging",
            "true",
            "--persisted",
            "true",
            "--trigger-content-uri",
            "content://u",
            "--trigger-content-flag",
            "1",
        ],
        &["--persisted", "true", "--job-id", "1", "-s", "/x"],
        &["-s", "/a", "-s", "/b"],
        &["--job-id=1", "--job-id=2"],
        &["--job-id=1", "--job-id="],
        &["-p", "-s", "/x"],
        &["-s", "/x", "-p"],
        &["-ps", "/x"],
        &["-ps/x"],
        &["-pp"],
        &["--cancel", "--job-id", "5"],
        &["--pending", "--cancel-all", "--cancel"],
        &["--", "--job-id=1"],
        &["--job-id=1", "--"],
        &["-s", "/x", "--", "-p"],
    ] {
        parity(&h, Case::new("job-scheduler", args).replies(&["[]\n"]));
    }
}

#[test]
fn job_scheduler_app_answers() {
    let Some(h) = job_harness("jobanswers") else {
        return;
    };
    for reply in ["", "[]\n", "no newline", "{\"error\": 1}\n"] {
        parity(
            &h,
            Case::new("job-scheduler", &["--pending"]).replies(&[reply]),
        );
        parity(&h, Case::new("job-scheduler", &[]).replies(&[reply]));
    }
    parity(
        &h,
        Case::new("job-scheduler", &["--cancel-all"])
            .stdin(b"in\n")
            .replies(&["x"]),
    );
}

#[test]
fn job_scheduler_calls_are_what_the_app_expects() {
    let Some(h) = job_harness("jobcalls") else {
        return;
    };
    let (_, calls) = parity(&h, Case::new("job-scheduler", &["--pending"]));
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].method, "JobScheduler");
    assert_eq!(calls[0].args, ["--ez", "pending", "1"]);
    let (o, calls) = parity(&h, Case::new("job-scheduler", &["foo"]));
    assert!(calls.is_empty());
    assert_eq!(o.status, 1);
    assert_eq!(o.stdout_str(), "tpp job-scheduler: too many arguments\n");
    let (o, calls) = parity(&h, Case::new("job-scheduler", &["-x"]));
    assert!(calls.is_empty());
    assert_eq!(o.status, 1);
    assert_eq!(o.stdout_str(), "");
}
