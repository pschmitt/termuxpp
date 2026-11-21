//! `infrared-frequencies`: `tpp infrared frequencies`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/infrared-frequencies.txt");
const NAME: &str = "tpp infrared frequencies";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("InfraredFrequencies"))
}
