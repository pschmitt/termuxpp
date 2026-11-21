//! `telephony-deviceinfo`: `tpp telephony device-info`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/telephony-deviceinfo.txt");
const NAME: &str = "tpp telephony device-info";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("TelephonyDeviceInfo"))
}
