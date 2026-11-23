//! Parity harness: run the original shell script (tests/oracle) against a fake
//! `libexec/termux-api` and `am`, run the Rust command against a
//! `FakeTransport`, and compare everything observable: the calls made (method,
//! arguments, standard input), standard output, standard error, exit status.

#![allow(dead_code)]

use std::fs;
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tpp_api::testing::{run_with, Answer, FakeTransport, Outcome, Recorded};

const FAKE_API: &str = r#"#!/bin/sh
# records "$0 name args..." as call.N.argv (NUL separated) and stdin as
# call.N.stdin, then answers with reply.N (else reply.last)
d="$TPP_FAKE_DIR"
n=0
while [ -e "$d/call.$n.argv" ]; do n=$((n+1)); done
: > "$d/call.$n.argv"
for a in "$@"; do printf '%s\0' "$a" >> "$d/call.$n.argv"; done
cat > "$d/call.$n.stdin"
r="$d/reply.$n"
[ -e "$r" ] || r="$d/reply.last"
[ -e "$r" ] && cat "$r"
exit 0
"#;

const FAKE_AM: &str = "#!/bin/sh\nexec \"$TPP_FAKE_PREFIX/libexec/termux-api\" am \"$@\"\n";

/// A scratch prefix with the shells, the fakes and the substituted scripts.
pub struct Harness {
    pub root: PathBuf,
}

fn which(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|d| d.join(name))
        .find(|p| p.is_file())
}

fn oracle_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/oracle")
}

// A parallel fork can inherit a writable script descriptor until exec.
// Linux rejects executing a script while any process holds it open for writing.
static EXEC_FIXTURES: Mutex<()> = Mutex::new(());

pub fn spawn_fixture_command(command: &mut Command) -> std::io::Result<Child> {
    let _guard = EXEC_FIXTURES.lock().unwrap();
    command.spawn()
}

fn write_exec(path: &Path, content: &str) {
    let _guard = EXEC_FIXTURES.lock().unwrap();
    fs::write(path, content).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
}

impl Harness {
    /// None (the test is skipped) when the system has no `sh` and `bash`.
    pub fn new(tag: &str) -> Option<Harness> {
        let sh = which("sh")?;
        let bash = which("bash")?;
        let root = std::env::temp_dir().join(format!("tpp-api-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&root);
        for d in ["bin", "libexec", "scripts", "work", "tmp"] {
            fs::create_dir_all(root.join(d)).unwrap();
        }
        std::os::unix::fs::symlink(sh, root.join("bin/sh")).unwrap();
        std::os::unix::fs::symlink(bash, root.join("bin/bash")).unwrap();
        write_exec(&root.join("libexec/termux-api"), FAKE_API);
        write_exec(&root.join("bin/am"), FAKE_AM);
        let prefix = root.to_str().unwrap().to_string();
        for entry in fs::read_dir(oracle_dir()).unwrap() {
            let entry = entry.unwrap();
            let name = entry.file_name().to_string_lossy().into_owned();
            let imp = name
                .strip_prefix("termux-")
                .and_then(|n| n.strip_suffix(".in"))
                .unwrap_or_else(|| panic!("odd oracle file {name}"))
                .to_string();
            let text = fs::read_to_string(entry.path()).unwrap();
            let text = text
                .replace("@TERMUX_PREFIX@", &prefix)
                .replace("@TERMUX__PREFIX@", &prefix)
                .replace("@TERMUX_ENV__S_TERMUX_API@", "dev.pschmitt.termux.api")
                .replace("@TERMUX_ENV__S_TERMUX@", "dev.pschmitt.termux")
                .replace("@TERMUX_API_PKG__VERSION@", "0.60.0");
            write_exec(&root.join("scripts").join(imp), &text);
        }
        Some(Harness { root })
    }

    pub fn work_dir(&self) -> PathBuf {
        self.root.join("work")
    }

    /// Run the original script; the calls it made and its outcome.
    pub fn oracle(
        &self,
        imp: &str,
        args: &[&str],
        stdin: &[u8],
        replies: &[&str],
    ) -> (Outcome, Vec<Recorded>) {
        let dir = self.root.join("run");
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        for (i, r) in replies.iter().enumerate() {
            fs::write(dir.join(format!("reply.{i}")), r).unwrap();
        }
        if let Some(last) = replies.last() {
            fs::write(dir.join("reply.last"), last).unwrap();
        }
        let sys_path = std::env::var("PATH").unwrap_or_default();
        let mut command = Command::new(self.root.join("scripts").join(imp));
        command
            .args(args)
            .current_dir(self.work_dir())
            .env_clear()
            .env("PATH", format!("{}/bin:{}", self.root.display(), sys_path))
            .env("TPP_FAKE_DIR", &dir)
            .env("TPP_FAKE_PREFIX", &self.root)
            .env("TMPDIR", self.root.join("tmp"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        let mut child = spawn_fixture_command(&mut command).unwrap();
        let mut sin = child.stdin.take().unwrap();
        let data = stdin.to_vec();
        let writer = std::thread::spawn(move || {
            let _ = sin.write_all(&data);
        });
        let out = child.wait_with_output().unwrap();
        let _ = writer.join();
        let mut calls = Vec::new();
        for n in 0.. {
            let argv = match fs::read(dir.join(format!("call.{n}.argv"))) {
                Ok(a) => a,
                Err(_) => break,
            };
            let mut parts: Vec<String> = argv
                .split(|b| *b == 0)
                .map(|p| String::from_utf8_lossy(p).into_owned())
                .collect();
            parts.pop(); // after the last NUL
            let method = if parts.is_empty() {
                String::new()
            } else {
                parts.remove(0)
            };
            let stdin = fs::read(dir.join(format!("call.{n}.stdin"))).unwrap_or_default();
            calls.push(Recorded {
                method,
                args: parts,
                stdin,
            });
        }
        (
            Outcome {
                status: out.status.code().unwrap_or(-1),
                stdout: out.stdout,
                stderr: out.stderr,
            },
            calls,
        )
    }

    /// The Rust command in the same situation (the work directory as cwd).
    pub fn rust(
        &self,
        imp: &str,
        args: &[&str],
        stdin: &[u8],
        replies: &[&str],
    ) -> (Outcome, Vec<Recorded>) {
        let answers: Vec<Answer> = replies.iter().map(|r| Answer::text(r)).collect();
        let t = FakeTransport::with_answers(answers);
        let _guard = CWD.lock().unwrap_or_else(|e| e.into_inner());
        std::env::set_current_dir(self.work_dir()).unwrap();
        let o = run_with(imp, args, stdin, &t, &[]);
        std::env::set_current_dir(env!("CARGO_MANIFEST_DIR")).unwrap();
        (o, t.calls())
    }
}

static CWD: std::sync::Mutex<()> = std::sync::Mutex::new(());

impl Drop for Harness {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

/// One scenario.
pub struct Case<'a> {
    pub imp: &'a str,
    pub args: &'a [&'a str],
    pub stdin: &'a [u8],
    /// the answers of the app, one per call (the last repeats)
    pub replies: &'a [&'a str],
}

impl<'a> Case<'a> {
    pub fn new(imp: &'a str, args: &'a [&'a str]) -> Self {
        Case {
            imp,
            args,
            stdin: b"",
            replies: &[""],
        }
    }
    pub fn stdin(mut self, s: &'a [u8]) -> Self {
        self.stdin = s;
        self
    }
    pub fn replies(mut self, r: &'a [&'a str]) -> Self {
        self.replies = r;
        self
    }
}

/// Assert that the Rust command behaves exactly like the script; returns the
/// Rust outcome and calls for further checks.
pub fn parity(h: &Harness, c: Case) -> (Outcome, Vec<Recorded>) {
    let (so, sc) = h.oracle(c.imp, c.args, c.stdin, c.replies);
    let (ro, rc) = h.rust(c.imp, c.args, c.stdin, c.replies);
    let label = format!(
        "{} {:?} stdin={:?}",
        c.imp,
        c.args,
        String::from_utf8_lossy(c.stdin)
    );
    assert_eq!(
        sc, rc,
        "calls differ (left: script, right: rust) for {label}"
    );
    assert_eq!(
        so.stdout_str(),
        ro.stdout_str(),
        "stdout differs (left: script, right: rust) for {label}"
    );
    assert_eq!(
        so.stderr_str(),
        ro.stderr_str(),
        "stderr differs (left: script, right: rust) for {label}"
    );
    assert_eq!(
        so.status, ro.status,
        "exit status differs (left: script, right: rust) for {label}"
    );
    (ro, rc)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod plugin;
