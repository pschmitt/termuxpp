//! Parity of `notification` and `dialog` with their shell scripts.

mod common;
use common::{parity, Case, Harness};

fn harness(tag: &str, need_getopt: bool) -> Option<Harness> {
    if need_getopt {
        let found = std::env::var_os("PATH")
            .map(|p| std::env::split_paths(&p).any(|d| d.join("getopt").is_file()))
            .unwrap_or(false);
        if !found {
            eprintln!("skipped: no getopt");
            return None;
        }
    }
    let h = Harness::new(tag);
    if h.is_none() {
        eprintln!("skipped: no sh/bash");
    }
    h
}

// ---------------------------------------------------------------- dialog

#[test]
fn dialog_basics() {
    let Some(h) = harness("dlgbasic", false) else {
        return;
    };
    let reply = "{\n  \"code\": -1,\n  \"text\": \"hello\"\n}\n";
    for args in [
        &[][..],
        &["-h"],
        &["-l"],
        &["-h", "-l"],
        &["-l", "-h"],
        &["-x"],
        &["-x", "-h"],
        &["-h", "-x"],
        &["-?"],
        &["-:"],
        &["-1"],
        &["text", "-h"],
        &["confirm", "-l"],
        &["confirm", "-x"],
        &["--"],
        &["-"],
        &["-X"],
        &["--help"],
        &["--", "text"],
        &["text", "--", "x"],
        &["text", "-t", "Title", "--", "-t", "x"],
        &["text", "extra"],
        &["confirm", "extra", "more"],
        &["extra", "text"],
    ] {
        parity(&h, Case::new("dialog", args).replies(&[reply]));
    }
}

#[test]
fn dialog_illegal_first_argument() {
    let Some(h) = harness("dlgillegal", false) else {
        return;
    };
    for args in [
        &["foo"][..],
        &[""],
        &["Foo bar"],
        &["foo", "-h"],
        &["a\\nb"],
        &["a\\tb\\\\c"],
        &["cut\\cshort"],
        &["x\\x41y\\0102z\\101\\q\\"],
        &["\\e[1m\\a\\b\\f\\r\\v"],
        &["\\x\\xZ\\x4Z\\08"],
        // looks like options (a dash and a lowercase letter anywhere)
        &["foo-bar"],
        &["foo-bar", "-t", "x"],
        &["-t"],
        &["-t", "Title"],
        &["-tTitle"],
        &["-t", "Title", "extra"],
        &["-i", "hint", "-p"],
        &["-mn"],
        &["a-"],
        &["-A", "-b"],
    ] {
        parity(&h, Case::new("dialog", args));
    }
}

const WIDGETS: &[&str] = &[
    "confirm", "checkbox", "counter", "date", "radio", "sheet", "spinner", "speech", "text", "time",
];

#[test]
fn dialog_every_widget() {
    let Some(h) = harness("dlgwidgets", false) else {
        return;
    };
    let reply = "{\"code\": -1}\n";
    for w in WIDGETS {
        // alone, with a title, with every option on its own and combined
        for extra in [
            &[][..],
            &["-t", "A title"],
            &["-tTitle"],
            &["-i", "a hint"],
            &["-i", ""],
            &["-m"],
            &["-p"],
            &["-n"],
            &["-r", "1,10,5"],
            &["-r", "1, 2, 3"],
            &["-v", "a,b,c"],
            &["-v", ""],
            &["-v", "with space,b"],
            &["-d", "dd-MM-yyyy k:m:s"],
            &["-m", "-n"],
            &["-mn"],
            &["-mp"],
            &["-pn"],
            &["-i", "h", "-p", "-t", "T"],
            &["-i", "h", "-m", "-t", "T", "-d", "fmt"],
            &["-i", "h", "-n", "-p", "-t", "T"],
            &["-v", "x,y", "-t", "T"],
            &["-v", "x,y", "-r", "1,2,3"],
            &["-v", "x,y", "-i", "h"],
            &["-v", "x,y", "-m"],
            &["-v", "x,y", "-p"],
            &["-v", "x,y", "-n"],
            &["-v", "x,y", "-d", "fmt"],
            &["-r", "0,100,50", "-t", "T"],
            &["-r", "0,100,50", "-i", "h"],
            &["-r", "0,100,50", "-d", "f"],
            &["-t", "one", "-t", "two"],
            &["-v", "a", "-v", "b"],
            &["-t"],
            &["-i"],
            &["-v"],
            &["-r"],
            &["-d"],
            &["-m", "-t"],
            &["-v", "a,b", "-t"],
            &["-x"],
            &["-m", "-x"],
            &["-t", "T", "stray"],
            &["-t", "T", "--", "stray"],
            &["-h"],
            &["-l"],
        ] {
            let mut args = vec![*w];
            args.extend_from_slice(extra);
            parity(&h, Case::new("dialog", &args).replies(&[reply]));
        }
    }
}

#[test]
fn dialog_default_widget_options() {
    let Some(h) = harness("dlgdefault", false) else {
        return;
    };
    for args in [
        &["-t", "Title"][..],
        &["-i", "hint"],
        &["-i", "hint", "-t", "T", "-p"],
        &["-m"],
        &["-n"],
        &["-m", "-n"],
        &["-p", "-t", "Pass"],
        &["-d", "x"],
        &["-r", "1,2,3"],
        &["-v", "a,b"],
        &["-t", "T", "-r"],
    ] {
        parity(&h, Case::new("dialog", args));
    }
}

#[test]
fn dialog_stdin_and_replies() {
    let Some(h) = harness("dlgstdin", false) else {
        return;
    };
    parity(&h, Case::new("dialog", &["confirm"]).stdin(b"some input\n"));
    parity(&h, Case::new("dialog", &["-t", "T"]).stdin(b"abc"));
    parity(&h, Case::new("dialog", &["text"]).replies(&[""]));
    parity(&h, Case::new("dialog", &["text"]).replies(&["no newline"]));
    parity(
        &h,
        Case::new("dialog", &["radio", "-v", "a,b"]).replies(&["{\"index\": 1}\n\n\n"]),
    );
}

// ----------------------------------------------------------- notification

fn n(h: &Harness, args: &[&str]) {
    parity(h, Case::new("notification", args));
}

#[test]
fn notification_basics() {
    let Some(h) = harness("notifbasic", true) else {
        return;
    };
    n(&h, &[]);
    n(&h, &["-h"]);
    n(&h, &["--help"]);
    n(&h, &["--help-actions"]);
    n(&h, &["-h", "--help-actions"]);
    n(&h, &["--help-actions", "-h"]);
    n(&h, &["--help-actions", "--bogus"]);
    n(&h, &["-h", "extra"]);
    n(&h, &["extra", "-h"]);
    n(&h, &["extra"]);
    n(&h, &["-t", "T", "extra", "more"]);
    n(&h, &["--", "-h"]);
    n(&h, &["-t", "T", "--", "-h"]);
    n(&h, &["-"]);
    n(&h, &[""]);
}

#[test]
fn notification_every_option() {
    let Some(h) = harness("notifopts", true) else {
        return;
    };
    let with_value: &[(&str, &str)] = &[
        ("--action", "tpp toast hi"),
        ("--button1", "One"),
        ("--button1-action", "act 1"),
        ("--button2", "Two"),
        ("--button2-action", "act 2"),
        ("--button3", "Three"),
        ("--button3-action", "act 3"),
        ("-c", "the content"),
        ("--content", "the content"),
        ("--channel", "chan"),
        ("--group", "grp"),
        ("-i", "42"),
        ("--id", "42"),
        ("--icon", "event_note"),
        ("--image-path", "/sdcard/x.png"),
        ("--led-color", "ff0000"),
        ("--led-off", "100"),
        ("--led-on", "200"),
        ("--media-next", "next"),
        ("--media-pause", "pause"),
        ("--media-play", "play"),
        ("--media-previous", "prev"),
        ("--on-delete", "cleared"),
        ("--priority", "high"),
        ("-t", "A title"),
        ("--title", "A title"),
        ("--type", "media"),
        ("--vibrate", "500,1000,200"),
    ];
    for (opt, value) in with_value {
        parity(&h, Case::new("notification", &[opt, value]));
        // value glued to the option
        if opt.starts_with("--") {
            let glued = format!("{opt}={value}");
            parity(&h, Case::new("notification", &[&glued]));
            // the option without its value
            parity(&h, Case::new("notification", &[opt]));
            parity(&h, Case::new("notification", &[opt, "-t"]));
        } else {
            let glued = format!("{opt}{value}");
            parity(&h, Case::new("notification", &[&glued]));
            parity(&h, Case::new("notification", &[opt]));
        }
        // an empty value is not passed on
        parity(&h, Case::new("notification", &[opt, ""]));
        // stdin as content
        parity(
            &h,
            Case::new("notification", &[opt, value]).stdin(b"stdin text\n"),
        );
    }
    for flag in ["--alert-once", "--sound"] {
        n(&h, &[flag]);
        n(&h, &[flag, "-t", "x"]);
        let bad = format!("{flag}=yes");
        n(&h, &[&bad]);
        n(&h, &[flag, "extra"]);
    }
    // --ongoing needs an id
    n(&h, &["--ongoing"]);
    n(&h, &["--ongoing", "-i", ""]);
    n(&h, &["--ongoing", "-i", "7"]);
    n(&h, &["--ongoing", "--id", "7", "-t", "x"]);
    n(&h, &["-i", "7", "--ongoing"]);
    n(&h, &["--ongoing=1", "-i", "7"]);
}

#[test]
fn notification_combined() {
    let Some(h) = harness("notifall", true) else {
        return;
    };
    n(
        &h,
        &[
            "--vibrate",
            "1,2",
            "--type",
            "default",
            "-t",
            "Title",
            "--sound",
            "--priority",
            "max",
            "--ongoing",
            "--on-delete",
            "del",
            "--media-previous",
            "a",
            "--media-play",
            "b",
            "--media-pause",
            "c",
            "--media-next",
            "d",
            "--led-on",
            "1",
            "--led-off",
            "2",
            "--led-color",
            "00ff00",
            "--image-path",
            "/p.png",
            "--icon",
            "star",
            "-i",
            "9",
            "--group",
            "g",
            "--channel",
            "ch",
            "-c",
            "Body text",
            "--button3-action",
            "a3",
            "--button3",
            "b3",
            "--button2-action",
            "a2",
            "--button2",
            "b2",
            "--button1-action",
            "a1",
            "--button1",
            "b1",
            "--alert-once",
            "--action",
            "act",
        ],
    );
    n(&h, &["-t", "T", "-c", "C", "-i", "1"]);
    n(&h, &["-tT", "-cC", "-i1"]);
    n(&h, &["-t", "first", "-t", "second", "--title", "third"]);
    n(&h, &["-hc", "x"]);
    n(&h, &["-ct", "x"]);
    n(&h, &["-t", "x", "-h", "-t", "y"]);
    n(
        &h,
        &["--button1", "B", "--button1-action", "A", "--button2", "B2"],
    );
    n(&h, &["--button3-action", "A3"]);
    n(&h, &["--led-on", "100"]);
    n(&h, &["--led-off", "100"]);
    n(&h, &["--vibrate", "0"]);
    n(&h, &["--action", "a b c", "--on-delete", "x; y"]);
    n(&h, &["-t", "with 'quotes' and \"dq\" and $VAR and `bt`"]);
    n(&h, &["-t", "-h"]);
    n(&h, &["--title", "--", "-c", "x"]);
    n(&h, &["-c", "--help"]);
    n(&h, &["-t", "multi\nline\n\n"]);
    n(&h, &["-t", " leading and trailing "]);
    n(&h, &["extra", "-t", "T"]);
    n(&h, &["-t", "T", "extra"]);
}

#[test]
fn notification_getopt_abbreviations() {
    let Some(h) = harness("notifabbr", true) else {
        return;
    };
    for args in [
        &["--titl", "T"][..],
        &["--ti", "T"],
        &["--ty", "media"],
        &["--ti=T"],
        &["--ima", "/p"],
        &["--image", "/p"],
        &["--vib", "1,2"],
        &["--v", "1,2"],
        &["--so"],
        &["--s"],
        &["--pr", "min"],
        &["--p", "min"],
        &["--gr", "g"],
        &["--ongo", "--id", "1"],
        &["--ongoing", "--id", "1"],
        &["--on-d", "x"],
        &["--on", "x"],
        &["--o", "x"],
        &["--ac", "x"],
        &["--act", "x"],
        &["--a"],
        &["--al"],
        &["--alert"],
        &["--button1", "x"],
        &["--button1-a", "x"],
        &["--button1-action", "x"],
        &["--button2-a", "x"],
        &["--button", "x"],
        &["--but", "x"],
        &["--b", "x"],
        &["--content", "x"],
        &["--cont", "x"],
        &["--co", "x"],
        &["--ch", "x"],
        &["--c", "x"],
        &["--cha", "x"],
        &["--help"],
        &["--hel"],
        &["--he"],
        &["--h"],
        &["--help-"],
        &["--help-a"],
        &["--help-actions"],
        &["--i", "x"],
        &["--id", "x"],
        &["--ic", "x"],
        &["--icon", "x"],
        &["--im", "x"],
        &["--led", "x"],
        &["--led-c", "x"],
        &["--led-o", "1"],
        &["--led-on", "1"],
        &["--led-of", "1"],
        &["--m", "x"],
        &["--med", "x"],
        &["--media-p", "x"],
        &["--media-pa", "x"],
        &["--media-pl", "x"],
        &["--media-n", "x"],
        &["--media-pr", "x"],
        &["--but=x"],
        &["--o=x"],
        &["--=x"],
        &["--"],
        &["--="],
        &["---"],
        &["--title="],
        &["--title=", "-c", "x"],
        &["--ti="],
    ] {
        parity(&h, Case::new("notification", args));
    }
}

#[test]
fn notification_getopt_errors() {
    let Some(h) = harness("notiferr", true) else {
        return;
    };
    for args in [
        &["-x"][..],
        &["-xyz"],
        &["-x", "-h"],
        &["-h", "-x"],
        &["-hx"],
        &["-xh"],
        &["-c"],
        &["-t"],
        &["-i"],
        &["-hc"],
        &["-ht"],
        &["-t", "T", "-c"],
        &["--bogus"],
        &["--bogus=1"],
        &["--bogus", "--other"],
        &["--bogus", "-x", "-c"],
        &["--title"],
        &["--title", "T", "--content"],
        &["--help=1"],
        &["--help-actions=1"],
        &["--sound=1", "--ongoing=2"],
        &["-:"],
        &["-?"],
        &["-1"],
        &["-t", "T", "--", "--bogus", "-x"],
        &["--", "--bogus"],
        &["-t", "--bogus"],
        &["--title", "-x"],
        &["--title", "--title", "-c", "-h"],
        &["-"],
        &["- "],
        &["-t", "-"],
        &["--ti", "--co"],
        &["---x"],
    ] {
        parity(&h, Case::new("notification", args));
    }
}

#[test]
fn notification_content_and_stdin() {
    let Some(h) = harness("notifstdin", true) else {
        return;
    };
    let nc = |args: &[&str], stdin: &[u8]| {
        parity(&h, Case::new("notification", args).stdin(stdin));
    };
    nc(&[], b"");
    nc(&[], b"from stdin");
    nc(&[], b"from stdin\n");
    nc(&[], b"from stdin\n\n\n");
    nc(&[], b"two\nlines\n");
    nc(&[], b"before\0after");
    nc(&[], b"\0after");
    nc(&[], b"  spaces  \n");
    nc(&[], b"tab\tsep\n");
    nc(&[], b"back\\slash\n");
    nc(&[], b"\n");
    nc(&[], b"\n\nx");
    nc(&[], b"-n");
    nc(&[], b"-e");
    nc(&[], b"-E");
    nc(&[], b"-neE");
    nc(&[], b"-en\n");
    nc(&[], b"-n\n");
    nc(&[], b"-n\n\n");
    nc(&[], b"-e\n\n");
    nc(&[], b"-x");
    nc(&[], b"-nx");
    nc(&[], b"-");
    nc(&[], b"--");
    nc(&[], b"\xff\xfe binary \x80\n");
    nc(&["-c", "arg"], b"ignored stdin");
    nc(&["-c", ""], b"ignored stdin");
    nc(&["-c", "arg\n\n"], b"");
    nc(&["-c", "-n"], b"");
    nc(&["-c", "-e"], b"");
    nc(&["-c", "-E"], b"");
    nc(&["-c", "-ne"], b"");
    nc(&["-c", "-n\n"], b"");
    nc(&["-c", "-n\n\n"], b"");
    nc(&["-c", "-e\n\n"], b"");
    nc(&["-c", "-x"], b"");
    nc(&["-c", "a\\nb"], b"");
    nc(&["--content", "x", "--content", "y"], b"stdin");
    nc(&["-c", "x", "-t", "T"], b"stdin");
    nc(&["-t", "T"], b"stdin body\n");
    nc(&["--ongoing", "-i", "1"], b"body");
}

#[test]
fn notification_replies() {
    let Some(h) = harness("notifreply", true) else {
        return;
    };
    for reply in ["", "ok\n", "no newline", "{\"a\": 1}\n\n\n"] {
        parity(
            &h,
            Case::new("notification", &["-t", "T", "-c", "C"]).replies(&[reply]),
        );
    }
}
