//! `brightness`: `tpp brightness auto|LEVEL`.
//!
//! Deviations: the level is passed to the app as one argument; the script's
//! unquoted `$1` would split a value with blanks in it into several words.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/brightness.txt");

/// An unquoted `$VAR` in the script: split into words at blanks. Nothing or a
/// single word gives exactly what the shell passes; a value with several
/// words is passed as one argument (not split).
fn words(v: &str) -> Vec<String> {
    let parts: Vec<&str> = v
        .split([' ', '\t', '\n'])
        .filter(|w| !w.is_empty())
        .collect();
    match parts.len() {
        0 => Vec::new(),
        1 => vec![parts[0].to_string()],
        _ => vec![v.to_string()],
    }
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    if matches!(args.first().map(String::as_str), Some("-h" | "--help")) {
        return super::show_usage(io, USAGE);
    }
    if args.len() != 1 {
        return super::show_usage(io, USAGE);
    }
    let a = &args[0];
    // [[ $1 =~ ^([0-9]+)|auto$ ]]: starts with a digit, or ends in "auto"
    if !(a.starts_with(|c: char| c.is_ascii_digit()) || a.ends_with("auto")) {
        io.outln("ERROR: Arg must be a number between 0 - 255 or auto!");
        return super::show_usage(io, USAGE);
    }
    let call = if a == "auto" {
        Call::new("Brightness").ez("auto", true)
    } else {
        Call::new("Brightness")
            .arg("--ei")
            .arg("brightness")
            .args(words(a))
            .ez("auto", false)
    };
    io.run_call(call)
}
