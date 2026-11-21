//! The API commands: the crate `tpp-api` is part of the binary, so every
//! command of the table runs in this process and talks to the app itself.

use std::ffi::OsString;
use std::io::{self, Write};

use tpp_api::{Env, Io, Native};

/// Run `imp` on the standard streams of the process; its exit status.
pub fn run(imp: &str, args: &[OsString]) -> i32 {
    tpp_api::run_stdio(imp, args).unwrap_or_else(|| unknown(imp))
}

/// Run `imp` with its standard output written to `stdout`.
pub fn run_to(imp: &str, args: &[OsString], stdout: &mut dyn Write) -> i32 {
    let mut stderr = io::stderr();
    let mut io = Io {
        stdin: Some(Box::new(io::stdin())),
        stdout,
        stderr: &mut stderr,
        env: Env::process(),
        transport: &Native,
    };
    tpp_api::run(imp, args, &mut io).unwrap_or_else(|| unknown(imp))
}

fn unknown(imp: &str) -> i32 {
    err!("{imp}: not an API command of this binary");
    127
}
