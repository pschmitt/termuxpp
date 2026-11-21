//! `saf-read`: `tpp saf read` with one operand.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/saf-read.txt");
const NAME: &str = "tpp saf read";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let rest = match super::only_help(args, io, NAME, USAGE) {
        Ok(r) => r,
        Err(status) => return status,
    };
    if rest.len() != 1 {
        io.outln(&format!("{NAME}: Invalid argument count"));
        return 1;
    }
    io.run_call(
        Call::new("SAF")
            .es("safmethod", "readDocument")
            .es("uri", &rest[0]),
    )
}
