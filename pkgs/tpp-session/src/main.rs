//! A test-only wrapper that runs `tpp session` on its own, for the integration tests. The shipped
//! program is `tpp`, which links the library.

use std::io::{IsTerminal, Write};
use std::process::ExitCode;

use tpp_session::cli::{self, Environment};
use tpp_session::client::SocketExchange;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args_os()
        .skip(1)
        .map(|arg| arg.to_string_lossy().into_owned())
        .collect();
    let stdout = std::io::stdout();
    let environment = Environment {
        color: stdout.is_terminal() && std::env::var_os("NO_COLOR").is_none(),
    };
    let exchange = SocketExchange::from_environment();
    let mut out = stdout.lock();
    let mut err = std::io::stderr().lock();
    let status = cli::run(&args, &exchange, &environment, &mut out, &mut err);
    let _ = out.flush();
    let _ = err.flush();
    ExitCode::from(status)
}
