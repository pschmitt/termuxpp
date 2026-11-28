//! Open surfaces in the running TermuxPP activity through its app-local socket.

use tpp_session::client::SocketExchange;
use tpp_session::json::{self, Value};

pub const ACTIONS: &[&str] = &[
    "clear",
    "more",
    "palette",
    "session-switcher",
    "settings",
    "sidebar",
    "tmux-switcher",
];

/// What to do with a surface; `show` is the default.
const OPERATIONS: &[&str] = &["show", "hide", "toggle"];

const HELP: &str = "Usage: tpp ui COMMAND [show|hide|toggle] [--json]\nShow, hide or toggle part of the TermuxPP interface in the visible terminal.\n\nCommands:\n  clear                Close everything: sidebars, palette, pickers, menu, Settings\n  more                 The terminal More menu\n  palette              The command palette [--search QUERY]\n  session-switcher     The session picker\n  settings             Settings [--search QUERY]\n  sidebar              The session sidebar [--right]\n  tmux-switcher        The tmux window picker\n\nWithout show, hide or toggle, the command shows the surface.\n";

/// The options that ACTION takes besides show, hide and toggle.
fn usage(action: &str) -> String {
    match action {
        "clear" => "Usage: tpp ui clear [--json]\n".to_owned(),
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
