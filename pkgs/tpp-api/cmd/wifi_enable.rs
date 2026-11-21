//! `wifi-enable`: `tpp wifi enable` and `tpp wifi disable` (the dispatcher
//! passes `true` or `false` first).
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/wifi-enable.txt");

/// `show_usage STATUS`
fn usage(io: &mut Io, status: i32) -> i32 {
    io.out(USAGE);
    status
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    if matches!(args.first().map(String::as_str), Some("-h" | "--help")) {
        return usage(io, 0);
    }
    if args.len() != 1 || !matches!(args[0].as_str(), "true" | "false") {
        return usage(io, 1);
    }
    io.run_call(Call::new("WifiEnable").ez("enabled", &args[0]))
}
