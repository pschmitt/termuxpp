//! `nfc`: `tpp nfc [-r [short|full]] [-w] [-t text]`.
//!
//! Every error ends in the usage and exit status 0, as the script does.
//!
//! Deviations:
//! the script word-splits the value of `-r` (`$OPT_R`); it is passed as one
//! argument instead (an empty value stays an empty argument).
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/nfc.txt");

pub fn run(args: &[String], io: &mut Io) -> i32 {
    if matches!(args.first().map(String::as_str), Some("-h" | "--help")) || args.is_empty() {
        return super::show_usage(io, USAGE);
    }
    let mut g = Getopts::new(args, ":r:t:w");
    let mut read: Option<String> = None;
    let mut write = false;
    let mut text: Option<String> = None;
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Arg('r', v) => read = Some(v),
            Opt::Flag('w') => write = true,
            Opt::Arg('t', v) => text = Some(v),
            Opt::Illegal(c) => {
                io.outln(&format!("Error: unknown parameters: ? {c};"));
                return super::show_usage(io, USAGE);
            }
            Opt::MissingArg(c) => {
                io.outln(&format!("Error: unknown parameters: : {c};"));
                return super::show_usage(io, USAGE);
            }
            Opt::Flag(_) | Opt::Arg(_, _) => {}
        }
    }
    if !g.rest().is_empty() {
        io.outln("Error: too many parameters!");
        return super::show_usage(io, USAGE);
    }
    if read.is_some() && write {
        io.outln("Error: Incompatible parameters! \"-r and \"-n");
        return super::show_usage(io, USAGE);
    }
    let mut call = Call::new("Nfc");
    if let Some(v) = read {
        call = call.es("mode", "read").es("param", v);
    }
    if write {
        call = call.es("mode", "write");
    }
    if let Some(v) = text {
        call = call.es("param", "text").es("value", v);
    }
    io.run_call(call)
}
