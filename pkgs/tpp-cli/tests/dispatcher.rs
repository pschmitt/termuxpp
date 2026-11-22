//! End to end tests of the tpp binary. The command table is the one built into
//! the binary and the commands are the ones of `tpp-api`; the app is played by
//! the test: `am` is a stub that logs the broadcast, and the test connects to
//! the sockets that the broadcast names, as the app does, to read what the
//! command forwarded on standard input and to answer.

#![cfg(target_os = "linux")]

use std::fs;
use std::io::{self, Read, Write};
use std::os::linux::net::SocketAddrExt;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{SocketAddr, UnixStream};
use std::path::PathBuf;
use std::process::{Child, Command, Output, Stdio};
use std::time::{Duration, Instant};

struct Fixture {
    dir: PathBuf,
}

/// What the app received: the arguments of the broadcast and the standard
/// input that the command forwarded.
struct Call {
    args: Vec<String>,
    stdin: Vec<u8>,
}

impl Call {
    /// The value of the string extra `--es KEY VALUE`.
    fn extra(&self, key: &str) -> Option<&str> {
        self.args
            .windows(3)
            .find(|w| (w[0] == "--es" || w[0] == "-e") && w[1] == key)
            .map(|w| w[2].as_str())
    }

    fn method(&self) -> &str {
        self.extra("api_method").expect("no api_method")
    }

    /// Whether the broadcast carries the extra `FLAG KEY VALUE` (as in
    /// `--ez force true`).
    fn has(&self, flag: &str, key: &str, value: &str) -> bool {
        self.args
            .windows(3)
            .any(|w| w[0] == flag && w[1] == key && w[2] == value)
    }
}

fn connect(name: &str) -> io::Result<UnixStream> {
    UnixStream::connect_addr(&SocketAddr::from_abstract_name(name)?)
}

impl Fixture {
    fn new(name: &str) -> Fixture {
        let dir = std::env::temp_dir().join(format!("tpp-test-{}-{name}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("usr/libexec/tpp/plugins")).unwrap();
        fs::create_dir_all(dir.join("usr/bin")).unwrap();
        let fx = Fixture { dir };
        // The arguments go to $AM_LOG, NUL separated; the file with the suffix
        // .done marks the log as complete.
        fx.script(
            "../../bin/am",
            "printf '%s\\0' \"$@\" > \"$AM_LOG\"; : > \"$AM_LOG.done\"",
        );
        fx
    }

    fn libexec(&self) -> PathBuf {
        self.dir.join("usr/libexec/tpp")
    }

    fn am_log(&self) -> PathBuf {
        self.dir.join("am.log")
    }

    fn script(&self, rel: &str, body: &str) {
        let path = self.libexec().join(rel);
        fs::write(&path, format!("#!/bin/sh\n{body}\n")).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).unwrap();
    }

    fn plugin(&self, name: &str, body: &str) {
        self.script(&format!("plugins/{name}"), body);
    }

    fn command(&self, args: &[&str]) -> Command {
        let mut cmd = Command::new(env!("CARGO_BIN_EXE_tpp"));
        cmd.args(args)
            .env_clear()
            .env("PATH", "/usr/bin:/bin")
            .env("TPP_API_AM", self.dir.join("usr/bin/am"))
            .env("AM_LOG", self.am_log())
            .env("TPP_PLUGIN_DIR", self.libexec().join("plugins"))
            .env("TMPDIR", &self.dir)
            .stdin(Stdio::null());
        cmd
    }

    /// Run a command that does not call the app.
    fn run(&self, args: &[&str]) -> Output {
        self.command(args).output_retry().unwrap()
    }

    /// Run a command that calls the app, which answers `reply`.
    fn app(&self, args: &[&str], reply: &[u8]) -> (Output, Call) {
        self.app_with(args, reply, |_| {})
    }

    fn app_with(
        &self,
        args: &[&str],
        reply: &[u8],
        tweak: impl Fn(&mut Command),
    ) -> (Output, Call) {
        // Running a stub that a test has just written can fail with ETXTBSY
        // (text file busy) while a parallel test forks; that lasts for moments.
        for _ in 0..100 {
            let mut cmd = self.command(args);
            tweak(&mut cmd);
            let (o, call) = self.play_app(cmd, reply);
            if !String::from_utf8_lossy(&o.stderr).contains("Text file busy") {
                return (o, call);
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        panic!("the am stub stays busy");
    }

    fn play_app(&self, mut cmd: Command, reply: &[u8]) -> (Output, Call) {
        let log = self.am_log();
        let done = self.dir.join("am.log.done");
        let _ = fs::remove_file(&log);
        let _ = fs::remove_file(&done);
        let mut child: Child = cmd
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();

        // Wait for the broadcast, or for the command to end without one
        let deadline = Instant::now() + Duration::from_secs(20);
        let mut args = Vec::new();
        loop {
            if done.exists() {
                args = fs::read(&log)
                    .unwrap()
                    .split(|b| *b == 0)
                    .map(|a| String::from_utf8_lossy(a).into_owned())
                    .collect::<Vec<_>>();
                args.pop(); // after the last NUL
                break;
            }
            if child.try_wait().unwrap().is_some() {
                break;
            }
            assert!(Instant::now() < deadline, "no broadcast from tpp");
            std::thread::sleep(Duration::from_millis(5));
        }
        let mut call = Call {
            args,
            stdin: Vec::new(),
        };
        if !call.args.is_empty() {
            let name = |key: &str| -> String {
                call.extra(key)
                    .unwrap_or_else(|| panic!("no {key}: {:?}", call.args))
                    .to_string()
            };
            let (to_app, from_app) = (name("socket_input"), name("socket_output"));
            // The command waits for the reply socket before it forwards
            // anything
            let mut answer = connect(&from_app).unwrap();
            let mut input = connect(&to_app).unwrap();
            input
                .set_read_timeout(Some(Duration::from_secs(10)))
                .unwrap();
            let _ = input.read_to_end(&mut call.stdin);
            let _ = answer.write_all(reply);
            // dropping the connection ends the reply
        }
        (child.wait_with_output().unwrap(), call)
    }
}

/// Running a script that a test has just written can fail with ETXTBSY (text
/// file busy) when a parallel test forks while the file is still open for
/// writing; that lasts for moments only.
trait OutputRetry {
    fn output_retry(&mut self) -> io::Result<Output>;
}

impl OutputRetry for Command {
    fn output_retry(&mut self) -> io::Result<Output> {
        let mut o = self.output()?;
        for _ in 0..50 {
            let busy = o.status.code() == Some(126)
                && String::from_utf8_lossy(&o.stderr).contains("Text file busy");
            if !busy {
                break;
            }
            std::thread::sleep(Duration::from_millis(20));
            o = self.output()?;
        }
        Ok(o)
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.dir);
    }
}

fn out(o: &Output) -> String {
    String::from_utf8_lossy(&o.stdout).into_owned()
}

fn err(o: &Output) -> String {
    String::from_utf8_lossy(&o.stderr).into_owned()
}

fn code(o: &Output) -> i32 {
    o.status.code().unwrap()
}

const BATTERY: &[u8] = b"{\"percentage\": 85, \"status\": \"FULL\"}\n";

#[test]
fn groups_default_to_list_or_get() {
    let fx = Fixture::new("default-actions");
    for (group, action, reply) in [
        ("clipboard", "get", b"hello".as_slice()),
        ("contacts", "list", b"[]".as_slice()),
        ("notification", "list", b"[]".as_slice()),
        ("sms", "list", b"[]".as_slice()),
        ("usb", "list", b"[]".as_slice()),
        ("sensor", "list", b"[]".as_slice()),
    ] {
        let (default, default_call) = fx.app(&[group], reply);
        let (explicit, explicit_call) = fx.app(&[group, action], reply);
        assert_eq!(code(&default), code(&explicit), "{group}");
        assert_eq!(default.stdout, explicit.stdout, "{group}");
        assert_eq!(default_call.method(), explicit_call.method(), "{group}");
    }
}

#[test]
fn api_errors_fail_in_human_and_json_modes() {
    let fx = Fixture::new("api-errors");
    for command in ["battery", "location"] {
        let reply = br#"{"API_ERROR":"permission denied"}"#;
        let (human, _) = fx.app(&[command], reply);
        assert_ne!(code(&human), 0, "{command}");
        assert!(out(&human).is_empty(), "{command}");
        assert!(err(&human).contains("permission denied"), "{command}");
        let (json, _) = fx.app(&["--json", command], reply);
        assert_ne!(code(&json), 0, "{command}");
        assert!(out(&json).contains("API_ERROR"), "{command}");
    }
}

#[test]
fn help_respects_color_controls() {
    let fx = Fixture::new("help-color");
    for args in [
        &["--help"][..],
        &["contacts", "list", "--help"][..],
        &["settings", "--help"][..],
        &["session", "--help"][..],
    ] {
        let colored = fx
            .command(args)
            .env("CLICOLOR_FORCE", "1")
            .output_retry()
            .unwrap();
        assert_eq!(code(&colored), 0, "{args:?}");
        assert!(
            out(&colored).contains("\x1b["),
            "{args:?}: {}",
            out(&colored)
        );
        let plain = fx
            .command(args)
            .env("CLICOLOR_FORCE", "1")
            .env("NO_COLOR", "1")
            .output_retry()
            .unwrap();
        assert!(!out(&plain).contains("\x1b["), "{args:?}");
    }
}

#[test]
fn bare_usage_and_usage_errors_use_stderr_colors() {
    let fx = Fixture::new("usage-stderr-color");
    for args in [&[][..], &["wifi", "unknown"], &["--unknown"]] {
        let output = fx
            .command(args)
            .env("CLICOLOR_FORCE", "1")
            .output_retry()
            .unwrap();
        assert_eq!(code(&output), 2);
        assert!(out(&output).is_empty());
        assert!(err(&output).contains("\x1b[1;36mUsage:"), "{args:?}");
        let output = fx
            .command(args)
            .env("CLICOLOR_FORCE", "1")
            .env("NO_COLOR", "1")
            .output_retry()
            .unwrap();
        assert!(!err(&output).contains("\x1b["), "{args:?}");
    }
}

#[test]
fn structured_read_commands_render_human_and_json_results() {
    let fx = Fixture::new("structured-commands");
    let commands: &[&[&str]] = &[
        &["battery"],
        &["audio", "info"],
        &["camera", "info"],
        &["call-log"],
        &["contacts", "list"],
        &["location"],
        &["keystore", "list"],
        &["saf", "dirs"],
        &["sensor", "list"],
        &["sms", "list"],
        &["notification", "list"],
        &["tts", "engines"],
        &["telephony", "cell-info"],
        &["telephony", "device-info"],
        &["usb", "list"],
        &["volume"],
        &["wifi", "connection"],
        &["wifi", "scan"],
    ];
    for command in commands {
        let (human, _) = fx.app(command, b"[{\"name\":\"example\",\"count\":2}]\n");
        assert_eq!(code(&human), 0, "{command:?}: {}", err(&human));
        let expected = if *command == ["location"] {
            "name: example\ncount: 2\n"
        } else {
            "- name: example\n  count: 2\n"
        };
        assert_eq!(out(&human).trim_end(), expected.trim_end(), "{command:?}");
        let mut args = vec!["--json"];
        args.extend_from_slice(command);
        let (json, _) = fx.app(&args, b"[{\"name\":\"example\",\"count\":2}]\n");
        assert_eq!(code(&json), 0, "{command:?}: {}", err(&json));
        assert_eq!(
            out(&json).trim_end(),
            "[\n  {\n    \"name\": \"example\",\n    \"count\": 2\n  }\n]",
            "{command:?}"
        );
    }
}

#[test]
fn silent_actions_acknowledge_requests() {
    let fx = Fixture::new("silent-actions");
    let commands: &[&[&str]] = &[
        &["vibrate", "-d", "100"],
        &["toast", "hello"],
        &["torch", "on"],
        &["notification", "send", "-t", "hello"],
        &["notification", "remove", "example"],
        &["notification", "create-channel", "example", "Example"],
        &["notification", "delete-channel", "example"],
        &["media", "play"],
        &["media", "pause"],
        &["media", "stop"],
        &["brightness", "100"],
        &["clipboard", "set", "example"],
        &["wifi", "enable"],
        &["wifi", "disable"],
        &["telephony", "call", "123"],
        &["media", "scan", "/example"],
        &["sms", "send", "-n", "123", "example"],
        &["infrared", "transmit", "-f", "38000", "100,100"],
        &["tts", "speak", "example"],
        &["download", "https://example.invalid/file"],
        &["share"],
        &["wallpaper", "-u", "https://example.invalid/image"],
    ];
    for command in commands {
        let (human, _) = fx.app(command, b"");
        assert_eq!(code(&human), 0, "{command:?}: {}", err(&human));
        assert_eq!(out(&human), "Request sent\n", "{command:?}");
        let mut args = vec!["--json"];
        args.extend_from_slice(command);
        let (json, _) = fx.app(&args, b"");
        assert_eq!(code(&json), 0, "{command:?}: {}", err(&json));
        assert_eq!(
            out(&json),
            "{\n  \"status\": \"request sent\"\n}\n",
            "{command:?}"
        );
    }
    let (info, _) = fx.app(&["media", "info"], b"");
    assert_eq!(out(&info), "No output\n");
    let invalid = fx.run(&["torch", "invalid"]);
    assert_ne!(code(&invalid), 0);
    assert!(!out(&invalid).contains("Request sent"));
    let help = fx.run(&["vibrate", "--help"]);
    assert_eq!(code(&help), 0);
    assert!(!out(&help).contains("Request sent"));
}

#[test]
fn human_and_json_output() {
    let fx = Fixture::new("json");
    let (human, call) = fx.app(&["battery"], BATTERY);
    assert_eq!(code(&human), 0, "{}", err(&human));
    assert_eq!(call.method(), "BatteryStatus");
    assert!(out(&human).contains("percentage: 85"));
    assert!(!out(&human).contains('\u{1b}'));

    let (json, _) = fx.app(&["--json", "battery"], BATTERY);
    assert_eq!(code(&json), 0);
    assert_eq!(
        out(&json),
        "{\n  \"percentage\": 85,\n  \"status\": \"FULL\"\n}\n"
    );
    // --json after the command is the same
    let (again, _) = fx.app(&["battery", "--json"], BATTERY);
    assert_eq!(out(&again), out(&json));
}

#[test]
fn color_is_opt_in_off_a_terminal() {
    let fx = Fixture::new("color");
    let (o, _) = fx.app_with(&["--json", "battery"], BATTERY, |c| {
        c.env("CLICOLOR_FORCE", "1");
    });
    assert!(out(&o).contains("\u{1b}[1;34m"));
    let (o, _) = fx.app_with(&["--json", "battery"], BATTERY, |c| {
        c.env("CLICOLOR_FORCE", "1").env("NO_COLOR", "1");
    });
    assert!(!out(&o).contains('\u{1b}'));
}

#[test]
fn text_is_passed_through_and_wrapped_for_json() {
    let fx = Fixture::new("text");
    let (o, call) = fx.app(&["clipboard", "get"], b"a \"b\"\nc");
    assert_eq!(call.method(), "Clipboard");
    assert_eq!(out(&o), "a \"b\"\nc");
    let (o, _) = fx.app(&["--json", "clipboard", "get"], b"a \"b\"\nc");
    assert_eq!(out(&o), "{\n  \"output\": \"a \\\"b\\\"\\nc\"\n}\n");
}

#[test]
fn binary_output_is_not_altered_and_refused_for_json() {
    let fx = Fixture::new("binary");
    let (o, _) = fx.app(&["toast", "hi"], b"ab\0cd");
    assert_eq!(o.stdout, b"ab\0cd");
    let (o, _) = fx.app(&["--json", "toast", "hi"], b"ab\0cd");
    assert_eq!(code(&o), 3);
    assert!(o.stdout.is_empty());
}

#[test]
fn invalid_utf8_is_refused_with_json_only() {
    let fx = Fixture::new("utf8");
    let (o, _) = fx.app(&["toast", "hi"], b"a\xffb\n");
    assert_eq!(o.stdout, b"a\xffb\n");
    let (o, _) = fx.app(&["--json", "toast", "hi"], b"a\xffb\n");
    assert_eq!(code(&o), 3);
    let (o, _) = fx.app(&["--json", "toast", "hi"], "caf\u{e9}\n".as_bytes());
    assert_eq!(out(&o), "{\n  \"output\": \"caf\u{e9}\"\n}\n");
}

#[test]
fn empty_output_and_exit_status() {
    let fx = Fixture::new("empty");
    let (o, _) = fx.app(&["battery"], b"");
    assert_eq!(out(&o), "No output\n");
    assert_eq!(code(&o), 0);
    let (o, _) = fx.app(&["--json", "battery"], b"");
    assert_eq!(out(&o), "null\n");

    // saf remove exits with the number that the app answers
    let (o, call) = fx.app(&["saf", "remove", "content://x"], b"7\n");
    assert_eq!(code(&o), 7);
    assert_eq!(call.method(), "SAF");
    assert_eq!(call.extra("uri"), Some("content://x"));
    let (o, _) = fx.app(&["saf", "remove", "content://x"], b"0\n");
    assert_eq!(code(&o), 0);
}

#[test]
fn raw_commands_pass_bytes_and_refuse_json() {
    let fx = Fixture::new("raw");
    // keystore sign prints the decoded signature
    let (o, call) = fx.app(&["keystore", "sign", "key", "SHA256"], b"/wBzaWc=\n");
    assert_eq!(o.stdout, b"\xff\0sig");
    assert_eq!(call.extra("alias"), Some("key"));
    let o = fx.run(&["--json", "keystore", "sign", "x", "y"]);
    assert_eq!(code(&o), 2);
    assert!(err(&o).contains("--json is not supported"), "{}", err(&o));
    let (o, call) = fx.app(&["sensor", "list"], b"x\0y");
    assert_eq!(o.stdout, b"x\0y");
    assert_eq!(call.method(), "Sensor");
    // help is never refused, and the other actions are auto
    let o = fx.run(&["--json", "keystore", "--help"]);
    assert_eq!(code(&o), 0);
    assert!(out(&o).contains("keystore"), "{}", out(&o));
    let (o, _) = fx.app(&["--json", "keystore", "list"], b"{\"keys\": []}\n");
    assert_eq!(out(&o), "{\n  \"keys\": []\n}\n");
}

#[test]
fn sensor_renders_each_reading_and_supports_json() {
    let fx = Fixture::new("sensor-render");
    let reply =
        b"{\"accelerometer\":{\"values\":[1,2,3]}}\n{\"accelerometer\":{\"values\":[4,5,6]}}\n";
    let (human, _) = fx.app(&["sensor", "read", "-a", "-n", "2"], reply);
    assert_eq!(code(&human), 0);
    assert!(out(&human).contains("accelerometer:"), "{}", out(&human));
    assert!(!out(&human).contains("\"accelerometer\""));
    let (json, _) = fx.app(&["sensor", "list", "--json"], b"{\"sensors\":[\"gyro\"]}\n");
    assert_eq!(code(&json), 0);
    assert!(out(&json).contains("\"sensors\": ["), "{}", out(&json));
}

#[test]
fn location_updates_render_for_people_and_remain_an_array_in_json() {
    let fx = Fixture::new("location-render");
    let reply = b"[\n{\"latitude\":1},\n{\"latitude\":2}\n]\n";
    let (human, _) = fx.app(&["location", "watch"], reply);
    assert_eq!(code(&human), 0);
    assert!(out(&human).contains("latitude: 1"), "{}", out(&human));
    assert!(out(&human).contains("latitude: 2"), "{}", out(&human));
    let (json, _) = fx.app(&["location", "watch", "--json"], reply);
    assert_eq!(code(&json), 0);
    assert!(out(&json).starts_with("[\n"), "{}", out(&json));
}

#[test]
fn fixed_arguments_and_user_arguments() {
    let fx = Fixture::new("args");
    let (_, call) = fx.app(&["wifi", "enable"], b"");
    assert_eq!(call.method(), "WifiEnable");
    assert!(call.has("--ez", "enabled", "true"), "{:?}", call.args);
    let (_, call) = fx.app(&["wifi", "disable"], b"");
    assert!(call.has("--ez", "enabled", "false"), "{:?}", call.args);
    let (_, call) = fx.app(&["vibrate", "-d", "50", "-f"], b"");
    assert!(call.has("--ei", "duration_ms", "50"), "{:?}", call.args);
    assert!(call.has("--ez", "force", "true"), "{:?}", call.args);

    // the text of toast goes to the app on standard input; --json after a
    // "--" is not ours
    let (o, call) = fx.app(&["toast", "a b", "--", "--json"], b"");
    assert_eq!(code(&o), 0, "{}", err(&o));
    assert_eq!(call.stdin, b"a b -- --json\n");
    // --json before it is, and wraps the answer
    let (o, call) = fx.app(&["--json", "toast", "a b", "--", "--json"], b"shown");
    assert_eq!(call.stdin, b"a b -- --json\n");
    assert_eq!(out(&o), "{\n  \"output\": \"shown\"\n}\n");
}

#[test]
fn an_unreachable_app_is_reported() {
    let fx = Fixture::new("noapp");
    let o = fx
        .command(&["battery"])
        .env("TPP_API_AM", fx.dir.join("no-such-am"))
        .output_retry()
        .unwrap();
    assert_ne!(code(&o), 0);
    assert!(o.stdout.is_empty());
    assert!(err(&o).contains("no-such-am"), "{}", err(&o));
}

#[test]
fn help_list_and_unknown() {
    let fx = Fixture::new("help");
    let o = fx.run(&["--help"]);
    assert_eq!(code(&o), 0);
    assert!(out(&o).starts_with("Usage: tpp [--json] COMMAND"));
    assert_eq!(out(&fx.run(&["help"])), out(&o));
    let o = fx.run(&["list"]);
    assert_eq!(code(&o), 2);
    assert!(err(&o).contains("unknown command 'list'"));
    let o = fx.run(&["wifi"]);
    assert_eq!(code(&o), 2);
    assert!(err(&o).contains("Usage: tpp [--json] wifi COMMAND"));
    let o = fx.run(&["help", "wifi"]);
    assert_eq!(code(&o), 0);
    assert!(out(&o).contains("scan"));
    let o = fx.run(&["wifi", "nope"]);
    assert_eq!(code(&o), 2);
    assert!(err(&o).contains("unknown command 'nope'"));
    let o = fx.run(&["nope"]);
    assert_eq!(code(&o), 2);
    assert!(err(&o).contains("unknown command 'nope'"));
    let o = fx.run(&["--nope"]);
    assert_eq!(code(&o), 2);
    assert!(err(&o).contains("unknown option '--nope'"));
    assert_eq!(code(&fx.run(&[])), 2);
}

#[test]
fn plugins() {
    let fx = Fixture::new("plugins");
    fx.plugin(
        "hello",
        "printf 'argv:'; printf ' [%s]' \"$@\"; echo; echo oops >&2; exit 5",
    );
    let o = fx.run(&["hello", "a b", "--json", "c"]);
    assert_eq!(code(&o), 5);
    assert_eq!(out(&o), "argv: [a b] [--json] [c]\n");
    assert_eq!(err(&o), "oops\n");
    // a leading --json is added in front of "--"
    let o = fx.run(&["--json", "hello", "x", "--", "y"]);
    assert_eq!(out(&o), "argv: [x] [--json] [--] [y]\n");
    // built-ins win, plugins are listed, and help asks the plugin
    fx.plugin("battery", "echo plugin");
    let (o, _) = fx.app(&["battery"], BATTERY);
    assert!(out(&o).contains("percentage"));
    assert!(out(&fx.run(&["--help"])).contains("Plugins:\n  hello\n"));
    let o = fx.run(&["help", "hello"]);
    assert!(out(&o).contains("[--help]"));
    // not executable
    fx.script("plugins/dull", "echo x");
    fs::set_permissions(
        fx.libexec().join("plugins/dull"),
        fs::Permissions::from_mode(0o644),
    )
    .unwrap();
    assert_eq!(code(&fx.run(&["dull"])), 2);
    // no plugins at all
    let o = fx
        .command(&["hello"])
        .env("TPP_PLUGIN_DIR", "")
        .output_retry()
        .unwrap();
    assert_eq!(code(&o), 2);
}

fn complete(fx: &Fixture, words: &[&str]) -> (i32, Vec<String>) {
    let mut args = vec!["--complete"];
    args.extend_from_slice(words);
    let o = fx.run(&args);
    (code(&o), out(&o).lines().map(str::to_string).collect())
}

#[test]
fn completion() {
    let fx = Fixture::new("complete");
    let (rc, names) = complete(&fx, &["w"]);
    assert_eq!(rc, 0);
    assert!(names.contains(&"wifi".to_string()));
    assert!(!names.contains(&"battery".to_string()));
    let (_, names) = complete(&fx, &[""]);
    assert!(names.contains(&"help".to_string()));
    assert!(!names.contains(&"list".to_string()));
    let (_, actions) = complete(&fx, &["wifi", ""]);
    assert!(actions.contains(&"scan".to_string()));
    let (_, flags) = complete(&fx, &["wifi", "scan", "-"]);
    assert!(flags.contains(&"--json".to_string()) && flags.contains(&"--help".to_string()));
    // --json is not offered twice
    let (_, flags) = complete(&fx, &["--json", "wifi", "scan", "-"]);
    assert!(!flags.contains(&"--json".to_string()));
    // raw commands have no --json
    let (_, flags) = complete(&fx, &["keystore", "sign", "-"]);
    assert!(!flags.contains(&"--json".to_string()));
    // nothing to suggest after "--"
    assert_eq!(complete(&fx, &["keystore", "--", ""]).0, 1);
}

#[test]
fn plugin_completion_is_delegated() {
    let fx = Fixture::new("plugin-complete");
    fx.plugin(
        "hello",
        "if [ \"$1\" = __complete ]; then shift; printf '%s\\n' one \"$@\"; exit 3; fi",
    );
    let o = fx.run(&["--complete", "hello", "x", "y"]);
    assert_eq!(code(&o), 3);
    assert_eq!(out(&o), "one\nx\ny\n");
}

#[test]
fn a_bare_binary_lists_and_completes() {
    // The tables and the commands are in the binary, so help and completion
    // work next to it without any installed file.
    let fx = Fixture::new("builtin");
    let bin = fx.dir.join("usr/bin/tpp");
    fs::copy(env!("CARGO_BIN_EXE_tpp"), &bin).unwrap();
    let run = |args: &[&str]| -> Output {
        // A parallel test may fork while the copy is still open for writing,
        // which makes the exec fail with ETXTBSY (errno 26) for a moment.
        for _ in 0..50 {
            let result = Command::new(&bin)
                .args(args)
                .env_clear()
                .env("PATH", "/usr/bin:/bin")
                .env("TPP_PLUGIN_DIR", "")
                .stdin(Stdio::null())
                .output();
            match result {
                Err(e) if e.raw_os_error() == Some(26) => {
                    std::thread::sleep(Duration::from_millis(20))
                }
                other => return other.unwrap(),
            }
        }
        panic!("{} stays busy", bin.display());
    };
    let o = run(&["--command-names"]);
    assert_eq!(code(&o), 0, "{}", err(&o));
    assert!(out(&o).contains("wifi scan"));
    let o = run(&["--complete", "wifi", ""]);
    assert!(out(&o).contains("scan"));
    // the usage of every command is the one of the binary
    let o = run(&["battery", "-h"]);
    assert_eq!(code(&o), 0, "{}", err(&o));
    assert_eq!(Some(out(&o).as_str()), tpp_api::usage("battery-status"));
}

#[test]
fn the_session_group_is_part_of_the_binary() {
    let fx = Fixture::new("session");
    // No socket: the session command reports it and exits with status 1
    let o = fx
        .command(&["session", "list"])
        .env("TPP_SESSION_SOCKET", "tpp-test-no-such-socket")
        .output_retry()
        .unwrap();
    assert_eq!(code(&o), 1, "{}", err(&o));
    // --help and usage errors come from the session code, status 0 and 2
    let o = fx.run(&["session", "--help"]);
    assert_eq!(code(&o), 0);
    assert!(out(&o).starts_with("usage: tpp-session "), "{}", out(&o));
    assert_eq!(out(&fx.run(&["help", "session"])), out(&o));
    assert_eq!(code(&fx.run(&["session", "--nope"])), 2);
    // it is listed and completed next to the table groups
    assert!(out(&fx.run(&["--help"])).contains("  session "));
    let names = out(&fx.run(&["--command-names"]));
    let names: Vec<&str> = names.lines().collect();
    assert!(
        names.contains(&"session") && names.contains(&"wifi scan") && names.contains(&"battery"),
        "{names:?}"
    );
    let (_, names) = complete(&fx, &["s"]);
    assert!(names.contains(&"session".to_string()));
    let (_, subs) = complete(&fx, &["session", ""]);
    assert!(
        subs.iter().any(|s| s == "list") && subs.iter().any(|s| s == "focus"),
        "{subs:?}"
    );
    // a plugin of that name does not shadow it
    fx.plugin("session", "echo plugin");
    assert!(out(&fx.run(&["session", "--help"])).starts_with("usage: tpp-session "));
    assert!(!out(&fx.run(&["--help"])).contains("Plugins:"));
}

#[test]
fn tpp_session_is_the_same_binary() {
    let fx = Fixture::new("argv0");
    let bin = fx.dir.join("usr/bin/tpp-session");
    std::os::unix::fs::symlink(env!("CARGO_BIN_EXE_tpp"), &bin).unwrap();
    let run = |args: &[&str]| -> Output {
        Command::new(&bin)
            .args(args)
            .env_clear()
            .env("PATH", "/usr/bin:/bin")
            .stdin(Stdio::null())
            .output()
            .unwrap()
    };
    let o = run(&["--help"]);
    assert_eq!(code(&o), 0);
    assert!(out(&o).starts_with("usage: tpp-session "));
    assert_eq!(code(&run(&["--nope"])), 2);
    assert_eq!(code(&run(&[])), 2);
    // the other commands are not reachable under this name
    assert_eq!(code(&run(&["battery"])), 2);
}

#[test]
fn every_command_answers_help_from_the_binary() {
    // No app and no file is needed for the usage text of any command
    let fx = Fixture::new("usage");
    let names = out(&fx.run(&["--command-names"]));
    let mut seen = 0;
    for name in names.lines().filter(|n| *n != "session") {
        let words: Vec<&str> = name.split(' ').collect();
        let mut args = words.clone();
        args.push("--help");
        let o = fx
            .command(&args)
            .env("CLICOLOR_FORCE", "1")
            .output_retry()
            .unwrap();
        assert!(out(&o).contains("\x1b["), "{name}");
        assert_eq!(code(&o), 0, "{name}: {}", err(&o));
        assert!(!out(&o).is_empty(), "{name}");
        seen += 1;
    }
    assert!(seen > 50, "{seen}");
}

#[test]
fn dialog_reports_progress_and_keeps_the_answer_on_stdout() {
    let fx = Fixture::new("dialog-feedback");
    let (output, call) = fx.app(
        &["dialog", "show", "text", "--title", "Your name"],
        br#"{"code":-1,"text":"Ada"}"#,
    );
    assert_eq!(code(&output), 0);
    assert_eq!(call.method(), "Dialog");
    assert_eq!(call.extra("input_title"), Some("Your name"));
    assert!(err(&output).contains("Answer on the device"));
    assert!(out(&output).contains("Ada"));
    assert!(!out(&output).contains("Opening dialog"));

    let (output, _) = fx.app(&["--json", "dialog"], br#"{"code":-1,"text":"Ada"}"#);
    assert!(err(&output).is_empty());
    assert!(out(&output).contains(r#""text": "Ada""#));
    let output = fx.run(&["dialog", "list"]);
    assert_eq!(code(&output), 0);
    assert!(out(&output).contains("confirm"));

    let rejected = fx.run(&["dialog", "show", "-l"]);
    assert_eq!(code(&rejected), 2);
    assert!(err(&rejected).contains("tpp dialog list"));
}

#[test]
fn valueless_flags_do_not_consume_option_values() {
    let fx = Fixture::new("option-value");
    for args in [
        &["dialog", "show", "--title", "--json"][..],
        &["dialog", "show", "--title=--json"][..],
    ] {
        let (output, call) = fx.app(args, br#"{"text":"Ada"}"#);
        assert_eq!(code(&output), 0);
        assert_eq!(call.extra("input_title"), Some("--json"));
        assert!(err(&output).contains("Opening dialog"));
    }
    let (output, call) = fx.app(
        &["dialog", "show", "--title=Hello", "--json"],
        br#"{"text":"Ada"}"#,
    );
    assert_eq!(call.extra("input_title"), Some("Hello"));
    assert!(err(&output).is_empty());
    assert!(tpp_session::json::parse(&out(&output)).is_ok());
}

#[test]
fn bad_public_arguments_exit_with_failure() {
    let fx = Fixture::new("bad-arguments");
    for args in [
        &["volume", "set", "music", "loud"][..],
        &["volume", "set", "misspelled", "3"],
        &["nfc", "write", "--text"],
        &["nfc", "write", "unexpected"],
        &["dialog", "show", "unknown-widget"],
        &["dialog", "show", "text", "unexpected"],
    ] {
        let output = fx.run(args);
        assert_eq!(code(&output), 2, "{args:?}: {}", err(&output));
        assert!(out(&output).is_empty(), "{args:?}");
        assert!(!err(&output).is_empty(), "{args:?}");
    }
}

#[test]
fn audit_actions() {
    let fx = Fixture::new("audit-actions");
    let (output, call) = fx.app(&["sms", "conversations"], b"[]");
    assert_eq!(code(&output), 0, "{}", err(&output));
    assert!(call.has("--ez", "conversation-list", "true"));
    let (output, call) = fx.app(&["location", "last", "--provider=network"], b"[]");
    assert_eq!(code(&output), 0, "{}", err(&output));
    assert_eq!(call.extra("request"), Some("last"));
    assert_eq!(call.extra("provider"), Some("network"));
}

#[test]
fn unique_prefixes_resolve_commands_and_actions() {
    let fx = Fixture::new("prefixes");
    let (output, call) = fx.app(&["not", "l"], b"[]");
    assert_eq!(code(&output), 0, "{}", err(&output));
    assert_eq!(call.method(), "NotificationList");
    assert_eq!(
        out(&fx.run(&["help", "not", "l"])),
        out(&fx.run(&["notification", "list", "--help"]))
    );
    assert_eq!(
        out(&fx.run(&["sess", "l", "--help"])),
        out(&fx.run(&["session", "list", "--help"]))
    );
    let ambiguous = fx.run(&["s"]);
    assert_eq!(code(&ambiguous), 2);
    assert!(err(&ambiguous).contains("session") && err(&ambiguous).contains("sensor"));
    let ambiguous = fx.run(&["media", "p"]);
    assert_eq!(code(&ambiguous), 2);
    assert!(err(&ambiguous).contains("pause, play"));
    let (_, options) = complete(&fx, &["not", "l", "--"]);
    assert!(options.contains(&"--help".to_owned()));
}
