//! `infrared-transmit`: `tpp infrared transmit -f frequency pattern`.
//!
//! Deviations: the frequency and the pattern are passed to the app as one
//! argument each; the script's unquoted `$FREQUENCY` and `$1` would split a
//! value with blanks in it into several words.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/infrared-transmit.txt");
const NAME: &str = "tpp infrared transmit";

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
    let mut g = Getopts::new(args, ":hf:");
    // FREQUENCY="--ei frequency $OPTARG": set (even for an empty value) by -f
    let mut frequency: Option<String> = None;
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('f', v) => frequency = Some(v),
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) | Opt::Flag(c) => {
                return super::illegal_option(io, NAME, c)
            }
        }
    }
    let Some(frequency) = frequency else {
        io.outln(&format!("{NAME}: No frequency specified"));
        return 1;
    };
    let rest = g.rest();
    if rest.is_empty() {
        io.outln(&format!("{NAME}: too few arguments"));
        return 1;
    }
    if rest.len() > 1 {
        io.outln(&format!("{NAME}: too many arguments"));
        return 1;
    }
    let call = Call::new("InfraredTransmit")
        .arg("--ei")
        .arg("frequency")
        .args(words(&frequency))
        .arg("--eia")
        .arg("pattern")
        .args(words(&rest[0]));
    io.run_call(call)
}
