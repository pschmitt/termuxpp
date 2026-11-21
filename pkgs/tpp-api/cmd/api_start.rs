//! `api-start`: `tpp api start`.
use crate::Io;

pub const USAGE: &str = include_str!("../help/api-start.txt");

pub fn run(args: &[String], io: &mut Io) -> i32 {
    if matches!(args.first().map(String::as_str), Some("-h" | "--help")) {
        return super::show_usage(io, USAGE);
    }
    io.run_program(
        "am",
        &[
            "startservice".to_string(),
            "-n".to_string(),
            "dev.pschmitt.termux/dev.pschmitt.termux.api.KeepAliveService".to_string(),
        ],
    )
}
