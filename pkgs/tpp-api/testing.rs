//! Helpers to run commands without an app: a transport that records the calls
//! and answers from a script.

use crate::call::Call;
use crate::transport::{Reply, Transport};
use crate::{Env, Io};
use std::collections::VecDeque;
use std::ffi::OsString;
use std::io::{self, Read, Write};
use std::sync::Mutex;

/// A call as the app would have received it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Recorded {
    pub method: String,
    pub args: Vec<String>,
    /// everything the call forwarded on standard input
    pub stdin: Vec<u8>,
}

/// One scripted answer.
#[derive(Debug, Clone, Default)]
pub struct Answer {
    pub output: Vec<u8>,
    /// stand-in for a descriptor passed by the app
    pub fd: Option<i32>,
    /// make the call fail with this message
    pub error: Option<String>,
}

impl Answer {
    pub fn text(s: &str) -> Self {
        Answer {
            output: s.as_bytes().to_vec(),
            ..Default::default()
        }
    }
}

/// Records every call and answers from a queue (the last answer repeats;
/// no answer means empty output).
#[derive(Default)]
pub struct FakeTransport {
    answers: Mutex<VecDeque<Answer>>,
    calls: Mutex<Vec<Recorded>>,
}

impl FakeTransport {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn with_answers(answers: Vec<Answer>) -> Self {
        FakeTransport {
            answers: Mutex::new(answers.into()),
            calls: Mutex::new(Vec::new()),
        }
    }
    /// A transport that always answers `text`.
    pub fn replying(text: &str) -> Self {
        Self::with_answers(vec![Answer::text(text)])
    }
    pub fn calls(&self) -> Vec<Recorded> {
        self.calls.lock().unwrap().clone()
    }
}

impl Transport for FakeTransport {
    fn call(
        &self,
        call: &Call,
        mut stdin: Box<dyn Read + Send>,
        out: &mut dyn Write,
        _err: &mut dyn Write,
        _env: &Env,
    ) -> io::Result<Reply> {
        let mut input = Vec::new();
        let _ = stdin.read_to_end(&mut input);
        self.calls.lock().unwrap().push(Recorded {
            method: call.method.clone(),
            args: call.args.clone(),
            stdin: input,
        });
        let answer = {
            let mut q = self.answers.lock().unwrap();
            if q.len() > 1 {
                q.pop_front().unwrap()
            } else {
                q.front().cloned().unwrap_or_default()
            }
        };
        if let Some(e) = answer.error {
            return Err(io::Error::other(e));
        }
        out.write_all(&answer.output)?;
        Ok(Reply { fd: answer.fd })
    }

    /// Records the program as a call whose method is its name; answers like
    /// [`Transport::call`] does.
    fn run_program(
        &self,
        program: &str,
        args: &[String],
        _env: &Env,
        out: &mut dyn Write,
        _err: &mut dyn Write,
    ) -> io::Result<i32> {
        self.calls.lock().unwrap().push(Recorded {
            method: program.to_string(),
            args: args.to_vec(),
            stdin: Vec::new(),
        });
        let answer = self.next_answer();
        out.write_all(&answer.output)?;
        Ok(0)
    }
}

impl FakeTransport {
    fn next_answer(&self) -> Answer {
        let mut q = self.answers.lock().unwrap();
        if q.len() > 1 {
            q.pop_front().unwrap()
        } else {
            q.front().cloned().unwrap_or_default()
        }
    }
}

/// What a command run produced.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Outcome {
    pub status: i32,
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
}

impl Outcome {
    pub fn stdout_str(&self) -> String {
        String::from_utf8_lossy(&self.stdout).into_owned()
    }
    pub fn stderr_str(&self) -> String {
        String::from_utf8_lossy(&self.stderr).into_owned()
    }
}

/// Run `imp` with `args` and `stdin` against `transport` with an empty
/// environment plus `env`. Panics if `imp` is unknown.
pub fn run_with(
    imp: &str,
    args: &[&str],
    stdin: &[u8],
    transport: &dyn Transport,
    env: &[(&str, &str)],
) -> Outcome {
    let mut stdout = Vec::new();
    let mut stderr = Vec::new();
    let mut e = Env::empty();
    for (k, v) in env {
        e.set(k, v);
    }
    let args: Vec<OsString> = args.iter().map(OsString::from).collect();
    let status = {
        let mut io = Io {
            stdin: Some(Box::new(io::Cursor::new(stdin.to_vec()))),
            stdout: &mut stdout,
            stderr: &mut stderr,
            env: e,
            transport,
        };
        crate::run(imp, &args, &mut io).unwrap_or_else(|| panic!("unknown impl {imp}"))
    };
    Outcome {
        status,
        stdout,
        stderr,
    }
}

/// [`run_with`] against a [`FakeTransport`] that answers `reply`; returns the
/// outcome and the calls made.
pub fn run_fake(imp: &str, args: &[&str], stdin: &[u8], reply: &str) -> (Outcome, Vec<Recorded>) {
    let t = FakeTransport::replying(reply);
    let o = run_with(imp, args, stdin, &t, &[]);
    (o, t.calls())
}
