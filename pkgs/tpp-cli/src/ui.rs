//! Open surfaces in the running TermuxPP activity through its app-local socket.

use tpp_session::client::SocketExchange;
use tpp_session::json::{self, Value};

pub const ACTIONS: &[&str] = &["more", "settings", "session-switcher", "sidebar"];

const HELP: &str = "Usage: tpp ui COMMAND [--json]\nOpen part of the TermuxPP interface from the visible terminal.\n\nCommands:\n  more                 Open the terminal More menu\n  settings             Open Settings [--search QUERY]\n  session-switcher     Open the session picker\n  sidebar              Open the session sidebar\n";

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
    while let Some(arg) = args.next() {
        match arg {
            "--json" => json_output = true,
            "-h" | "--help" => {
                let search = if action == "settings" {
                    " [--search QUERY]"
                } else {
                    ""
                };
                print!(
                    "{}",
                    crate::ctx::color_help(&format!("Usage: tpp ui {action}{search} [--json]\n"))
                );
                return 0;
            }
            "--search" if action == "settings" && query.is_none() => {
                let Some(value) = args.next_if(|value| !value.starts_with("--")) else {
                    err!("ui settings: --search requires a query");
                    return 2;
                };
                query = Some(value);
            }
            _ => {
                err!("ui {action}: unexpected argument '{arg}'");
                return 2;
            }
        }
    }

    let mut request = vec![Value::string("ui"), Value::string(action)];
    if let Some(query) = query {
        request.push(Value::string(query));
    }
    let exchange = SocketExchange::from_environment();
    if let Err(message) = tpp_session::cli::request_app(&exchange, &Value::Array(request)) {
        err!("ui {action}: {message}");
        return 1;
    }

    if json_output {
        let response = Value::Object(vec![
            ("ok".to_owned(), Value::Bool(true)),
            ("action".to_owned(), Value::string(action)),
        ]);
        let text = response.to_pretty();
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
    let candidates = match args.next() {
        None => {
            return ACTIONS
                .iter()
                .copied()
                .chain(["--help", "--json"])
                .filter(|word| word.starts_with(current))
                .map(str::to_owned)
                .collect()
        }
        Some(action) => match crate::cli::resolve_prefix(action, ACTIONS.iter().copied()) {
            Ok(Some("settings")) if prior.last().is_some_and(|word| word == "--search") => {
                return Vec::new();
            }
            Ok(Some("settings")) if !prior.iter().any(|word| word == "--search") => {
                &["--help", "--json", "--search"][..]
            }
            Ok(Some(_)) => &["--help", "--json"][..],
            _ => return Vec::new(),
        },
    };
    candidates
        .iter()
        .copied()
        .filter(|word| word.starts_with(current) && !prior.iter().any(|used| used == word))
        .map(str::to_owned)
        .collect()
}
