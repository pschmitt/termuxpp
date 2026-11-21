//! `call-log`: `tpp call-log [-l limit] [-o offset]`.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/call-log.txt");
const NAME: &str = "tpp call-log";

/// `validate_is_postive_number`: on a bad value the error goes to standard
/// error, the usage to standard output, and the script exits with status 1.
fn validate(io: &mut Io, opt: char, value: &str) -> Option<i32> {
    if !value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    io.errln(&format!(
        "ERROR: The -{opt} value '{value}' is not a positive number."
    ));
    io.out(USAGE);
    Some(1)
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":hl:o:");
    let mut limit = "10".to_string();
    let mut offset = "0".to_string();
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('l', v) => limit = v,
            Opt::Arg('o', v) => offset = v,
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return super::illegal_option(io, NAME, c)
            }
            Opt::Flag(_) => {}
        }
    }
    if let Some(status) = validate(io, 'l', &limit) {
        return status;
    }
    if let Some(status) = validate(io, 'o', &offset) {
        return status;
    }
    if let Some(status) = super::no_operands(io, NAME, g.rest()) {
        return status;
    }
    io.run_call(Call::new("CallLog").ei("offset", offset).ei("limit", limit))
}
