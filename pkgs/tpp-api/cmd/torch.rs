//! `torch`: `tpp torch [on | off]`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/torch.txt");

/// `show_usage` with the default status 1.
fn usage_failure(io: &mut Io) -> i32 {
    io.out(USAGE);
    1
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    if matches!(args.first().map(String::as_str), Some("-h" | "--help")) {
        return super::show_usage(io, USAGE);
    }
    if args.len() != 1 {
        io.outln("Illegal param count");
        return usage_failure(io);
    }
    let enabled = match args[0].as_str() {
        "on" => true,
        "off" => false,
        other => {
            io.outln(&format!("Illegal parameter: {other}"));
            return usage_failure(io);
        }
    };
    io.run_call(Call::new("Torch").ez("enabled", enabled))
}
