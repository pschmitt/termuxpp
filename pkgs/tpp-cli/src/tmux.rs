//! A shell-owned SSH attachment and a multiplexed tmux channel.
use std::ffi::{OsStr, OsString};
use std::fs::{self, File};
use std::io::{self, Read};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::DirBuilderExt;
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::mpsc::{self, Receiver};
use std::thread;
use std::time::{Duration, Instant};

use tpp_session::client::{Exchange, SocketExchange, MAX_REQUEST};
use tpp_session::json::Value;

const APP_TIMEOUT: Duration = Duration::from_secs(2);
const SSH_TIMEOUT: Duration = Duration::from_secs(3);
const POLL_INTERVAL: Duration = Duration::from_millis(750);
const HELP: &str = "Usage: tpp tmux attach SCRIPT -- SSH_ARGS...
       tpp tmux switcher [--json]

  attach     Run the SSH wrapper's remote script and publish tmux windows
  switcher   Open the window picker for the visible terminal
";

pub fn run(args: &[OsString], json: bool) -> i32 {
    let args: Vec<&OsStr> = args.iter().map(OsString::as_os_str).collect();
    match args.as_slice() {
        [] => help(),
        [word] | [_, word] if matches!(word.to_str(), Some("help" | "-h" | "--help")) => help(),
        [action, script, separator, ssh_args @ ..]
            if *action == "attach" && *separator == "--" && !ssh_args.is_empty() && !json =>
        {
            match attach(script, ssh_args) {
                Ok(code) => code,
                Err(error) => {
                    err!("tmux attach: {error}");
                    1
                }
            }
        }
        [action, flags @ ..] if *action == "switcher" => {
            let mut words = vec!["tmux-switcher".to_owned()];
            words.extend(flags.iter().map(|arg| arg.to_string_lossy().into_owned()));
            crate::ui::run(&words, json)
        }
        _ => {
            err!("expected 'tmux attach SCRIPT -- SSH_ARGS...' or 'tmux switcher'");
            2
        }
    }
}

fn help() -> i32 {
    print!("{}", crate::ctx::color_help(HELP));
    0
}

pub fn complete(words: &[String]) -> Vec<String> {
    let (prefix, candidates) = match words {
        [prefix] => (prefix, &["attach", "switcher", "--help"][..]),
        [action, prefix] if action == "switcher" => (prefix, &["--json", "--help"][..]),
        _ => return Vec::new(),
    };
    candidates
        .iter()
        .filter(|word| word.starts_with(prefix))
        .map(|word| (*word).into())
        .collect()
}

fn shell_command(script: &OsStr) -> OsString {
    use std::os::unix::ffi::OsStringExt;
    let mut quoted = b"sh -c '".to_vec();
    for byte in script.as_bytes() {
        if *byte == b'\'' {
            quoted.extend_from_slice(b"'\\''");
        } else {
            quoted.push(*byte);
        }
    }
    quoted.push(b'\'');
    OsString::from_vec(quoted)
}

struct MuxDirectory(PathBuf);

impl Drop for MuxDirectory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn attach(script: &OsStr, ssh_args: &[&OsStr]) -> io::Result<i32> {
    let Some((attachment, directory)) = Attachment::open() else {
        // Preserve interactive SSH even outside the app or with an older app build.
        return Err(Command::new("ssh")
            .args(ssh_args)
            .arg(shell_command(script))
            .exec());
    };

    let control = directory.0.join("s");
    let mut visible = OsString::from(format!(
        r#"umask_saved=$(umask)
umask 077
ref="${{TMPDIR:-/tmp}}/{marker}"
if mkdir -- "$ref" 2>/dev/null
then
  trap 'rm -f -- "$ref/tty"; rmdir -- "$ref"' EXIT
  trap 'exit 129' HUP
  trap 'exit 143' TERM
  tty > "$ref/tty"
fi
umask "$umask_saved"
(
"#,
        marker = attachment.marker()
    ));
    visible.push(script);
    visible.push("\n)\n");
    let mut control_option = OsString::from("ControlPath=");
    control_option.push(&control);

    let mut child = Command::new("ssh")
        .args(["-o", "ControlMaster=yes", "-o", "ControlPersist=no", "-o"])
        .arg(control_option)
        .args(ssh_args)
        .arg(shell_command(&visible))
        .spawn()?;

    let (stop, stopped) = mpsc::channel();
    let follower = thread::spawn(move || attachment.follow(&control, &stopped));
    let status = child.wait();
    let _ = stop.send(());
    let _ = follower.join();
    status.map(|status| {
        status
            .code()
            .unwrap_or_else(|| 128 + status.signal().unwrap_or(1))
    })
}

struct Attachment {
    terminal: String,
    token: String,
    exchange: SocketExchange,
}

impl Attachment {
    fn open() -> Option<(Self, MuxDirectory)> {
        let terminal = std::env::var("TPP_TERMINAL_SESSION_ID").ok()?;
        if terminal.is_empty() {
            return None;
        }

        let mut random = [0; 16];
        File::open("/dev/urandom")
            .ok()?
            .read_exact(&mut random)
            .ok()?;
        let token: String = random.iter().map(|byte| format!("{byte:02x}")).collect();
        let attachment = Self {
            terminal,
            token,
            exchange: SocketExchange::from_environment(),
        };
        attachment.publish(None)?;
        let path = std::env::temp_dir().join(format!("tppm-{}", &attachment.token[..16]));
        // Reserve space for OpenSSH's temporary socket suffix.
        if path.as_os_str().as_bytes().len() + 2 + 17 >= 108 {
            return None;
        }
        fs::DirBuilder::new().mode(0o700).create(&path).ok()?;
        Some((attachment, MuxDirectory(path)))
    }

    fn marker(&self) -> String {
        format!(".tpp-tmux-{}", self.token)
    }

    fn publish(&self, windows: Option<&[Window<'_>]>) -> Option<Value> {
        let mut args = vec![
            Value::string("tmux-windows"),
            Value::string(&self.terminal),
            Value::string(&self.token),
        ];
        if let Some(windows) = windows {
            // The active window comes first for truncation. Display retained rows by index.
            let mut size = Value::Array(args.clone()).to_compact().len() + 5;
            let mut rows = Vec::new();
            for window in windows {
                let row = window.json();
                size += row.to_compact().len() + usize::from(!rows.is_empty()) * 2;
                if size > MAX_REQUEST {
                    break;
                }
                rows.push((window.index, row));
            }
            rows.sort_by_key(|(index, _)| *index);
            args.push(Value::Array(rows.into_iter().map(|(_, row)| row).collect()));
        }
        let reply = self
            .exchange
            .exchange(&Value::Array(args), APP_TIMEOUT)
            .ok()?;
        reply.get("result").cloned()
    }

    fn follow(&self, control: &Path, stopped: &Receiver<()>) {
        let client = format!(
            r#"ref="${{TMPDIR:-/tmp}}/{}/tty"
[ -r "$ref" ] || exit 1
IFS= read -r attached_tty < "$ref" || exit 1
session=$(tmux list-clients -F '#{{client_tty}} #{{session_id}}' |
    awk -v tty="$attached_tty" '$1 == tty {{ print $2; exit }}')
[ -n "$session" ] || exit 1
"#,
            self.marker()
        );
        let query = format!(
            "{client}printf '%s\\n' \"$session\"\ntmux list-windows -t \"$session\" -F '#{{window_id}} #{{window_index}} #{{window_active}} #{{window_name}}'\n"
        );
        while stopped.try_recv() == Err(mpsc::TryRecvError::Empty) {
            if let Ok(output) = remote(control, &query) {
                let output = String::from_utf8_lossy(&output);
                if let Some((session, windows)) = parse_windows(&output) {
                    if let Some(Value::String(id)) = self.publish(Some(&windows)) {
                        if windows.iter().any(|window| window.id == id) {
                            let action = format!(
                                "{client}[ \"$session\" = '{session}' ] || exit 1\ntmux select-window -t \"$session:{id}\""
                            );
                            let _ = remote(control, &action);
                        }
                    }
                }
            }
            if stopped.recv_timeout(POLL_INTERVAL) != Err(mpsc::RecvTimeoutError::Timeout) {
                break;
            }
        }
        self.publish(None);
    }
}

fn remote(control: &Path, script: &str) -> io::Result<Vec<u8>> {
    // Forbid a fresh connection if the master socket has not appeared or has closed.
    let mut child = Command::new("ssh")
        .args([
            "-F",
            "/dev/null",
            "-T",
            "-o",
            "ControlMaster=no",
            "-o",
            "ProxyCommand=false",
            "-S",
        ])
        .arg(control)
        .arg("termuxpp-mux")
        .arg(shell_command(OsStr::new(script)))
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let stdout = child.stdout.take().expect("piped stdout");
    let reader = thread::spawn(move || {
        let mut output = Vec::new();
        stdout.take(256 * 1024).read_to_end(&mut output)?;
        Ok(output)
    });
    let deadline = Instant::now() + SSH_TIMEOUT;
    loop {
        match child.try_wait() {
            Ok(Some(status)) if status.success() => {
                return reader
                    .join()
                    .map_err(|_| io::Error::other("SSH output reader failed"))?;
            }
            Ok(Some(_)) => break,
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(25)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                break;
            }
        }
    }
    let _ = reader.join();
    Err(io::Error::other("tmux control channel unavailable"))
}

struct Window<'a> {
    id: &'a str,
    index: u32,
    name: &'a str,
    active: bool,
}

impl Window<'_> {
    fn json(&self) -> Value {
        let title: String = self.name.chars().take(100).collect();
        Value::Object(vec![
            ("id".into(), Value::string(self.id)),
            (
                "label".into(),
                Value::string(format!("{}  {title}", self.index)),
            ),
            ("active".into(), Value::Bool(self.active)),
        ])
    }
}

fn valid_id(value: &str, marker: char) -> bool {
    value.strip_prefix(marker).is_some_and(|digits| {
        !digits.is_empty() && digits.bytes().all(|byte| byte.is_ascii_digit())
    })
}

fn parse_windows(output: &str) -> Option<(&str, Vec<Window<'_>>)> {
    let (session, rows) = output.split_once('\n')?;
    if !valid_id(session, '$') {
        return None;
    }
    let mut windows: Vec<_> = rows
        .lines()
        .filter_map(|row| {
            let fields: Vec<_> = row.splitn(4, ' ').collect();
            let [id, index, active @ ("0" | "1"), name] = fields.as_slice() else {
                return None;
            };
            if !valid_id(id, '@') {
                return None;
            }
            Some(Window {
                id,
                index: index.parse().ok()?,
                name,
                active: *active == "1",
            })
        })
        .collect();
    windows.sort_by_key(|window| (!window.active, window.index));
    Some((session, windows))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_unicode_and_prioritizes_the_active_window() {
        let (session, windows) =
            parse_windows("$4\n@1 1 0 first\n@15 2 1 é spaces\n@3; 3 1 invalid\n").unwrap();
        assert_eq!(session, "$4");
        assert_eq!(windows.len(), 2);
        assert_eq!(windows[0].id, "@15");
        assert_eq!(windows[0].name, "é spaces");
        assert!(parse_windows("$4; bad\n@1 1 0 first").is_none());
    }

    #[test]
    fn shell_command_keeps_remote_script_as_one_argument() {
        let script = "printf '%s\\n' \"héllo world\"\nprintf '%s' '$VALUE'";
        let output = Command::new("sh")
            .arg("-c")
            .arg(shell_command(OsStr::new(script)))
            .output()
            .unwrap();
        assert!(output.status.success());
        assert_eq!(output.stdout, "héllo world\n$VALUE".as_bytes());
    }
}
