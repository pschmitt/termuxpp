//! `telephony-call`: `tpp telephony call <number>`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/telephony-call.txt");
const NAME: &str = "tpp telephony call";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let rest = match super::only_help(args, io, NAME, USAGE) {
        Ok(r) => r,
        Err(status) => return status,
    };
    if rest.len() == 1 {
        io.run_call(Call::new("TelephonyCall").es("number", &rest[0]))
    } else {
        io.errln(&format!("{NAME}: No single number to call specified."));
        1
    }
}
