//! End-to-end tests: the real binary talks to a fake app on an abstract Unix socket.

use std::io::{BufRead, BufReader, Write};
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(not(target_os = "android"))]
use std::os::linux::net::SocketAddrExt;
use std::os::unix::net::{SocketAddr, UnixListener};
use std::process::{Command, Output, Stdio};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::mpsc::{self, Receiver};
use std::thread;

static COUNTER: AtomicU32 = AtomicU32::new(0);

/// A fake app: answers every connection with `reply` and reports the request lines it saw.
struct FakeApp {
    name: String,
    requests: Receiver<String>,
}

impl FakeApp {
    /// Serves `connections` requests.
    fn start(reply: &'static str, connections: usize) -> FakeApp {
        let name = format!(
            "tpp-session-test-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::SeqCst)
        );
        let address = SocketAddr::from_abstract_name(name.as_bytes()).unwrap();
        let listener = UnixListener::bind_addr(&address).unwrap();
        let (sender, requests) = mpsc::channel();
        thread::spawn(move || {
            for _ in 0..connections {
                let Ok((stream, _)) = listener.accept() else {
                    return;
                };
                let mut line = String::new();
                let mut reader = BufReader::new(stream);
                let _ = reader.read_line(&mut line);
                let _ = sender.send(line);
                let _ = reader.get_mut().write_all(reply.as_bytes());
            }
        });
        FakeApp { name, requests }
    }

    fn request(&self) -> String {
        self.requests
            .recv_timeout(std::time::Duration::from_secs(10))
            .unwrap()
    }

    fn command(&self, args: &[&str]) -> Command {
        let mut command = tpp(args);
        command.env("TPP_SESSION_SOCKET", &self.name);
        command
    }
}

fn tpp(args: &[&str]) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_tpp-session"));
    command
        .args(args)
        .env_remove("NO_COLOR")
        .env_remove("TPP_SESSION_SOCKET")
        .stdin(Stdio::null());
    command
}

fn text(bytes: &[u8]) -> String {
    String::from_utf8(bytes.to_vec()).unwrap()
}

fn status(output: &Output) -> i32 {
    output.status.code().unwrap()
}

#[test]
fn list_prints_the_table_and_sends_one_request() {
    let app = FakeApp::start(
        r#"{"result": [{"id": "abc", "position": 1, "current": true, "running": true, "name": "", "title": "bash"}, {"id": "def", "position": 2, "current": false, "running": true, "name": "", "title": ""}]}"#,
        1,
    );
    let output = app.command(&["list"]).output().unwrap();
    assert_eq!(app.request(), "[\"list\"]\n");
    assert_eq!(status(&output), 0);
    assert_eq!(
        text(&output.stdout),
        "  POS  ID                                    STATE    NAME / TITLE\n*   1  abc  running  bash\n    2  def  running  Session 2\n"
    );
    assert_eq!(text(&output.stderr), "");
}

#[test]
fn commands_send_ids_names_and_positions() {
    for (args, request) in [
        (&["switcher"][..], "[\"switcher\"]\n"),
        (&["close", "abc"][..], "[\"close\", \"abc\"]\n"),
        (&["move", "abc", "3"][..], "[\"move\", \"abc\", 3]\n"),
        (
            &["rename", "abc", "n\u{e9}w name"][..],
            "[\"rename\", \"abc\", \"n\u{e9}w name\"]\n",
        ),
        (
            &["--json", "reload-settings"][..],
            "[\"reload-settings\"]\n",
        ),
    ] {
        let app = FakeApp::start("{\"ok\": true}\n", 1);
        let output = app.command(args).output().unwrap();
        assert_eq!(app.request(), request, "{args:?}");
        assert_eq!(status(&output), 0, "{args:?}");
    }
}

#[test]
fn json_output_goes_to_standard_output() {
    let app = FakeApp::start("{\"ok\": true}\n", 1);
    let output = app.command(&["focus", "abc", "--json"]).output().unwrap();
    assert_eq!(
        text(&output.stdout),
        "{\n  \"ok\": true,\n  \"action\": \"focus\",\n  \"id\": \"abc\"\n}\n"
    );
}

#[test]
fn an_error_reply_fails_with_its_message() {
    let app = FakeApp::start("{\"error\": \"no such session\"}\n", 1);
    let output = app.command(&["kill", "abc"]).output().unwrap();
    assert_eq!(status(&output), 1);
    assert_eq!(text(&output.stdout), "");
    assert_eq!(text(&output.stderr), "tpp-session: no such session\n");
}

#[test]
fn an_absent_app_is_reported() {
    let output = tpp(&["list"])
        .env("TPP_SESSION_SOCKET", "tpp-session-test-nobody-home")
        .output()
        .unwrap();
    assert_eq!(status(&output), 1);
    assert_eq!(
        text(&output.stderr),
        "tpp-session: cannot reach TermuxPP; open the app first ([Errno 111] Connection refused)\n"
    );
}

#[test]
fn an_app_that_closes_without_answering_is_reported() {
    let app = FakeApp::start("", 1);
    let output = app.command(&["list"]).output().unwrap();
    assert_eq!(status(&output), 1);
    assert!(
        text(&output.stderr).starts_with("tpp-session: "),
        "{}",
        text(&output.stderr)
    );
}

#[test]
fn an_oversized_request_is_refused_without_connecting() {
    let long = "x".repeat(5000);
    let output = tpp(&["rename", "abc", &long])
        .env("TPP_SESSION_SOCKET", "tpp-session-test-nobody-home")
        .output()
        .unwrap();
    assert_eq!(status(&output), 1);
    assert_eq!(
        text(&output.stderr),
        "tpp-session: session command is too long\n"
    );
}

#[test]
fn completions_need_no_app() {
    for shell in ["bash", "fish", "zsh"] {
        let output = tpp(&["completions", shell]).output().unwrap();
        assert_eq!(status(&output), 0, "{shell}");
        let script = text(&output.stdout);
        assert!(script.contains("tpp-session"), "{shell}");
        assert!(script.ends_with('\n'), "{shell}");
        let json = tpp(&["--json", "completions", shell]).output().unwrap();
        assert!(
            text(&json.stdout).starts_with("{\n  \"output\": \""),
            "{shell}"
        );
    }
}

#[test]
fn usage_errors_exit_with_two() {
    for args in [
        &[][..],
        &["close"],
        &["bogus"],
        &["list", "--nope"],
        &["move", "a", "x"],
    ] {
        let output = tpp(args).output().unwrap();
        assert_eq!(status(&output), 2, "{args:?}");
        assert_eq!(text(&output.stdout), "", "{args:?}");
        assert!(
            text(&output.stderr).starts_with("usage: tpp-session"),
            "{args:?}"
        );
    }
}

#[test]
fn help_exits_with_zero_on_standard_output() {
    for args in [
        &["--help"][..],
        &["-h"],
        &["list", "-h"],
        &["move", "--help"],
    ] {
        let output = tpp(args).output().unwrap();
        assert_eq!(status(&output), 0, "{args:?}");
        assert_eq!(text(&output.stderr), "", "{args:?}");
        assert!(
            text(&output.stdout).starts_with("usage: tpp-session"),
            "{args:?}"
        );
    }
}

/// The help texts of the Python implementation, one section per parser: `### NAME` lines, the
/// main parser's name is empty.
fn golden_help() -> Vec<(String, String)> {
    let fixture = include_str!("fixtures/python-help.txt");
    let mut sections: Vec<(String, String)> = Vec::new();
    for line in fixture.split_inclusive('\n') {
        if let Some(name) = line
            .strip_prefix("### ")
            .or_else(|| line.strip_prefix("###\n"))
        {
            sections.push((name.trim_end_matches('\n').to_string(), String::new()));
        } else if let Some((_, body)) = sections.last_mut() {
            body.push_str(line);
        }
    }
    sections
}

#[test]
fn help_texts_match_the_python_implementation() {
    let sections = golden_help();
    assert_eq!(sections.len(), 9);
    for (name, expected) in sections {
        let args: Vec<&str> = if name.is_empty() {
            vec!["--help"]
        } else {
            vec![name.as_str(), "--help"]
        };
        let output = tpp(&args).output().unwrap();
        let expected = if name.is_empty() {
            expected
                .replace("reload-settings}", "reload-settings,switcher}")
                .replace(
                    "reload app properties and styling\n",
                    "reload app properties and styling\n    switcher            open the session switcher\n",
                )
        } else {
            expected
        };
        assert_eq!(text(&output.stdout), expected, "help for {name:?}");
    }
}

#[test]
fn shell_completion_asks_the_app() {
    let app = FakeApp::start(
        "{\"result\": [{\"id\": \"aa\", \"position\": 1}, {\"id\": \"ab\", \"position\": 2}]}\n",
        1,
    );
    let output = app.command(&["__complete", "close", "a"]).output().unwrap();
    assert_eq!(app.request(), "[\"list\"]\n");
    assert_eq!(text(&output.stdout), "aa\nab\n");
    let offline = tpp(&["__complete", "close", ""])
        .env("TPP_SESSION_SOCKET", "tpp-session-test-nobody-home")
        .output()
        .unwrap();
    assert_eq!(
        (status(&offline), text(&offline.stdout)),
        (0, String::new())
    );
}

#[test]
fn bash_completion_script_works_with_the_binary() {
    // The bash of a Nix build sandbox has no readline, so no complete/compgen.
    let has_completion = Command::new("bash")
        .args(["-c", "type complete compgen"])
        .output()
        .is_ok_and(|o| o.status.success());
    if !has_completion {
        eprintln!("skipping: bash without programmable completion");
        return;
    }
    let app = FakeApp::start(
        "{\"result\": [{\"id\": \"aa\", \"position\": 1}, {\"id\": \"ab\", \"position\": 2}]}\n",
        3,
    );
    let script = text(&tpp(&["completions", "bash"]).output().unwrap().stdout);
    let program = env!("CARGO_BIN_EXE_tpp-session");
    let driver = format!(
        r#"tpp-session() {{ "{program}" "$@"; }}
{script}
complete_line() {{
  COMP_WORDS=("$@"); COMP_CWORD=$(( $# - 1 ))
  _termuxpp_session
  printf '%s\n' "${{COMPREPLY[@]}}"
}}
complete_line tpp-session close a
echo --
complete_line tpp-session move aa ''
echo --
complete_line tpp-session list --j
echo --
complete_line tpp-session completions ''
"#
    );
    let output = Command::new("bash")
        .args(["-c", &driver])
        .env("TPP_SESSION_SOCKET", &app.name)
        .output()
        .unwrap();
    assert_eq!(text(&output.stderr), "");
    assert_eq!(
        text(&output.stdout),
        "aa\nab\n--\n1\n2\n--\n--json\n--\nbash\nfish\nzsh\n"
    );
}

#[test]
fn completion_scripts_are_syntactically_valid() {
    for (shell, flag) in [("bash", "-n"), ("zsh", "-n"), ("fish", "--no-execute")] {
        let available = std::env::var_os("PATH")
            .map(|path| std::env::split_paths(&path).any(|dir| dir.join(shell).is_file()))
            .unwrap_or(false);
        if !available {
            eprintln!("skipping {shell}: not installed");
            continue;
        }
        let script = text(&tpp(&["completions", shell]).output().unwrap().stdout);
        let path =
            std::env::temp_dir().join(format!("tpp-session-syntax-{}-{shell}", std::process::id()));
        std::fs::write(&path, script).unwrap();
        let result = Command::new(shell).arg(flag).arg(&path).output();
        let _ = std::fs::remove_file(&path);
        match result {
            Ok(output) => assert!(output.status.success(), "{shell}: {}", text(&output.stderr)),
            // The shell is not installed here
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                eprintln!("skipping {shell}: not installed")
            }
            Err(error) => panic!("{shell}: {error}"),
        }
    }
}

#[test]
fn program_is_a_small_static_friendly_binary() {
    // No dependency beyond the standard library: the crate must stay buildable offline.
    let manifest = include_str!("../Cargo.toml");
    let dependencies = manifest.split("[dependencies]").nth(1).unwrap_or("");
    let section: String = dependencies
        .lines()
        .take_while(|line| !line.starts_with('['))
        .collect();
    assert!(
        section.trim().is_empty(),
        "unexpected dependencies:\n{section}"
    );
}
