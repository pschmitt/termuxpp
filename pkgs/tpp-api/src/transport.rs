//! The transport of an API call: the native client of the socket protocol that
//! `termux-api.c` implements, and the hook tests use to replace it.

use crate::call::Call;
use crate::Env;
use std::io::{self, Read, Write};
use std::os::raw::c_int;

/// What a finished call leaves behind.
#[derive(Debug, Default)]
pub struct Reply {
    /// The file descriptor the app passed with its answer (`SCM_RIGHTS`), as
    /// `tpp usb` needs. The caller owns it. Commands that do not expect one
    /// ignore it (it is closed by [`Reply::close_fd`] or at process exit).
    pub fd: Option<c_int>,
}

impl Reply {
    pub fn close_fd(&mut self) {
        if let Some(fd) = self.fd.take() {
            // SAFETY: the descriptor was received by recvmsg and is owned by us.
            unsafe { crate::sys::close(fd) };
        }
    }
}

/// Runs a call: forwards `stdin` to the app, streams the app's output to
/// `out`, and returns the descriptor the app passed, if any.
pub trait Transport {
    fn call(
        &self,
        call: &Call,
        stdin: Box<dyn Read + Send>,
        out: &mut dyn Write,
        err: &mut dyn Write,
        env: &Env,
    ) -> io::Result<Reply>;

    /// Run an external program (`am` for the service commands) and give its
    /// exit status; its output goes to `out` and `err`. Tests replace this to
    /// record the invocation.
    fn run_program(
        &self,
        program: &str,
        args: &[String],
        env: &Env,
        out: &mut dyn Write,
        err: &mut dyn Write,
    ) -> io::Result<i32> {
        let output = std::process::Command::new(program)
            .args(args)
            .env_clear()
            .envs(env.iter())
            .stdin(std::process::Stdio::null())
            .output()?;
        out.write_all(&output.stdout)?;
        err.write_all(&output.stderr)?;
        Ok(output.status.code().unwrap_or(128))
    }
}

pub use crate::native::Native;
