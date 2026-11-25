//! A shell-owned SSH attachment and a multiplexed tmux channel.
use std::ffi::{OsStr, OsString};
use std::fs::{self, File};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::DirBuilderExt;
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::{self, Receiver};
use std::thread;
use std::time::{Duration, Instant};

use tpp_session::client::{Exchange, SocketExchange, MAX_REQUEST};
use tpp_session::json::Value;

const APP_TIMEOUT: Duration = Duration::from_secs(2);
const SSH_TIMEOUT: Duration = Duration::from_secs(3);
const POLL_INTERVAL: Duration = Duration::from_millis(750);
/// Covers the app's hold of a `tmux-wait` request (750 ms) with room to spare.
const WAIT_TIMEOUT: Duration = Duration::from_secs(3);
/// After this many broken shells in a row, run each command over its own SSH channel instead.
const SHELL_ATTEMPTS: u32 = 3;
const MAX_OUTPUT: usize = 256 * 1024;
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

    /// Wait for a window chosen in the app. The app holds the request open until there is one.
    fn wait(&self) -> Wait {
        let request = Value::Array(vec![Value::string("tmux-wait"), Value::string(&self.token)]);
        match self.exchange.exchange(&request, WAIT_TIMEOUT) {
            Ok(reply) => match reply.get("result") {
                Some(Value::String(id)) => Wait::Chosen(id.clone()),
                Some(Value::Null) => Wait::Nothing,
                // An app without the request answers with an error.
                _ => Wait::Unsupported,
            },
            Err(_) => Wait::Failed,
        }
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
        // The command of the active pane follows the name after a tab, for the app's program icons.
        let list = "printf '%s\\n' \"$session\"\ntmux list-windows -t \"$session\" -F '#{window_id} #{window_index} #{window_active} #{window_name}\t#{pane_current_command}'\n";
        let query = format!("{client}{list}");
        let mut remote = Remote::new(control);
        // The tmux session and window IDs of the last snapshot, to check a choice against.
        let mut last: Option<(String, Vec<String>)> = None;
        let mut chosen: Option<String> = None;
        let mut waits = true;
        while stopped.try_recv() == Err(mpsc::TryRecvError::Empty) {
            // A choice is switched to and listed again in one request, so the app sees the result at once.
            let script = match (chosen.take(), &last) {
                (Some(id), Some((session, ids))) if ids.contains(&id) => format!(
                    "{client}[ \"$session\" = '{session}' ] || exit 1\ntmux select-window -t \"$session:{id}\"\n{list}"
                ),
                _ => query.clone(),
            };
            if let Ok(output) = remote.run(&script) {
                let output = String::from_utf8_lossy(&output);
                if let Some((session, windows)) = parse_windows(&output) {
                    last = Some((
                        session.to_owned(),
                        windows.iter().map(|window| window.id.to_owned()).collect(),
                    ));
                    // An app without `tmux-wait` hands the choice back with the snapshot instead.
                    if let Some(Value::String(id)) = self.publish(Some(&windows)) {
                        chosen = Some(id);
                        continue;
                    }
                }
            }
            if waits {
                match self.wait() {
                    Wait::Chosen(id) => {
                        chosen = Some(id);
                        continue;
                    }
                    // The app held the request for a while: time to look at tmux again.
                    Wait::Nothing => continue,
                    Wait::Unsupported => waits = false,
                    Wait::Failed => {}
                }
            }
            if stopped.recv_timeout(POLL_INTERVAL) != Err(mpsc::RecvTimeoutError::Timeout) {
                break;
            }
        }
        self.publish(None);
    }
}

enum Wait {
    Chosen(String),
    Nothing,
    Unsupported,
    Failed,
}

/// Runs the helper's tmux commands on the attachment's SSH connection: in one long-lived shell
/// while that works, otherwise over a new channel per command.
struct Remote<'a> {
    control: &'a Path,
    shell: Option<Shell>,
    failures: u32,
}

impl<'a> Remote<'a> {
    fn new(control: &'a Path) -> Self {
        Self {
            control,
            shell: None,
            failures: 0,
        }
    }

    fn run(&mut self, script: &str) -> io::Result<Vec<u8>> {
        if self.failures < SHELL_ATTEMPTS {
            if self.shell.is_none() {
                self.shell = Shell::spawn(self.control).ok();
            }
            if let Some(shell) = self.shell.as_mut() {
                match shell.run(script, SSH_TIMEOUT) {
                    Ok((output, 0)) => {
                        self.failures = 0;
                        return Ok(output);
                    }
                    Ok(_) => return Err(io::Error::other("tmux command failed")),
                    Err(_) => {
                        self.shell = None;
                        self.failures += 1;
                    }
                }
            } else {
                self.failures += 1;
            }
        }
        remote(self.control, script)
    }
}

/// A remote `sh` that reads one request after another from its standard input. Each request costs
/// one round trip on the open connection, without the setup of a new SSH channel.
struct Shell {
    child: Child,
    stdin: ChildStdin,
    lines: Receiver<Vec<u8>>,
    serial: u64,
}

impl Shell {
    fn spawn(control: &Path) -> io::Result<Self> {
        let mut command = mux_command(control);
        command.arg("sh");
        Self::start(command)
    }

    fn start(mut command: Command) -> io::Result<Self> {
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()?;
        let stdin = child.stdin.take().expect("piped stdin");
        let stdout = child.stdout.take().expect("piped stdout");
        let (send, lines) = mpsc::channel();
        thread::spawn(move || {
            let mut reader = BufReader::new(stdout);
            loop {
                let mut line = Vec::new();
                match reader
                    .by_ref()
                    .take(MAX_OUTPUT as u64)
                    .read_until(b'\n', &mut line)
                {
                    Ok(0) | Err(_) => break,
                    Ok(_) if send.send(line).is_err() => break,
                    Ok(_) => {}
                }
            }
        });
        Ok(Self {
            child,
            stdin,
            lines,
            serial: 0,
        })
    }

    /// Run a script in a subshell and return its output and exit status.
    fn run(&mut self, script: &str, timeout: Duration) -> io::Result<(Vec<u8>, i32)> {
        self.serial += 1;
        // A fresh end marker per request, so output left over from an earlier request cannot end this one.
        let end = format!("__tpp_tmux_end_{}", self.serial);
        let request = format!("(\n{script}\n) </dev/null\nprintf '\\n%s %s\\n' '{end}' \"$?\"\n");
        self.stdin.write_all(request.as_bytes())?;
        self.stdin.flush()?;

        let deadline = Instant::now() + timeout;
        let mut output = Vec::new();
        loop {
            let left = deadline.saturating_duration_since(Instant::now());
            let line = self
                .lines
                .recv_timeout(left)
                .map_err(|_| io::Error::other("tmux shell did not answer"))?;
            if let Some(status) = line
                .strip_prefix(end.as_bytes())
                .and_then(|rest| std::str::from_utf8(rest).ok())
                .and_then(|rest| rest.trim().parse().ok())
            {
                // The marker starts on a new line of its own; that newline is not output.
                if output.last() == Some(&b'\n') {
                    output.pop();
                }
                return Ok((output, status));
            }
            output.extend_from_slice(&line);
            if output.len() > MAX_OUTPUT {
                return Err(io::Error::other("tmux shell output is too long"));
            }
        }
    }
}

impl Drop for Shell {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// `ssh` on the attachment's master connection, never a fresh connection if that has closed.
fn mux_command(control: &Path) -> Command {
    let mut command = Command::new("ssh");
    command
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
        .arg("termuxpp-mux");
    command
}

fn remote(control: &Path, script: &str) -> io::Result<Vec<u8>> {
    let mut child = mux_command(control)
        .arg(shell_command(OsStr::new(script)))
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let stdout = child.stdout.take().expect("piped stdout");
    let reader = thread::spawn(move || {
        let mut output = Vec::new();
        stdout.take(MAX_OUTPUT as u64).read_to_end(&mut output)?;
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
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(5)),
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
    /// The command of the window's active pane, empty when tmux did not name one.
    program: &'a str,
    active: bool,
}

impl Window<'_> {
    fn json(&self) -> Value {
        let title: String = self.name.chars().take(100).collect();
        let mut fields = vec![
            ("id".into(), Value::string(self.id)),
            (
                "label".into(),
                Value::string(format!("{}  {title}", self.index)),
            ),
            ("active".into(), Value::Bool(self.active)),
        ];
        if !self.program.is_empty() {
            let program: String = self.program.chars().take(64).collect();
            fields.push(("program".into(), Value::string(program)));
        }
        Value::Object(fields)
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
            let [id, index, active @ ("0" | "1"), rest] = fields.as_slice() else {
                return None;
            };
            if !valid_id(id, '@') {
                return None;
            }
            let (name, program) = rest.rsplit_once('\t').unwrap_or((rest, ""));
            Some(Window {
                id,
                index: index.parse().ok()?,
                name,
                program,
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
            parse_windows("$4\n@1 1 0 first\tzsh\n@15 2 1 é spaces\tclaude\n@3; 3 1 invalid\n").unwrap();
        assert_eq!(session, "$4");
        assert_eq!(windows.len(), 2);
        assert_eq!(windows[0].id, "@15");
        assert_eq!(windows[0].name, "é spaces");
        assert_eq!(windows[0].program, "claude");
        assert_eq!(windows[1].program, "zsh");
        // A name without the command, as from a helper that did not ask for it.
        let (_, windows) = parse_windows("$4\n@1 1 1 plain name\n").unwrap();
        assert_eq!((windows[0].name, windows[0].program), ("plain name", ""));
        assert!(parse_windows("$4; bad\n@1 1 0 first").is_none());
    }

    #[test]
    fn shell_runs_requests_one_after_another_and_keeps_their_status() {
        let mut shell = Shell::start(Command::new("sh")).unwrap();
        let (output, status) = shell.run("printf 'a\\nb'", SSH_TIMEOUT).unwrap();
        assert_eq!((output.as_slice(), status), (&b"a\nb"[..], 0));
        let (output, status) = shell.run("printf '$1 x\\n'\nexit 3", SSH_TIMEOUT).unwrap();
        assert_eq!((output.as_slice(), status), (&b"$1 x\n"[..], 3));
        // A request cannot read the next one from the shell's input.
        let (output, _) = shell.run("cat", SSH_TIMEOUT).unwrap();
        assert!(output.is_empty());
        assert_eq!(shell.run("echo ok", SSH_TIMEOUT).unwrap().0, b"ok\n");
    }

    #[test]
    fn shell_reports_a_request_that_does_not_finish_in_time() {
        let mut shell = Shell::start(Command::new("sh")).unwrap();
        assert!(shell.run("sleep 2", Duration::from_millis(100)).is_err());
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
