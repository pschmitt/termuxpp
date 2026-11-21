//! `vibrate`: `tpp vibrate [-d duration] [-f]`.
//!
//! Deviations: an option value is passed to the app as one argument; the
//! script's unquoted `$PARAMS` would split a value with blanks in it into
//! several words.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/vibrate.txt");
const NAME: &str = "tpp vibrate";

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
    let mut g = Getopts::new(args, ":hfd:");
    let mut call = Call::new("Vibrate");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('d', v) => call = call.arg("--ei").arg("duration_ms").args(words(&v)),
            Opt::Flag('f') => call = call.ez("force", true),
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) | Opt::Flag(c) => {
                return super::illegal_option(io, NAME, c)
            }
        }
    }
    if let Some(status) = super::no_operands(io, NAME, g.rest()) {
        return status;
    }
    io.run_call(call)
}
