//! Command groups that are part of the tpp binary and take over the whole
//! command line behind their name, as opposed to the table commands whose output
//! the dispatcher renders.
//!
//! Today that is `tpp session`, the terminal session control of the app
//! (`tpp-session`). The same code answers to `tpp-session` as argv[0].

use std::ffi::OsString;
use std::io::Write;

use tpp_session::cli::{self, Environment};
use tpp_session::client::SocketExchange;

/// The groups: the word after `tpp` and the one-line description.
pub const GROUPS: &[(&str, &str)] = &[
    ("session", tpp_session::SUMMARY),
    ("settings", "Reload app properties and styling"),
    ("tmux", "Attach to remote tmux from the shell"),
    ("ui", "Open the TermuxPP interface"),
];

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

fn expand_action(name: &str, words: &mut [String]) -> bool {
    let actions = match name {
        "settings" => &["reload"][..],
        _ => &tpp_session::complete::ACTIONS,
    };
    let Some(word) = words.iter_mut().find(|word| word.as_str() != "--json") else {
        return true;
    };
    match crate::cli::resolve_prefix(word, actions.iter().copied()) {
        Ok(Some(action)) => *word = action.to_owned(),
        Ok(None) => {}
        Err(matches) => {
            eprintln!(
                "tpp: {name}: ambiguous command '{word}'. Matches: {}",
                matches.join(", ")
            );
            return false;
        }
    }
    true
}

/// Run a group with the arguments behind its name. `lead_json` is a
/// `--json` in front of the group name; the session command takes it in
/// front of its own command, like it does after it.
pub fn run(name: &str, args: &[OsString], lead_json: bool) -> i32 {
    debug_assert!(is_native(name));
    if name == "tmux" {
        return crate::tmux::run(args, lead_json);
    }
    if name == "ui" {
        return crate::ui::run(&strings(args), lead_json);
    }
    if name == "settings" {
        let mut words = strings(args);
        if !expand_action(name, &mut words) {
            return 2;
        }
        if words.is_empty()
            || words
                .iter()
                .any(|word| matches!(word.as_str(), "-h" | "--help" | "help"))
        {
            print!(
                "{}",
                crate::ctx::color_help(
                    "Usage: tpp settings reload [--json]\nReload app properties and styling.\n"
                )
            );
            return 0;
        }
        if words[0] != "reload" {
            eprintln!("tpp: settings: unknown command '{}'", words[0]);
            return 2;
        }
        let mut session_args = vec!["reload-settings".to_string()];
        if lead_json {
            session_args.push("--json".to_string());
        }
        session_args.extend_from_slice(&words[1..]);
        return run_session(&session_args);
    }
    let mut words = Vec::with_capacity(args.len() + 1);
    if lead_json {
        words.push("--json".to_string());
    }
    words.extend(strings(args));
    if words.first().is_none_or(|word| word != "__complete") && !expand_action(name, &mut words) {
        return 2;
    }
    run_session(&words)
}

fn run_session(args: &[String]) -> i32 {
    let stdout = std::io::stdout();
    let environment = Environment {
        color: crate::ctx::use_color(),
    };
    let exchange = SocketExchange::from_environment();
    let mut out = stdout.lock();
    let mut err = std::io::stderr().lock();
    if args
        .iter()
        .any(|arg| matches!(arg.as_str(), "-h" | "--help"))
    {
        let mut help = Vec::new();
        let status = cli::run(args, &exchange, &environment, &mut help, &mut err);
        let _ = out.write_all(crate::ctx::color_help(&String::from_utf8_lossy(&help)).as_bytes());
        return i32::from(status);
    }
    let status = cli::run(args, &exchange, &environment, &mut out, &mut err);
    let _ = out.flush();
    let _ = err.flush();
    i32::from(status)
}

/// The candidates for the last of `words`, the words behind the group name.
pub fn complete(name: &str, words: &[String]) -> Vec<String> {
    debug_assert!(is_native(name));
    if name == "tmux" {
        return crate::tmux::complete(words);
    }
    if name == "ui" {
        return crate::ui::complete(words);
    }
    if name == "settings" {
        return match words {
            [word] => ["reload", "--help"]
                .into_iter()
                .filter(|s| s.starts_with(word))
                .map(str::to_owned)
                .collect(),
            [action, word] if action == "reload" => ["--help", "--json"]
                .into_iter()
                .filter(|s| s.starts_with(word))
                .map(str::to_owned)
                .collect(),
            _ => Vec::new(),
        };
    }
    tpp_session::complete::complete(words, &SocketExchange::from_environment())
}
