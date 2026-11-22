//! tpp: the unified entry point for the TermuxPP Android API commands.
//!
//! The commands (tpp wifi scan, tpp vibrate, ...) are listed in the table
//! built into the binary (`commands.rs`). Every one of them is implemented by
//! the crate `tpp-api` and runs in the binary (`api.rs`); there are no command
//! scripts. Executables in `$TPP_PLUGIN_DIR` add more commands.
//!
//! The binary also contains the session control (`tpp session`, also installed
//! as `tpp-session`), see `native.rs`.
//!
//! Environment:
//!   TPP_PLUGIN_DIR   colon-separated plugin directories, may be empty
//!                    (default: PREFIX/libexec/tpp/plugins)
//!   TPP_API_AM, TPP_API_PATH_CMD  see the crate tpp-api
//!   NO_COLOR, CLICOLOR_FORCE  color control for terminal output

/// Print `tpp: MESSAGE` to standard error.
macro_rules! err {
    ($($arg:tt)*) => {{
        use std::io::Write as _;
        let _ = writeln!(std::io::stderr(), "tpp: {}", format_args!($($arg)*));
    }};
}

mod api;
mod cli;
mod commands;
mod complete;
mod ctx;
mod native;
mod render;
mod storage;
mod table;
mod ui;

fn main() {
    let mut args = std::env::args_os();
    // One binary, several names: tpp-session is the session group of tpp
    let program = args.next().unwrap_or_default();
    let name = std::path::Path::new(&program)
        .file_name()
        .unwrap_or_default();
    let code = if name == native::SESSION_PROGRAM {
        cli::run_native("session", args.collect())
    } else if name == "tpp-setup-storage" {
        storage::run(args.collect())
    } else {
        cli::run(args.collect())
    };
    std::process::exit(code);
}
