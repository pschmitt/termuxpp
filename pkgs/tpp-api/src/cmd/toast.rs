//! `toast`: `tpp toast [-b bgcolor] [-c color] [-g gravity] [-s] [text]`.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};
use std::io::IsTerminal;
use std::time::Duration;

pub const USAGE: &str = include_str!("../help/toast.txt");
const NAME: &str = "tpp toast";

/// bash's `echo` takes `-n`, `-e` and `-E` (and clusters of them) as options.
fn is_echo_option(s: &str) -> bool {
    s.len() > 1 && s.starts_with('-') && s[1..].chars().all(|c| matches!(c, 'n' | 'e' | 'E'))
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":hsc:b:g:");
    let mut call = Call::new("Toast");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Flag('s') => call = call.ez("short", true),
            Opt::Arg('c', v) => call = call.es("text_color", v),
            Opt::Arg('b', v) => call = call.es("background", v),
            Opt::Arg('g', v) => call = call.es("gravity", v),
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return super::illegal_option(io, NAME, c)
            }
            Opt::Flag(_) => {}
        }
    }
    let rest = g.rest();
    if io.strict_usage && rest.is_empty() && std::io::stdin().is_terminal() {
        io.errln("tpp toast: enter a message as an argument, or pipe text into the command.");
        io.errln("Example: tpp toast \"Build finished\"");
        return 2;
    }
    // The text comes from the arguments, else from standard input (3 s).
    let mut text: Vec<u8> = if rest.is_empty() {
        io.read_stdin_until_nul(Duration::from_secs(3))
    } else {
        rest.join(" ").into_bytes()
    };
    // TOAST_TEXT="$(echo "$TOAST_TEXT")": trailing newlines go away
    if !io.strict_usage
        && std::str::from_utf8(&text)
            .map(is_echo_option)
            .unwrap_or(false)
    {
        text.clear();
    }
    while text.last() == Some(&b'\n') {
        text.pop();
    }
    if io.strict_usage && text.iter().all(u8::is_ascii_whitespace) {
        io.errln("tpp toast: no message received. Try: tpp toast \"Build finished\"");
        return 2;
    }
    // echo "$TOAST_TEXT" | termux-api Toast ...
    text.push(b'\n');
    io.run_call(call.stdin_bytes(text))
}
