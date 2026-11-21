//! A call of an API method: what `termux-api METHOD ARGS...` takes.

use std::fmt::Display;

/// Where the standard input of a call comes from.
#[derive(Debug, Clone, Default)]
pub enum Stdin {
    /// The standard input of the command (what `termux-api` itself does by
    /// default). Only the first call of a command gets it.
    #[default]
    Inherit,
    /// Nothing: the app sees end of input at once.
    None,
    /// These bytes (`echo "$X" | termux-api ...`).
    Bytes(Vec<u8>),
}

/// `termux-api METHOD [extras...]`: the method name and the `am`-style extras
/// that follow it (`--es key value`, `--ei key 3`, `--ez key true`, ...).
#[derive(Debug, Clone)]
pub struct Call {
    pub method: String,
    pub args: Vec<String>,
    pub stdin: Stdin,
}

impl Call {
    pub fn new(method: &str) -> Self {
        Call {
            method: method.to_string(),
            args: Vec::new(),
            stdin: Stdin::Inherit,
        }
    }

    fn extra(mut self, kind: &str, key: &str, value: impl Display) -> Self {
        self.args.push(kind.to_string());
        self.args.push(key.to_string());
        self.args.push(value.to_string());
        self
    }

    /// `--es key value`
    pub fn es(self, key: &str, value: impl Display) -> Self {
        self.extra("--es", key, value)
    }
    /// `--ei key value`
    pub fn ei(self, key: &str, value: impl Display) -> Self {
        self.extra("--ei", key, value)
    }
    /// `--ez key value` (`true`/`false`)
    pub fn ez(self, key: &str, value: impl Display) -> Self {
        self.extra("--ez", key, value)
    }
    /// `--ef key value`
    pub fn ef(self, key: &str, value: impl Display) -> Self {
        self.extra("--ef", key, value)
    }
    /// `--esa key a,b,c`
    pub fn esa(self, key: &str, value: impl Display) -> Self {
        self.extra("--esa", key, value)
    }
    /// `--eia key 1,2,3`
    pub fn eia(self, key: &str, value: impl Display) -> Self {
        self.extra("--eia", key, value)
    }
    /// `-e key value` (the short form of `--es`)
    pub fn e(self, key: &str, value: impl Display) -> Self {
        self.extra("-e", key, value)
    }
    /// One more raw argument, as a script would write it.
    pub fn arg(mut self, a: impl Into<String>) -> Self {
        self.args.push(a.into());
        self
    }
    /// Several more raw arguments.
    pub fn args<I, S>(mut self, a: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.args.extend(a.into_iter().map(Into::into));
        self
    }
    /// Choose the standard input of the call.
    pub fn stdin(mut self, s: Stdin) -> Self {
        self.stdin = s;
        self
    }
    /// `echo -n TEXT | termux-api ...`
    pub fn stdin_bytes(self, b: impl Into<Vec<u8>>) -> Self {
        self.stdin(Stdin::Bytes(b.into()))
    }
}
