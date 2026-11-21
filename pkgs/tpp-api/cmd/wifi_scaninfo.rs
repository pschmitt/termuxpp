//! `wifi-scaninfo`: `tpp wifi scan`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/wifi-scaninfo.txt");
const NAME: &str = "tpp wifi scan";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("WifiScanInfo"))
}
