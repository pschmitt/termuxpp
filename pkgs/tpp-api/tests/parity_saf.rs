//! Parity of the SAF commands, `storage get`, `download` and
//! `notification remove` with their shell scripts.

mod common;
use common::{parity, Case, Harness};

fn harness(tag: &str) -> Option<Harness> {
    let h = Harness::new(tag);
    if h.is_none() {
        eprintln!("skipped: no sh/bash");
    }
    h
}

const URI: &str = "content://com.android.externalstorage.documents/tree/primary%3ADocs";
const JSON: &str = "[\n  {\"name\": \"a\", \"uri\": \"content://x/a\"}\n]\n";
const ERR: &str = "{\n  \"API_ERROR\": \"no such document\"\n}\n";

/// The options every script has: -h, illegal options, `--`.
const COMMON: &[&[&str]] = &[
    &["-h"],
    &["-x"],
    &["-h", "-x"],
    &["-x", "-h"],
    &["-hx"],
    &["--help"],
    &["--"],
    &["-", "x"],
];

fn common_cases(h: &Harness, imp: &str) {
    for args in COMMON {
        parity(h, Case::new(imp, args).replies(&[JSON]));
    }
}

#[test]
fn saf_create() {
    let Some(h) = harness("safcreate") else {
        return;
    };
    let imp = "saf-create";
    common_cases(&h, imp);
    for args in [
        &[][..],
        &[URI],
        &[URI, "file.txt"],
        &[URI, "file with space.txt"],
        &[URI, ""],
        &["", "n"],
        &[URI, "a", "b"],
        &["-t", "text/plain", URI, "file.txt"],
        &["-ttext/plain", URI, "file.txt"],
        &["-t", "", URI, "file.txt"],
        &["-t", "a/b", "-t", "c/d", URI, "f"],
        &["-t"],
        &["-t", "video/mp4"],
        &["-t", "video/mp4", URI],
        &["-h", "-t", "x/y", URI, "f"],
        &["-t", "x/y", "-h"],
        &["-z", "-t", "x/y"],
        &["-t", "x/y", "-z"],
        &["--", "-t", "x"],
        &[URI, "-t", "x/y"],
        &["-t", "-h", URI, "f"],
        &["-ht", "x/y", URI, "f"],
    ] {
        parity(&h, Case::new(imp, args).replies(&["content://x/new\n"]));
    }
    for reply in ["", "\n", ERR, "content://x/new"] {
        parity(&h, Case::new(imp, &[URI, "f"]).replies(&[reply]));
    }
}

#[test]
fn saf_mkdir() {
    let Some(h) = harness("safmkdir") else { return };
    let imp = "saf-mkdir";
    common_cases(&h, imp);
    for args in [
        &[][..],
        &[URI],
        &[URI, "dir"],
        &[URI, "my dir"],
        &[URI, ""],
        &[URI, "a", "b"],
        &["--", URI, "dir"],
        &["-t", "x/y", URI, "dir"],
        &["-h", URI, "dir"],
    ] {
        parity(&h, Case::new(imp, args).replies(&["content://x/new\n"]));
    }
    for reply in ["", "\n", ERR] {
        parity(&h, Case::new(imp, &[URI, "d"]).replies(&[reply]));
    }
}

#[test]
fn saf_dirs() {
    let Some(h) = harness("safdirs") else { return };
    let imp = "saf-dirs";
    common_cases(&h, imp);
    for args in [&[][..], &["x"], &["x", "y"], &["--", "x"], &["-h", "x"]] {
        parity(&h, Case::new(imp, args).replies(&[JSON]));
    }
    for reply in [JSON, "", "\n", "[]\n", ERR] {
        parity(&h, Case::new(imp, &[]).replies(&[reply]));
    }
}

#[test]
fn saf_managedir() {
    let Some(h) = harness("safmanagedir") else {
        return;
    };
    let imp = "saf-managedir";
    common_cases(&h, imp);
    for args in [&[][..], &["x"], &["x", "y"], &["--", "x"], &["-h", "x"]] {
        parity(&h, Case::new(imp, args).replies(&["content://x/tree\n"]));
    }
    for reply in ["content://x/tree\n", "", "\n", ERR] {
        parity(&h, Case::new(imp, &[]).replies(&[reply]));
    }
}

fn one_operand(h: &Harness, imp: &str) {
    common_cases(h, imp);
    for args in [
        &[][..],
        &[URI],
        &["a b"],
        &[""],
        &["a", "b"],
        &["a", "b", "c"],
        &["--", URI],
        &["--", "-x"],
        &["-h", URI],
        &["-x", URI],
        &[URI, "-h"],
        &[URI, "-x"],
    ] {
        parity(h, Case::new(imp, args).replies(&[JSON]));
    }
    for reply in [JSON, "", "\n", ERR, "no newline"] {
        parity(h, Case::new(imp, &[URI]).replies(&[reply]));
    }
}

#[test]
fn saf_ls() {
    let Some(h) = harness("safls") else { return };
    one_operand(&h, "saf-ls");
}

#[test]
fn saf_stat() {
    let Some(h) = harness("safstat") else { return };
    one_operand(&h, "saf-stat");
}

#[test]
fn saf_read() {
    let Some(h) = harness("safread") else { return };
    let imp = "saf-read";
    one_operand(&h, imp);
    // stdin is forwarded to the call whether or not there is any
    parity(&h, Case::new(imp, &[URI]).stdin(b"ignored by the app\n"));
    parity(&h, Case::new(imp, &[URI]).stdin(b"data"));
    // binary data, no trailing newline, several chunks
    parity(
        &h,
        Case::new(imp, &[URI]).replies(&["bin\u{1}\u{2}\nno newline"]),
    );
    let big = "x".repeat(100_000);
    parity(&h, Case::new(imp, &[URI]).replies(&[&big]));
}

#[test]
fn saf_write() {
    let Some(h) = harness("safwrite") else { return };
    let imp = "saf-write";
    one_operand(&h, imp);
    parity(&h, Case::new(imp, &[URI]).stdin(b"new contents\nline 2\n"));
    parity(&h, Case::new(imp, &[URI]).stdin(b"no newline"));
    parity(&h, Case::new(imp, &[URI]).stdin(b"nul\0inside\0"));
    parity(&h, Case::new(imp, &[URI]).stdin(b""));
    parity(&h, Case::new(imp, &["-h"]).stdin(b"data"));
    parity(&h, Case::new(imp, &[]).stdin(b"data"));
    let big = vec![b'y'; 200_000];
    parity(&h, Case::new(imp, &[URI]).stdin(&big));
    parity(&h, Case::new(imp, &[URI]).stdin(b"x").replies(&[ERR]));
}

#[test]
fn storage_get() {
    let Some(h) = harness("storageget") else {
        return;
    };
    let imp = "storage-get";
    common_cases(&h, imp);
    let work = h.work_dir();
    std::fs::write(work.join("existing.bin"), b"x").unwrap();
    std::fs::create_dir_all(work.join("sub")).unwrap();
    std::fs::write(work.join("sub/in sub.txt"), b"y").unwrap();
    let abs_new = work.join("new.out");
    let abs_existing = work.join("existing.bin");
    let abs_dots = format!("{}/sub/../existing.bin", work.display());
    let abs_missing_parent = format!("{}/nodir/new.out", work.display());
    for args in [
        &[][..],
        &["a", "b"],
        &["existing.bin"],
        &["new.out"],
        &["./new.out"],
        &["sub/new.out"],
        &["sub/../new.out"],
        &["sub/in sub.txt"],
        &["sub"],
        &["."],
        &[".."],
        &["--", "new.out"],
        &["--", "./-dash.out"],
        &["-h", "new.out"],
        &["-x", "new.out"],
        &["new.out", "-h"],
        &[abs_new.to_str().unwrap()],
        &[abs_existing.to_str().unwrap()],
        &[abs_dots.as_str()],
        // realpath fails: its message on stderr, the call still goes out
        &["nodir/new.out"],
        &[abs_missing_parent.as_str()],
        &["nodir/a/b"],
        &[""],
    ] {
        parity(&h, Case::new(imp, args).replies(&["ok\n"]));
    }
    for reply in ["", "\n", ERR] {
        parity(&h, Case::new(imp, &["new.out"]).replies(&[reply]));
    }
}

#[test]
fn download() {
    let Some(h) = harness("download") else { return };
    let imp = "download";
    common_cases(&h, imp);
    let url = "https://example.org/file.zip?a=1&b=2";
    for args in [
        &[][..],
        &[url],
        &[url, "other"],
        &["-d", "desc", url],
        &["-ddesc", url],
        &["-d", "two words", url],
        &["-d", "", url],
        &["-t", "title", url],
        &["-t", "a  b", url],
        &["-p", "/sdcard/Download/x.zip", url],
        &["-p", "", url],
        &["-d", "D", "-t", "T", url],
        &["-t", "T", "-d", "D", url],
        &["-p", "/x", "-t", "T", "-d", "D", url],
        &["-d", "D", "-d", "E", url],
        &["-d", "D", "-t", "T", "-p", "/x", url, "extra"],
        &["-dD", "-tT", "-p/x", url],
        &["-d", "D"],
        &["-d"],
        &["-t"],
        &["-p"],
        &["-d", "D", "-t"],
        &["-h", url],
        &["-d", "D", "-h"],
        &["-z", url],
        &["-d", "D", "-z", url],
        &["--", url],
        &["--", "-d", "x"],
        &["-d", "-h", url],
        &[url, "-d", "x"],
        &["-d", "*", "-t", "~", url],
    ] {
        parity(&h, Case::new(imp, args).replies(&["Downloading\n"]));
    }
    for reply in ["", "\n", ERR] {
        parity(&h, Case::new(imp, &[url]).replies(&[reply]));
    }
}

#[test]
fn notification_remove() {
    let Some(h) = harness("notifremove") else {
        return;
    };
    let imp = "notification-remove";
    common_cases(&h, imp);
    for args in [
        &[][..],
        &["1"],
        &["my id"],
        &[""],
        &["1", "2"],
        &["--", "1"],
        &["--", "-1"],
        &["-1"],
        &["-h", "1"],
        &["1", "-h"],
        &["-x", "1"],
    ] {
        parity(&h, Case::new(imp, args).replies(&[""]));
    }
    for reply in ["", "\n", ERR] {
        parity(&h, Case::new(imp, &["1"]).replies(&[reply]));
    }
}
