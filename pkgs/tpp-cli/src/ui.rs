//! Open surfaces in the running TermuxPP activity through its app-local socket.

use std::io::{IsTerminal, Read};
use std::time::Duration;

use tpp_session::client::{Exchange, ExchangeError, SocketExchange};
use tpp_session::json::{self, Value};

pub const ACTIONS: &[&str] = &[
    "clear",
    "more",
    "palette",
    "picker",
    "session-switcher",
    "settings",
    "sidebar",
    "tmux-switcher",
];

/// What to do with a surface; `show` is the default.
const OPERATIONS: &[&str] = &["show", "hide", "toggle"];

const HELP: &str = "Usage: tpp ui COMMAND [show|hide|toggle] [--json]\nShow, hide or toggle part of the TermuxPP interface in the visible terminal.\n\nCommands:\n  clear                Close everything: sidebars, palette, pickers, menu, Settings\n  more                 The terminal More menu\n  palette              The command palette [--search QUERY]\n  picker               Pick a line from stdin, like fzf: ls | tpp ui picker\n  session-switcher     The session picker\n  settings             Settings [--search QUERY]\n  sidebar              The session sidebar [--right]\n  tmux-switcher        The tmux window picker\n\nWithout show, hide or toggle, the command shows the surface.\n";

/// The options that ACTION takes besides show, hide and toggle.
fn usage(action: &str) -> String {
    match action {
        "clear" => "Usage: tpp ui clear [--json]\n".to_owned(),
        "picker" => "Usage: COMMAND | tpp ui picker [--title TEXT] [--query QUERY]\nShow the lines of stdin in the visible terminal and print the one you pick.\nExit status 130 when the picker is closed without a choice.\n".to_owned(),
        "settings" | "palette" => {
            format!("Usage: tpp ui {action} [show|hide|toggle] [--search QUERY] [--json]\n")
        }
        "sidebar" => "Usage: tpp ui sidebar [show|hide|toggle] [--right] [--json]\n".to_owned(),
        _ => format!("Usage: tpp ui {action} [show|hide|toggle] [--json]\n"),
    }
}

/// The request for the app. A plain `show` keeps the original form
/// (`["ui", ACTION]`, `["ui", "settings", QUERY]`), which older apps know;
/// everything else adds an options object.
fn request(action: &str, operation: &str, query: Option<&str>, right: bool) -> Value {
    let mut request = vec![Value::string("ui"), Value::string(action)];
    if operation == "show" && !right && (query.is_none() || action == "settings") {
        if let Some(query) = query {
            request.push(Value::string(query));
        }
        return Value::Array(request);
    }
    let mut options = vec![("op".to_owned(), Value::string(operation))];
    if let Some(query) = query {
        options.push(("query".to_owned(), Value::string(query)));
    }
    if right {
        options.push(("side".to_owned(), Value::string("right")));
    }
    request.push(Value::Object(options));
    Value::Array(request)
}

pub fn run(words: &[String], mut json_output: bool) -> i32 {
    let mut args = words.iter().map(String::as_str).peekable();
    while args.peek() == Some(&"--json") {
        args.next();
        json_output = true;
    }
    let action = match args.next() {
        None | Some("help" | "-h" | "--help") => {
            print!("{}", crate::ctx::color_help(HELP));
            return 0;
        }
        Some(action) => match crate::cli::resolve_prefix(action, ACTIONS.iter().copied()) {
            Ok(Some(action)) => action,
            Ok(None) => {
                err!("ui: unknown command '{action}'");
                return 2;
            }
            Err(matches) => {
                err!(
                    "ui: ambiguous command '{action}'. Matches: {}",
                    matches.join(", ")
                );
                return 2;
            }
        },
    };

    if action == "picker" {
        return picker(args.collect());
    }

    let mut query = None;
    let mut operation = None;
    let mut right = false;
    while let Some(arg) = args.next() {
        match arg {
            "--json" => json_output = true,
            "-h" | "--help" => {
                print!("{}", crate::ctx::color_help(&usage(action)));
                return 0;
            }
            "--search" if matches!(action, "settings" | "palette") && query.is_none() => {
                let Some(value) = args.next_if(|value| !value.starts_with("--")) else {
                    err!("ui {action}: --search requires a query");
                    return 2;
                };
                query = Some(value);
            }
            "--right" if action == "sidebar" && !right => right = true,
            word if action != "clear" && operation.is_none() && OPERATIONS.contains(&word) => {
                operation = Some(word);
            }
            _ => {
                err!("ui {action}: unexpected argument '{arg}'");
                return 2;
            }
        }
    }
    let operation = operation.unwrap_or("show");
    if query.is_some() && operation == "hide" {
        err!("ui {action}: --search only goes with show or toggle");
        return 2;
    }

    let exchange = SocketExchange::from_environment();
    let request = request(action, operation, query, right);
    if let Err(message) = tpp_session::cli::request_app(&exchange, &request) {
        err!("ui {action}: {message}");
        return 1;
    }

    if json_output {
        let mut response = vec![
            ("ok".to_owned(), Value::Bool(true)),
            ("action".to_owned(), Value::string(action)),
        ];
        if action != "clear" {
            response.push(("operation".to_owned(), Value::string(operation)));
        }
        let text = Value::Object(response).to_pretty();
        if crate::ctx::use_color() {
            println!("{}", json::colorize(&text));
        } else {
            println!("{text}");
        }
    }
    0
}

/// Most lines the picker takes (the app's limit).
const PICKER_LINES: usize = 20_000;
/// How long the picker may stay open (the app gives up after 30 minutes).
const PICKER_TIMEOUT: Duration = Duration::from_secs(31 * 60);

/// `tpp ui picker`: the lines of stdin in a picker in the app; prints the chosen one.
fn picker(words: Vec<&str>) -> i32 {
    let mut title = None;
    let mut query = None;
    let mut args = words.into_iter();
    while let Some(arg) = args.next() {
        match arg {
            "-h" | "--help" => {
                print!("{}", crate::ctx::color_help(&usage("picker")));
                return 0;
            }
            "--title" | "--query" => {
                let Some(value) = args.next() else {
                    err!("ui picker: {arg} requires a value");
                    return 2;
                };
                if arg == "--title" {
                    title = Some(value);
                } else {
                    query = Some(value);
                }
            }
            _ => {
                err!("ui picker: unexpected argument '{arg}'");
                return 2;
            }
        }
    }
    let mut stdin = std::io::stdin();
    if stdin.is_terminal() {
        err!("ui picker: pipe the lines in, like: ls | tpp ui picker");
        return 2;
    }
    let mut input = Vec::new();
    if let Err(error) = stdin.read_to_end(&mut input) {
        err!("ui picker: cannot read stdin: {error}");
        return 1;
    }
    let lines: Vec<Value> = String::from_utf8_lossy(&input)
        .lines()
        .map(|line| line.trim_end_matches('\r'))
        .filter(|line| !line.trim().is_empty())
        .map(Value::string)
        .collect();
    if lines.is_empty() {
        err!("ui picker: no lines to pick from");
        return 1;
    }
    if lines.len() > PICKER_LINES {
        err!(
            "ui picker: too many lines ({}, at most {PICKER_LINES})",
            lines.len()
        );
        return 1;
    }
    let mut options = vec![("items".to_owned(), Value::Array(lines))];
    if let Some(title) = title {
        options.push(("title".to_owned(), Value::string(title)));
    }
    if let Some(query) = query {
        options.push(("query".to_owned(), Value::string(query)));
    }
    let request = Value::Array(vec![Value::string("picker"), Value::Object(options)]);
    let reply = match SocketExchange::from_environment().exchange(&request, PICKER_TIMEOUT) {
        Ok(reply) => reply,
        Err(ExchangeError::Unreachable(error)) => {
            err!("ui picker: cannot reach TermuxPP; open the app first ({error})");
            return 1;
        }
        Err(ExchangeError::Failed(error)) => {
            err!("ui picker: {error}");
            return 1;
        }
    };
    if let Some(error) = reply.get("error") {
        err!("ui picker: {}", error.display());
        return 1;
    }
    match reply.get("result") {
        Some(Value::String(line)) => {
            println!("{line}");
            0
        }
        // Closed without a choice, like fzf.
        Some(Value::Null) => 130,
        _ => {
            err!(
                "ui picker: unexpected reply from TermuxPP: {}",
                reply.to_compact()
            );
            1
        }
    }
}

pub fn complete(words: &[String]) -> Vec<String> {
    let [prior @ .., current] = words else {
        return Vec::new();
    };
    let mut args = prior
        .iter()
        .map(String::as_str)
        .filter(|word| *word != "--json");
    let Some(action) = args.next() else {
        return ACTIONS
            .iter()
            .copied()
            .chain(["--help", "--json"])
            .filter(|word| word.starts_with(current))
            .map(str::to_owned)
            .collect();
    };
    let Ok(Some(action)) = crate::cli::resolve_prefix(action, ACTIONS.iter().copied()) else {
        return Vec::new();
    };
    if prior.last().is_some_and(|word| word == "--search") {
        return Vec::new();
    }
    if action == "picker" {
        if prior
            .last()
            .is_some_and(|word| word == "--title" || word == "--query")
        {
            return Vec::new();
        }
        return ["--help", "--query", "--title"]
            .into_iter()
            .filter(|word| word.starts_with(current) && !prior.iter().any(|used| used == word))
            .map(str::to_owned)
            .collect();
    }
    let mut candidates = vec!["--help", "--json"];
    if action != "clear" && !prior.iter().any(|word| OPERATIONS.contains(&word.as_str())) {
        candidates.extend(OPERATIONS);
    }
    match action {
        "settings" | "palette" => candidates.push("--search"),
        "sidebar" => candidates.push("--right"),
        _ => {}
    }
    candidates
        .into_iter()
        .filter(|word| word.starts_with(current) && !prior.iter().any(|used| used == word))
        .map(str::to_owned)
        .collect()
}
