//! `saf-dirs`: `tpp saf dirs`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/saf-dirs.txt");
const NAME: &str = "tpp saf dirs";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(
        args,
        io,
        NAME,
        USAGE,
        Call::new("SAF").es("safmethod", "getManagedDocumentTrees"),
    )
}
