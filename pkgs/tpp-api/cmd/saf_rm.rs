//! `saf-rm`: `tpp saf rm uri`. The exit status is what the app answers.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/saf-rm.txt");
const NAME: &str = "tpp saf rm";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let rest = match super::only_help(args, io, NAME, USAGE) {
        Ok(r) => r,
        Err(status) => return status,
    };
    if rest.len() != 1 {
        io.outln(&format!("{NAME}: Invalid argument count"));
        return 1;
    }
    let call = Call::new("SAF")
        .es("safmethod", "removeDocument")
        .es("uri", &rest[0]);
    // exit "$(termux-api SAF ...)"
    match io.call_text(call) {
        Ok(answer) => match answer.trim().parse::<i64>() {
            Ok(n) if n >= 0 => (n & 0xff) as i32,
            _ => {
                io.errln(&format!("{NAME}: exit: Illegal number: {answer}"));
                2
            }
        },
        Err(e) => {
            io.errln(&format!("tpp: {e}"));
            1
        }
    }
}
