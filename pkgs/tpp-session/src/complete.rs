//! Shell completion: `tpp-session __complete WORD...` prints the candidates for the last WORD.
//!
//! The shell completion scripts call this, so the candidates always match the installed program,
//! and session IDs and positions come live from the app.

use crate::client::{Exchange, COMPLETION_TIMEOUT};
use crate::json::Value;

/// The commands in the order completion offers them.
pub const ACTIONS: [&str; 10] = [
    "list",
    "new",
    "close",
    "focus",
    "kill",
    "move",
    "rename",
    "reload-settings",
    "completions",
    "switcher",
];
/// The commands that take a session ID first.
const ID_ACTIONS: [&str; 5] = ["close", "focus", "kill", "move", "rename"];
/// The shells `completions` can print a script for.
pub const SHELLS: [&str; 3] = ["bash", "fish", "zsh"];

/// The values of one field of every session that start with `prefix`. Nothing when the app is
/// unreachable, slow or answers something unexpected.
fn session_values(exchange: &dyn Exchange, key: &str, prefix: &str) -> Vec<String> {
    let request = Value::Array(vec![Value::string("list")]);
    let Ok(reply) = exchange.exchange(&request, COMPLETION_TIMEOUT) else {
        return Vec::new();
    };
    let Value::Object(_) = reply else {
        return Vec::new();
    };
    let Some(Value::Array(sessions)) = reply.get("result") else {
        return Vec::new();
    };
    let mut values = Vec::with_capacity(sessions.len());
    for session in sessions {
        match session.get(key) {
            Some(value) => values.push(value.display()),
            None => return Vec::new(),
        }
    }
    values.retain(|value| value.starts_with(prefix));
    values
}

/// Candidates for the last of `words`, the words after `tpp-session`.
pub fn complete(words: &[String], exchange: &dyn Exchange) -> Vec<String> {
    let Some((current, mut before)) = words.split_last().map(|(last, rest)| (last.as_str(), rest))
    else {
        return Vec::new();
    };
    if let [only] = words {
        match only.as_str() {
            "ids" => return session_values(exchange, "id", ""),
            "positions" => return session_values(exchange, "position", ""),
            _ => {}
        }
    }
    while before.first().map(String::as_str) == Some("--json") {
        before = &before[1..];
    }
    let Some((action, rest)) = before.split_first() else {
        return ACTIONS
            .iter()
            .copied()
            .chain(["--help"])
            .filter(|name| name.starts_with(current))
            .map(str::to_string)
            .collect();
    };
    let action = action.as_str();
    if action == "-h" || action == "--help" || !ACTIONS.contains(&action) {
        return Vec::new();
    }
    if rest.iter().any(|word| word == "--help" || word == "-h") {
        return Vec::new();
    }
    if action == "new" && rest.iter().any(|word| word == "--") {
        return Vec::new();
    }
    if current.starts_with('-') {
        let options = if action == "new" {
            &["--json", "--help", "--name"][..]
        } else {
            &["--json", "--help"]
        };
        return options
            .iter()
            .copied()
            .filter(|flag| flag.starts_with(current) && !rest.iter().any(|word| word == flag))
            .map(str::to_string)
            .collect();
    }
    let positional = rest.iter().filter(|word| !word.starts_with('-')).count();
    if action == "completions" {
        if positional > 0 {
            return Vec::new();
        }
        return SHELLS
            .iter()
            .filter(|shell| shell.starts_with(current))
            .map(|shell| shell.to_string())
            .collect();
    }
    if ID_ACTIONS.contains(&action) {
        if positional == 0 {
            return session_values(exchange, "id", current);
        }
        if action == "move" && positional == 1 {
            return session_values(exchange, "position", current);
        }
    }
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client::ExchangeError;
    use crate::json;
    use std::time::Duration;

    struct Reply(&'static str);

    impl Exchange for Reply {
        fn exchange(&self, request: &Value, _timeout: Duration) -> Result<Value, ExchangeError> {
            assert_eq!(request.to_compact(), "[\"list\"]");
            json::parse(self.0).map_err(ExchangeError::Failed)
        }
    }

    struct Down;

    impl Exchange for Down {
        fn exchange(&self, _: &Value, _: Duration) -> Result<Value, ExchangeError> {
            Err(ExchangeError::Unreachable(
                "[Errno 111] Connection refused".into(),
            ))
        }
    }

    const SESSIONS: &str = r#"{"result": [{"id": "aaa-1", "position": 1}, {"id": "bbb-2", "position": 2}, {"id": "aab-3", "position": 3}]}"#;

    /// The words of a command line; a trailing space starts a new, empty word.
    fn words(text: &str) -> Vec<String> {
        text.split(' ').map(str::to_string).collect()
    }

    fn run(text: &str) -> Vec<String> {
        complete(&words(text), &Reply(SESSIONS))
    }

    fn offline(text: &str) -> Vec<String> {
        complete(&words(text), &Down)
    }

    #[test]
    fn offers_commands_and_help_first() {
        assert_eq!(run("li"), ["list"]);
        assert_eq!(run("re"), ["rename", "reload-settings"]);
        assert_eq!(run("--json c"), ["close", "completions"]);
        assert_eq!(run("--json --json --h"), ["--help"]);
        assert_eq!(
            offline(""),
            [
                "list",
                "new",
                "close",
                "focus",
                "kill",
                "move",
                "rename",
                "reload-settings",
                "completions",
                "switcher",
                "--help"
            ]
        );
        assert!(offline("zzz").is_empty());
        assert!(complete(&[], &Down).is_empty());
    }

    #[test]
    fn offers_options_not_given_yet() {
        assert_eq!(offline("list --"), ["--json", "--help"]);
        assert_eq!(offline("list --json --"), ["--help"]);
        assert_eq!(offline("close aaa-1 --j"), ["--json"]);
        assert_eq!(offline("move aaa-1 --json"), ["--json"]);
        assert!(offline("list --help --").is_empty());
        assert!(offline("list -h --").is_empty());
        assert!(offline("bogus --").is_empty());
        assert!(offline("--help list --").is_empty());
        assert!(offline("-h ").is_empty());
    }

    #[test]
    fn offers_shells_once() {
        assert_eq!(offline("completions "), ["bash", "fish", "zsh"]);
        assert_eq!(offline("completions b"), ["bash"]);
        assert_eq!(offline("completions --json f"), ["fish"]);
        assert!(offline("completions bash f").is_empty());
        assert!(offline("completions bash ").is_empty());
    }

    #[test]
    fn offers_session_ids_and_positions_from_the_app() {
        for action in ["close", "focus", "kill", "move", "rename"] {
            assert_eq!(run(&format!("{action} a")), ["aaa-1", "aab-3"], "{action}");
            assert_eq!(run(&format!("--json {action} b")), ["bbb-2"], "{action}");
            assert_eq!(run(&format!("{action} ")).len(), 3, "{action}");
        }
        assert_eq!(run("move aaa-1 2"), ["2"]);
        assert_eq!(run("move aaa-1 "), ["1", "2", "3"]);
        assert_eq!(run("move --json aaa-1 "), ["1", "2", "3"]);
        assert!(run("move aaa-1 2 ").is_empty());
        assert!(run("rename aaa-1 n").is_empty());
        assert!(run("close aaa-1 x").is_empty());
        assert!(run("list a").is_empty());
        assert!(run("reload-settings a").is_empty());
    }

    #[test]
    fn ids_and_positions_are_listed_whole_for_the_shell_scripts() {
        assert_eq!(run("ids"), ["aaa-1", "bbb-2", "aab-3"]);
        assert_eq!(run("positions"), ["1", "2", "3"]);
        // Only as the one and only word
        assert!(run("close ids").is_empty());
    }

    #[test]
    fn odd_or_missing_replies_give_nothing() {
        for reply in [
            r#"{"error": "no"}"#,
            r#"{"result": null}"#,
            r#"{"result": {"id": "a"}}"#,
            r#"{"result": [1]}"#,
            r#"{"result": [{"id": "a"}, {"position": 2}]}"#,
            r#"{"result": "text"}"#,
            "[]",
            "null",
        ] {
            for line in ["ids", "positions", "close ", "move a "] {
                assert!(
                    complete(&words(line), &Reply(reply)).is_empty(),
                    "{line:?} with {reply}"
                );
            }
        }
        for line in ["ids", "positions", "close ", "focus a"] {
            assert!(offline(line).is_empty(), "{line:?}");
        }
    }
}
