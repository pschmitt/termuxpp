//! `clipboard-set`: `tpp clipboard set [text]`.
use crate::{Call, Io, Stdin};

pub const USAGE: &str = include_str!("../help/clipboard-set.txt");
const NAME: &str = "tpp clipboard set";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let rest = match super::only_help(args, io, NAME, USAGE) {
        Ok(r) => r,
        Err(status) => return status,
    };
    let call = Call::new("Clipboard").e("api_version", 2).ez("set", true);
    if rest.is_empty() {
        io.run_call(call)
    } else {
        // echo -n "$@" | termux-api ...
        io.run_call(call.stdin(Stdin::Bytes(rest.join(" ").into_bytes())))
    }
}
