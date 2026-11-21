//! `saf-mkdir`: `tpp saf mkdir parent-uri name`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/saf-mkdir.txt");
const NAME: &str = "tpp saf mkdir";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let rest = match super::only_help(args, io, NAME, USAGE) {
        Ok(r) => r,
        Err(status) => return status,
    };
    if rest.len() != 2 {
        io.outln(&format!("{NAME}: Invalid argument count"));
        return 1;
    }
    io.run_call(
        Call::new("SAF")
            .es("safmethod", "createDocument")
            .es("treeuri", &rest[0])
            .es("filename", &rest[1])
            .es("mimetype", "vnd.android.document/directory"),
    )
}
