//! `battery-status`: `tpp battery`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/battery-status.txt");
const NAME: &str = "tpp battery";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("BatteryStatus"))
}
