//! `telephony-cellinfo`: `tpp telephony cell-info`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/telephony-cellinfo.txt");
const NAME: &str = "tpp telephony cell-info";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("TelephonyCellInfo"))
}
