//! `volume`: `tpp volume [stream volume]`.
//!
//! Deviations: the stream is passed to the app as one argument; the script's
//! unquoted `$1` would split a value with blanks in it into several words.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/volume.txt");

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
    let mut call = Call::new("Volume");
    if !args.is_empty() {
        if args.len() != 2 {
            io.outln("Invalid argument count");
            return super::show_usage(io, USAGE);
        }
        // [[ $2 =~ ^[0-9]+$ ]]
        if args[1].is_empty() || !args[1].bytes().all(|b| b.is_ascii_digit()) {
            io.outln("ERROR: Volume must be a number");
            return super::show_usage(io, USAGE);
        }
        call = call
            .arg("-a")
            .arg("set-volume")
            .arg("--es")
            .arg("stream")
            .args(words(&args[0]))
            .ei("volume", &args[1]);
    }
    io.run_call(call)
}
