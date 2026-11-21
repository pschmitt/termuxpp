//! The session control protocol of the TermuxPP app.
//!
//! The app listens on the abstract Unix socket `dev.pschmitt.termux.sessions`. A request is one
//! line holding a JSON array (at most 4096 bytes, newline included); the reply is one line holding
//! a JSON object (at most 1 MiB).

use std::fmt;
use std::io::{self, BufRead, BufReader, Read, Write};
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(not(target_os = "android"))]
use std::os::linux::net::SocketAddrExt;
use std::os::unix::net::{SocketAddr, UnixStream};
use std::time::Duration;

use crate::json::{self, Value};

/// Name of the abstract socket the app listens on.
pub const SOCKET_NAME: &str = "dev.pschmitt.termux.sessions";
/// Environment variable that replaces [`SOCKET_NAME`]; for tests only.
pub const SOCKET_ENV: &str = "TPP_SESSION_SOCKET";
/// Largest request line, newline included.
pub const MAX_REQUEST: usize = 4096;
/// Largest reply line.
pub const MAX_REPLY: u64 = 1024 * 1024;
/// How long a command waits for the app.
pub const COMMAND_TIMEOUT: Duration = Duration::from_secs(5);
/// How long shell completion waits for the app: a slow app must not freeze the prompt.
pub const COMPLETION_TIMEOUT: Duration = Duration::from_millis(250);

/// Why an exchange failed.
#[derive(Debug, PartialEq)]
pub enum ExchangeError {
    /// Nobody listens on the socket: the app is not running.
    Unreachable(String),
    /// Anything else, with the message to show.
    Failed(String),
}

impl fmt::Display for ExchangeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ExchangeError::Unreachable(message) | ExchangeError::Failed(message) => {
                f.write_str(message)
            }
        }
    }
}

/// One request and its reply.
pub trait Exchange {
    fn exchange(&self, request: &Value, timeout: Duration) -> Result<Value, ExchangeError>;
}

/// The real thing: talks to the app over the abstract socket.
pub struct SocketExchange {
    name: String,
}

impl SocketExchange {
    pub fn new(name: impl Into<String>) -> Self {
        SocketExchange { name: name.into() }
    }

    /// The app's socket, or the one named by [`SOCKET_ENV`].
    pub fn from_environment() -> Self {
        match std::env::var(SOCKET_ENV) {
            Ok(name) if !name.is_empty() => Self::new(name),
            _ => Self::new(SOCKET_NAME),
        }
    }
}

impl Exchange for SocketExchange {
    fn exchange(&self, request: &Value, timeout: Duration) -> Result<Value, ExchangeError> {
        let mut payload = request.to_compact().into_bytes();
        payload.push(b'\n');
        if payload.len() > MAX_REQUEST {
            return Err(ExchangeError::Failed(
                "session command is too long".to_string(),
            ));
        }
        let address = SocketAddr::from_abstract_name(self.name.as_bytes())
            .map_err(|error| ExchangeError::Failed(python_style(&error)))?;
        let mut stream = UnixStream::connect_addr(&address)
            .map_err(|error| ExchangeError::Unreachable(python_style(&error)))?;
        let io_error = |error: io::Error| ExchangeError::Failed(python_style(&error));
        stream.set_read_timeout(Some(timeout)).map_err(io_error)?;
        stream.set_write_timeout(Some(timeout)).map_err(io_error)?;
        stream.write_all(&payload).map_err(io_error)?;
        let mut line = Vec::new();
        BufReader::new(stream.take(MAX_REPLY))
            .read_until(b'\n', &mut line)
            .map_err(io_error)?;
        let text = String::from_utf8(line).map_err(|error| {
            ExchangeError::Failed(format!(
                "'utf-8' codec can't decode byte: {}",
                error.utf8_error()
            ))
        })?;
        json::parse(&text).map_err(ExchangeError::Failed)
    }
}

/// Formats an I/O error the way Python's `OSError` prints, because that is what users have seen:
/// `[Errno 111] Connection refused`.
pub fn python_style(error: &io::Error) -> String {
    if matches!(
        error.kind(),
        io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
    ) {
        return "timed out".to_string();
    }
    match error.raw_os_error() {
        Some(code) => {
            let text = error.to_string();
            let suffix = format!(" (os error {code})");
            let description = text.strip_suffix(&suffix).unwrap_or(&text);
            format!("[Errno {code}] {description}")
        }
        None => error.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn os_errors_look_like_pythons() {
        let refused = io::Error::from_raw_os_error(111);
        assert_eq!(python_style(&refused), "[Errno 111] Connection refused");
        assert_eq!(
            python_style(&io::Error::from(io::ErrorKind::TimedOut)),
            "timed out"
        );
        assert_eq!(python_style(&io::Error::other("boom")), "boom");
    }

    #[test]
    fn an_absent_listener_is_unreachable() {
        let exchange = SocketExchange::new("tpp-session-test-nobody-listens-here");
        let request = Value::Array(vec![Value::string("list")]);
        match exchange.exchange(&request, Duration::from_millis(200)) {
            Err(ExchangeError::Unreachable(message)) => {
                assert!(message.starts_with("[Errno 111]"), "{message}")
            }
            other => panic!("unexpected result: {other:?}"),
        }
    }

    #[test]
    fn oversized_requests_are_refused_before_connecting() {
        let exchange = SocketExchange::new("tpp-session-test-nobody-listens-here");
        let request = Value::Array(vec![
            Value::string("rename"),
            Value::string("id"),
            Value::string("x".repeat(5000)),
        ]);
        assert_eq!(
            exchange.exchange(&request, Duration::from_millis(200)),
            Err(ExchangeError::Failed(
                "session command is too long".to_string()
            ))
        );
    }
}
