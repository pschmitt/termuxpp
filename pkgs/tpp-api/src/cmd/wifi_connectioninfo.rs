//! `wifi-connectioninfo`: `tpp wifi connection`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/wifi-connectioninfo.txt");
const NAME: &str = "tpp wifi connection";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("WifiConnectionInfo"))
}
