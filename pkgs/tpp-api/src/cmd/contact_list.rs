//! `contact-list`: `tpp contacts list`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/contact-list.txt");
const NAME: &str = "tpp contacts list";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("ContactList"))
}
