//! `saf-ls`: `tpp saf ls` with one operand.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/saf-ls.txt");
const NAME: &str = "tpp saf ls";

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
            .es("safmethod", "listDirectory")
            .es("treeuri", &rest[0]),
    )
}
