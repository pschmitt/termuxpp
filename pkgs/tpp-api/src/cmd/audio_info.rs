//! `audio-info`: `tpp audio info`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/audio-info.txt");
const NAME: &str = "tpp audio info";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("AudioInfo"))
}
