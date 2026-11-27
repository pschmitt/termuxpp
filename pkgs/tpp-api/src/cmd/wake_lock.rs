//! `wake-lock`, `wake-unlock`: `tpp wake-lock acquire|release`.
//!
//! The TermuxPP service holds the wake lock (and, with `wake-lock-wifi`, the
//! Wi-Fi lock); these ask it to acquire or release them, as the stock
//! `termux-wake-lock` and `termux-wake-unlock` scripts did through `am`.
//!
//! ```text
//! $ tpp wake-lock acquire    # am startservice -a ...service_wake_lock
//! $ tpp wake-lock release    # am startservice -a ...service_wake_unlock
//! ```
use crate::Io;

pub const LOCK_USAGE: &str = include_str!("../help/wake-lock.txt");
pub const UNLOCK_USAGE: &str = include_str!("../help/wake-unlock.txt");

const SERVICE: &str = "dev.pschmitt.termux/com.termux.app.TermuxService";

pub fn lock(args: &[String], io: &mut Io) -> i32 {
    run(
        args,
        io,
        LOCK_USAGE,
        "dev.pschmitt.termux.service_wake_lock",
    )
}

pub fn unlock(args: &[String], io: &mut Io) -> i32 {
    run(
        args,
        io,
        UNLOCK_USAGE,
        "dev.pschmitt.termux.service_wake_unlock",
    )
}

fn run(args: &[String], io: &mut Io, usage: &str, action: &str) -> i32 {
    match args.first().map(String::as_str) {
        None => {}
        Some("-h" | "--help") => return super::show_usage(io, usage),
        Some(_) => {
            super::show_usage(io, usage);
            return 1;
        }
    }
    io.run_program(
        "am",
        &[
            "startservice".to_string(),
            "-a".to_string(),
            action.to_string(),
            SERVICE.to_string(),
        ],
    )
}
