//! The `tpp session` command: parses the command line, asks the app and prints the answer.

use std::io::Write;

use crate::args::{self, Command, Outcome, Parsed};
use crate::client::{Exchange, ExchangeError, COMMAND_TIMEOUT};
use crate::complete;
use crate::json::{self, Value};
use crate::printable::is_printable;

const PROGRAM: &str = "tpp session";

/// What the command needs to know about the terminal it runs in.
pub struct Environment {
    /// Color the JSON output: standard output is a terminal and `NO_COLOR` is not set.
    pub color: bool,
}

/// Runs the command for `args` (the arguments after the program name) and returns the exit status:
/// 0 on success, 1 when the command failed, 2 for a usage error.
pub fn run(
    args: &[String],
    exchange: &dyn Exchange,
    environment: &Environment,
    out: &mut dyn Write,
    err: &mut dyn Write,
) -> u8 {
    if args.first().map(String::as_str) == Some("__complete") {
        for candidate in complete::complete(&args[1..], exchange) {
            let _ = writeln!(out, "{candidate}");
        }
        return 0;
    }
    match args::parse(args) {
        Outcome::Help(text) => {
            let _ = out.write_all(text.as_bytes());
            0
        }
        Outcome::Error(text) => {
            let _ = err.write_all(text.as_bytes());
            2
        }
        Outcome::Run(parsed) => match execute(&parsed, exchange, environment, out) {
            Ok(()) => 0,
            Err(message) => {
                let _ = writeln!(err, "{PROGRAM}: {message}");
                1
            }
        },
    }
}

fn execute(
    parsed: &Parsed,
    exchange: &dyn Exchange,
    environment: &Environment,
    out: &mut dyn Write,
) -> Result<(), String> {
    let reply = request_app(exchange, &request(&parsed.command))?;

    if parsed.command == Command::List {
        let sessions = reply.get("result").ok_or_else(|| unexpected(&reply))?;
        if parsed.json {
            print_json(out, sessions, environment);
        } else {
            let table = table(sessions, environment.color).ok_or_else(|| unexpected(&reply))?;
            let _ = out.write_all(table.as_bytes());
        }
    } else if matches!(parsed.command, Command::New { .. }) {
        let Some(Value::String(id)) = reply.get("result") else {
            return Err(unexpected(&reply));
        };
        if parsed.json {
            let Value::Object(mut entries) = outcome(&parsed.command) else {
                unreachable!()
            };
            entries.push(("id".to_string(), Value::string(id)));
            print_json(out, &Value::Object(entries), environment);
        } else {
            let _ = writeln!(out, "{id}");
        }
    } else if parsed.json {
        print_json(out, &outcome(&parsed.command), environment);
    }
    Ok(())
}

/// Send a command to the running app and report socket or application errors.
pub fn request_app(exchange: &dyn Exchange, request: &Value) -> Result<Value, String> {
    let reply = match exchange.exchange(request, COMMAND_TIMEOUT) {
        Ok(reply) => reply,
        Err(ExchangeError::Unreachable(error)) => {
            return Err(format!(
                "cannot reach TermuxPP; open the app first ({error})"
            ));
        }
        Err(ExchangeError::Failed(error)) => return Err(error),
    };
    let Value::Object(_) = reply else {
        return Err(unexpected(&reply));
    };
    if let Some(error) = reply.get("error") {
        return Err(error.display());
    }

    Ok(reply)
}

fn unexpected(reply: &Value) -> String {
    format!("unexpected reply from TermuxPP: {}", reply.to_compact())
}

/// The request line for the app: `[command, id?, name or position?]`.
fn request(command: &Command) -> Value {
    let mut items = vec![Value::string(command.name())];
    match command {
        Command::New { name, argv } => {
            items.push(name.as_deref().map(Value::string).unwrap_or(Value::Null));
            items.push(Value::Array(argv.iter().map(Value::string).collect()));
        }
        Command::Close { id } | Command::Focus { id } | Command::Kill { id } => {
            items.push(Value::string(id.as_str()))
        }
        Command::Move { id, position } => {
            items.push(Value::string(id.as_str()));
            items.push(Value::Number(position.clone()));
        }
        Command::Rename { id, name } => {
            items.push(Value::string(id.as_str()));
            items.push(Value::string(name.as_str()));
        }
        Command::List | Command::ReloadSettings | Command::Switcher => {}
    }
    Value::Array(items)
}

/// What `--json` prints for a command that has no result of its own.
fn outcome(command: &Command) -> Value {
    let mut entries = vec![
        ("ok".to_string(), Value::Bool(true)),
        ("action".to_string(), Value::string(command.name())),
    ];
    match command {
        Command::Close { id } | Command::Focus { id } | Command::Kill { id } => {
            entries.push(("id".to_string(), Value::string(id.as_str())));
        }
        Command::Move { id, position } => {
            entries.push(("id".to_string(), Value::string(id.as_str())));
            entries.push(("position".to_string(), Value::Number(position.clone())));
        }
        Command::Rename { id, name } => {
            entries.push(("id".to_string(), Value::string(id.as_str())));
            entries.push(("name".to_string(), Value::string(name.as_str())));
        }
        Command::List | Command::New { .. } | Command::ReloadSettings | Command::Switcher => {}
    }
    Value::Object(entries)
}

/// The session table, or `None` when the reply is not a list of sessions.
fn table(sessions: &Value, color: bool) -> Option<String> {
    let header = "  POS  ID                                    STATE    NAME";
    let mut text = if color {
        format!("\x1b[1;34m{header}\x1b[0m\n")
    } else {
        format!("{header}\n")
    };
    for session in sessions.as_array()? {
        let current = if session.get("current")?.truthy() {
            '*'
        } else {
            ' '
        };
        let state = if session.get("running")?.truthy() {
            "running"
        } else {
            "exited"
        };
        let name = session.get("name")?;
        let label = if name.truthy() {
            name
        } else {
            session.get("title")?
        };
        let Value::String(label) = label else {
            return None;
        };
        let position = session.get("position")?.display();
        let label: String = if label.is_empty() {
            format!("Session {position}")
        } else {
            label
                .chars()
                .map(|letter| if is_printable(letter) { letter } else { '?' })
                .collect()
        };
        let id = session.get("id")?.display();
        let state = if color {
            let code = if state == "running" { "32" } else { "33" };
            format!("\x1b[{code}m{state:<7}\x1b[0m")
        } else {
            format!("{state:<7}")
        };
        text.push_str(&format!(
            "{current} {position:>3}  {id}  {state}  {label}\n"
        ));
    }
    Some(text)
}

fn print_json(out: &mut dyn Write, value: &Value, environment: &Environment) {
    let text = value.to_pretty();
    let text = if environment.color {
        json::colorize(&text)
    } else {
        text
    };
    let _ = writeln!(out, "{text}");
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::time::Duration;

    struct Fake {
        reply: Result<&'static str, ExchangeError>,
        requests: RefCell<Vec<String>>,
    }

    impl Fake {
        fn replying(reply: &'static str) -> Fake {
            Fake {
                reply: Ok(reply),
                requests: RefCell::new(Vec::new()),
            }
        }

        fn failing(error: ExchangeError) -> Fake {
            Fake {
                reply: Err(error),
                requests: RefCell::new(Vec::new()),
            }
        }
    }

    impl Exchange for Fake {
        fn exchange(&self, request: &Value, _timeout: Duration) -> Result<Value, ExchangeError> {
            self.requests.borrow_mut().push(request.to_compact());
            match &self.reply {
                Ok(text) => json::parse(text).map_err(ExchangeError::Failed),
                Err(ExchangeError::Unreachable(text)) => {
                    Err(ExchangeError::Unreachable(text.clone()))
                }
                Err(ExchangeError::Failed(text)) => Err(ExchangeError::Failed(text.clone())),
            }
        }
    }

    struct Ran {
        status: u8,
        out: String,
        err: String,
        requests: Vec<String>,
    }

    fn run_with(fake: &Fake, color: bool, line: &str) -> Ran {
        let args: Vec<String> = if line.is_empty() {
            Vec::new()
        } else {
            line.split(' ')
                .map(|word| {
                    if word == "''" {
                        String::new()
                    } else {
                        word.to_string()
                    }
                })
                .collect()
        };
        let (mut out, mut err) = (Vec::new(), Vec::new());
        let status = run(&args, fake, &Environment { color }, &mut out, &mut err);
        Ran {
            status,
            out: String::from_utf8(out).unwrap(),
            err: String::from_utf8(err).unwrap(),
            requests: fake.requests.borrow().clone(),
        }
    }

    fn ran(reply: &'static str, line: &str) -> Ran {
        run_with(&Fake::replying(reply), false, line)
    }

    const SESSIONS: &str = r#"{"result": [
        {"id": "11111111-1111-1111-1111-111111111111", "position": 1, "current": true, "running": true, "name": "", "title": "bash"},
        {"id": "22222222-2222-2222-2222-222222222222", "position": 10, "current": false, "running": false, "name": "Build \u0007bell", "title": "x"}
    ]}"#;

    #[test]
    fn list_prints_a_table() {
        let result = ran(SESSIONS, "list");
        assert_eq!(result.status, 0);
        assert_eq!(result.requests, ["[\"list\"]"]);
        assert_eq!(
            result.out,
            "  POS  ID                                    STATE    NAME\n\
             *   1  11111111-1111-1111-1111-111111111111  running  bash\n\
             \x20\x20 10  22222222-2222-2222-2222-222222222222  exited   Build ?bell\n"
        );
        assert_eq!(result.err, "");
    }

    #[test]
    fn list_colors_header_and_states_when_enabled() {
        let result = run_with(&Fake::replying(SESSIONS), true, "list");
        assert!(result.out.contains("\x1b[1;34m"));
        assert!(result.out.contains("\x1b[32mrunning\x1b[0m"));
        assert!(result.out.contains("\x1b[33mexited \x1b[0m"));
    }

    #[test]
    fn list_without_sessions_prints_the_header_only() {
        let result = ran(r#"{"result": []}"#, "list");
        assert_eq!(
            result.out,
            "  POS  ID                                    STATE    NAME\n"
        );
    }

    #[test]
    fn list_json_prints_the_result_array() {
        let result = ran(
            r#"{"result": [{"id": "a", "position": 1, "name": "é"}]}"#,
            "--json list",
        );
        assert_eq!(
            result.out,
            "[\n  {\n    \"id\": \"a\",\n    \"position\": 1,\n    \"name\": \"é\"\n  }\n]\n"
        );
        assert_eq!(ran(r#"{"result": []}"#, "list --json").out, "[]\n");
    }

    #[test]
    fn json_is_colored_on_request() {
        let result = run_with(
            &Fake::replying(r#"{"ok": true}"#),
            true,
            "reload-settings --json",
        );
        assert_eq!(result.out, "{\n  \u{1b}[36m\"ok\"\u{1b}[0m: \u{1b}[35mtrue\u{1b}[0m,\n  \u{1b}[36m\"action\"\u{1b}[0m: \u{1b}[32m\"reload-settings\"\u{1b}[0m\n}\n");
    }

    #[test]
    fn commands_send_the_request_the_app_expects() {
        for (line, request) in [
            ("close abc", r#"["close", "abc"]"#),
            ("focus abc", r#"["focus", "abc"]"#),
            ("kill abc", r#"["kill", "abc"]"#),
            ("move abc 3", r#"["move", "abc", 3]"#),
            ("move abc -2", r#"["move", "abc", -2]"#),
            ("move abc 1_0", r#"["move", "abc", 10]"#),
            ("rename abc new", r#"["rename", "abc", "new"]"#),
            ("rename abc ''", r#"["rename", "abc", ""]"#),
            ("reload-settings", r#"["reload-settings"]"#),
        ] {
            let result = ran(r#"{"ok": true}"#, line);
            assert_eq!(result.status, 0, "{line}");
            assert_eq!(result.requests, [request], "{line}");
            assert_eq!(result.out, "", "{line}");
        }
    }

    #[test]
    fn json_names_the_action_and_its_arguments() {
        for (line, expected) in [
            ("close --json abc", "{\n  \"ok\": true,\n  \"action\": \"close\",\n  \"id\": \"abc\"\n}\n"),
            ("--json focus abc", "{\n  \"ok\": true,\n  \"action\": \"focus\",\n  \"id\": \"abc\"\n}\n"),
            (
                "move abc 02 --json",
                "{\n  \"ok\": true,\n  \"action\": \"move\",\n  \"id\": \"abc\",\n  \"position\": 2\n}\n",
            ),
            (
                "rename abc n\u{e9}w --json",
                "{\n  \"ok\": true,\n  \"action\": \"rename\",\n  \"id\": \"abc\",\n  \"name\": \"n\u{e9}w\"\n}\n",
            ),
            ("reload-settings --json", "{\n  \"ok\": true,\n  \"action\": \"reload-settings\"\n}\n"),
        ] {
            assert_eq!(ran(r#"{"ok": true}"#, line).out, expected, "{line}");
        }
    }

    #[test]
    fn failures_go_to_standard_error_with_status_one() {
        let result = ran(r#"{"error": "no such session"}"#, "close abc");
        assert_eq!(
            (result.status, result.out.as_str(), result.err.as_str()),
            (1, "", "tpp session: no such session\n")
        );
        // with or without --json
        let result = ran(r#"{"error": "no such session"}"#, "--json close abc");
        assert_eq!(
            (result.status, result.out.as_str(), result.err.as_str()),
            (1, "", "tpp session: no such session\n")
        );
        let result = ran(r#"{"error": 5}"#, "list");
        assert_eq!(result.err, "tpp session: 5\n");
    }

    #[test]
    fn an_absent_app_is_explained() {
        let fake = Fake::failing(ExchangeError::Unreachable(
            "[Errno 111] Connection refused".into(),
        ));
        let result = run_with(&fake, false, "list");
        assert_eq!(result.status, 1);
        assert_eq!(result.err, "tpp session: cannot reach TermuxPP; open the app first ([Errno 111] Connection refused)\n");
    }

    #[test]
    fn other_exchange_failures_show_their_message() {
        let fake = Fake::failing(ExchangeError::Failed("timed out".into()));
        let result = run_with(&fake, false, "kill abc");
        assert_eq!(
            (result.status, result.err.as_str()),
            (1, "tpp session: timed out\n")
        );
    }

    #[test]
    fn replies_of_the_wrong_shape_are_reported_not_crashed_on() {
        for (reply, line) in [
            ("[]", "list"),
            ("null", "close a"),
            (r#"{"result": 3}"#, "list"),
            (r#"{}"#, "list"),
            (r#"{"result": [1]}"#, "list"),
            (r#"{"result": [{"id": "a"}]}"#, "list"),
            (
                r#"{"result": [{"id": "a", "position": 1, "current": 0, "running": 1, "name": null, "title": null}]}"#,
                "list",
            ),
        ] {
            let result = ran(reply, line);
            assert_eq!(result.status, 1, "{reply}");
            assert!(
                result
                    .err
                    .starts_with("tpp session: unexpected reply from TermuxPP"),
                "{reply}: {}",
                result.err
            );
        }
    }

    #[test]
    fn usage_goes_to_the_right_stream() {
        let help = ran("{}", "--help");
        assert_eq!((help.status, help.err.as_str()), (0, ""));
        assert!(help.out.starts_with("usage: tpp session [-h] [--json]\n"));
        let usage = ran("{}", "close");
        assert_eq!((usage.status, usage.out.as_str()), (2, ""));
        assert!(usage
            .err
            .ends_with("tpp session close: error: the following arguments are required: id\n"));
        assert!(usage.requests.is_empty());
    }

    #[test]
    fn complete_prints_one_candidate_per_line() {
        let result = ran(
            r#"{"result": [{"id": "aa", "position": 1}, {"id": "ab", "position": 2}]}"#,
            "__complete close a",
        );
        assert_eq!((result.status, result.out.as_str()), (0, "aa\nab\n"));
        let result = ran("{}", "__complete zzz");
        assert_eq!((result.status, result.out.as_str()), (0, ""));
        let result = ran("{}", "__complete");
        assert_eq!((result.status, result.out.as_str()), (0, ""));
    }
}
