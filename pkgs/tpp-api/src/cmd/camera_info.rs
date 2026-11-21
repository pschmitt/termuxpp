//! `camera-info`: `tpp camera info`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/camera-info.txt");
const NAME: &str = "tpp camera info";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(args, io, NAME, USAGE, Call::new("CameraInfo"))
}
