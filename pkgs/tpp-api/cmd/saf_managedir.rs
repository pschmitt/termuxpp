//! `saf-managedir`: `tpp saf managedir`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/saf-managedir.txt");
const NAME: &str = "tpp saf managedir";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(
        args,
        io,
        NAME,
        USAGE,
        Call::new("SAF").es("safmethod", "manageDocumentTree"),
    )
}
