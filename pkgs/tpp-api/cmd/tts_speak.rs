//! `tts-speak`: `tpp tts speak [-e engine] [-l language] [-n region]
//! [-v variant] [-p pitch] [-r rate] [-s stream] [text-to-speak]`.
//!
//! The text is the operands (`echo "$@"`, with the echo of bash: options
//! `-n`, `-e`, `-E`) or, without operands, the standard input.
//!
//! Deviations: the option values are passed to the app as one argument each;
//! the script's unquoted `$PARAMS` would split a value with blanks in it into
//! several words.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/tts-speak.txt");
const NAME: &str = "tpp tts speak";

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
    let mut g = Getopts::new(args, ":he:l:n:v:p:r:s:");
    let mut call = Call::new("TextToSpeech");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg(c, v) if "elnvprs".contains(c) => {
                let (kind, key) = match c {
                    'e' => ("--es", "engine"),
                    'l' => ("--es", "language"),
                    'n' => ("--es", "region"),
                    'v' => ("--es", "variant"),
                    'p' => ("--ef", "pitch"),
                    'r' => ("--ef", "rate"),
                    _ => ("--es", "stream"),
                };
                call = call.arg(kind).arg(key).args(words(&v));
            }
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) | Opt::Flag(c) => {
                return super::illegal_option(io, NAME, c)
            }
        }
    }
    let rest = g.rest();
    if !rest.is_empty() {
        call = call.stdin_bytes(super::sms_send::echo(rest));
    }
    io.run_call(call)
}
