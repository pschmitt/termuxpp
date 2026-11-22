//! The API commands: the crate `tpp-api` is part of the binary, so every
//! command of the table runs in this process and talks to the app itself.

use std::ffi::OsString;
use std::io::{self, Read, Write};

use tpp_api::{Call, Env, Io, Native, Reply, Transport};

/// Run `imp` on the standard streams of the process; its exit status.
pub fn run(imp: &str, args: &[OsString]) -> i32 {
    tpp_api::run_stdio(imp, args).unwrap_or_else(|| unknown(imp))
}

/// Run `imp` with its standard output written to `stdout`.
pub fn run_to(imp: &str, args: &[OsString], stdout: &mut dyn Write, human: bool) -> i32 {
    let mut stderr = io::stderr();
    let transport = Progress { human };
    let mut io = Io {
        stdin: Some(Box::new(io::stdin())),
        stdout,
        stderr: &mut stderr,
        env: Env::process(),
        transport: &transport,
    };
    tpp_api::run(imp, args, &mut io).unwrap_or_else(|| unknown(imp))
}

fn unknown(imp: &str) -> i32 {
    err!("{imp}: not an API command of this binary");
    127
}

struct Progress {
    human: bool,
}

impl Transport for Progress {
    fn call(
        &self,
        call: &Call,
        stdin: Box<dyn Read + Send>,
        out: &mut dyn Write,
        err: &mut dyn Write,
        env: &Env,
    ) -> io::Result<Reply> {
        if self.human {
            let message = match call.method.as_str() {
                "Dialog" => Some("Opening dialog. Answer on the device…"),
                "Location" => Some("Waiting for location…"),
                "Fingerprint" => Some("Waiting for fingerprint authentication…"),
                "SpeechToText" => Some("Listening for speech…"),
                "StorageGet" => Some("Waiting for you to pick a file…"),
                "Nfc" => Some("Waiting for an NFC tag…"),
                "NotificationList" => Some("Reading notifications…"),
                _ => None,
            };
            if let Some(message) = message {
                let _ = writeln!(err, "{message}");
            }
        }
        Native.call(call, stdin, out, err, env)
    }

    fn run_program(
        &self,
        program: &str,
        args: &[String],
        env: &Env,
        out: &mut dyn Write,
        err: &mut dyn Write,
    ) -> io::Result<i32> {
        Native.run_program(program, args, env, out, err)
    }
}
