//! Command groups that are part of the tpp binary and take over the whole
//! command line behind their name, as opposed to the table commands whose output
//! the dispatcher renders.
//!
//! Today that is `tpp session`, the terminal session control of the app
//! (`tpp-session`). The same code answers to `tpp-session` as argv[0].

use std::ffi::OsString;
use std::io::{IsTerminal, Write};

use tpp_session::cli::{self, Environment};
use tpp_session::client::SocketExchange;

/// The groups: the word after `tpp` and the one-line description.
pub const GROUPS: &[(&str, &str)] = &[("session", tpp_session::SUMMARY)];

/// The program name that runs the session group on its own.
pub const SESSION_PROGRAM: &str = "tpp-session";

pub fn is_native(name: &str) -> bool {
    GROUPS.iter().any(|(n, _)| *n == name)
}

fn strings(args: &[OsString]) -> Vec<String> {
    args.iter()
        .map(|a| a.to_string_lossy().into_owned())
        .collect()
}

/// Run a group with the arguments behind its name. `lead_json` is a
/// `--json` in front of the group name; the session command takes it in
/// front of its own command, like it does after it.
pub fn run(name: &str, args: &[OsString], lead_json: bool) -> i32 {
    debug_assert!(is_native(name));
    let mut words = Vec::with_capacity(args.len() + 1);
    if lead_json {
        words.push("--json".to_string());
    }
    words.extend(strings(args));
    run_session(&words)
}

fn run_session(args: &[String]) -> i32 {
    let stdout = std::io::stdout();
    let environment = Environment {
        color: stdout.is_terminal() && std::env::var_os("NO_COLOR").is_none(),
    };
    let exchange = SocketExchange::from_environment();
    let mut out = stdout.lock();
    let mut err = std::io::stderr().lock();
    let status = cli::run(args, &exchange, &environment, &mut out, &mut err);
    let _ = out.flush();
    let _ = err.flush();
    i32::from(status)
}

/// The candidates for the last of `words`, the words behind the group name.
pub fn complete(name: &str, words: &[String]) -> Vec<String> {
    debug_assert!(is_native(name));
    tpp_session::complete::complete(words, &SocketExchange::from_environment())
}
