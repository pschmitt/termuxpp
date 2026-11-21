//! `clipboard-get`: `tpp clipboard get`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/clipboard-get.txt");
const NAME: &str = "tpp clipboard get";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("Clipboard"))
}
