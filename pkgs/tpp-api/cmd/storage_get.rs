//! `storage-get`: `tpp storage get output-file`.
//!
//! Like the script, a failing `realpath` only prints its message to standard
//! error: the call still goes out, with an empty file name.
//!
//! Deviations: the text of the `realpath` error message is rebuilt from the
//! I/O error (`realpath: PATH: No such file or directory`), which matches GNU
//! coreutils for the common errors only. A file name that starts with `-` is
//! resolved as a path, whereas the script hands it to `realpath` as an option.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/storage-get.txt");
const NAME: &str = "tpp storage get";

/// GNU `quotef`: names with shell-special characters are single-quoted.
fn quote_name(s: &str) -> String {
    let plain = !s.is_empty()
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || "_@%+=:,./-".contains(c));
    if plain {
        s.to_string()
    } else {
        format!("'{}'", s.replace('\'', "'\\''"))
    }
}

/// `$(realpath "$1")`: the path, or an empty string after a message on stderr.
fn realpath_or_empty(io: &mut Io, path: &str) -> String {
    match io.realpath(path) {
        Ok(p) => p,
        Err(e) => {
            let text = e.to_string();
            let text = match text.rfind(" (os error ") {
                Some(i) if text.ends_with(')') => text[..i].to_string(),
                _ => text,
            };
            // The default conversion already starts the text with the path.
            let text = match text.strip_prefix(path) {
                Some(rest) if rest.starts_with(": ") => {
                    format!("{}{}", quote_name(path), rest)
                }
                _ => text,
            };
            io.errln(&format!("realpath: {text}"));
            String::new()
        }
    }
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let rest = match super::only_help(args, io, NAME, USAGE) {
        Ok(r) => r,
        Err(status) => return status,
    };
    if rest.len() > 1 {
        io.outln(&format!("{NAME}: too many arguments"));
        return 1;
    }
    if rest.is_empty() {
        io.outln(&format!("{NAME}: no output file specified"));
        return 1;
    }
    let file = realpath_or_empty(io, &rest[0]);
    io.run_call(Call::new("StorageGet").es("file", file))
}
