//! `notification-remove`: `tpp notification remove notification-id`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/notification-remove.txt");
const NAME: &str = "tpp notification remove";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let rest = match super::only_help(args, io, NAME, USAGE) {
        Ok(r) => r,
        Err(status) => return status,
    };
    if rest.len() != 1 {
        io.outln(&format!("{NAME}: no notification id specified"));
        return 1;
    }
    io.run_call(Call::new("NotificationRemove").es("id", &rest[0]))
}
