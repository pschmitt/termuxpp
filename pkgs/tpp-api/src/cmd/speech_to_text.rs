//! `speech-to-text`: `tpp speech-to-text [-p]`.
//!
//! Without `-p` the script pipes the answer through `tail -1`, so its exit
//! status is that of `tail` (0), even when the call fails.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/speech-to-text.txt");
const NAME: &str = "tpp speech-to-text";

/// `tail -1`: the last line, with its newline if it has one.
fn tail_one(data: &[u8]) -> &[u8] {
    let body = match data.last() {
        Some(b'\n') => &data[..data.len() - 1],
        _ => data,
    };
    let start = body.iter().rposition(|b| *b == b'\n').map_or(0, |i| i + 1);
    &data[start..]
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":hp");
    let mut show_progress = false;
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Flag('p') => show_progress = true,
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) | Opt::Flag(c) => {
                return super::illegal_option(io, NAME, c)
            }
        }
    }
    if let Some(status) = super::no_operands(io, NAME, g.rest()) {
        return status;
    }
    let call = Call::new("SpeechToText");
    if show_progress {
        return io.run_call(call);
    }
    // $CMD SpeechToText | tail -1
    match io.call_capture(call) {
        Ok((out, mut reply)) => {
            reply.close_fd();
            let last = tail_one(&out).to_vec();
            let _ = io.stdout.write_all(&last);
            let _ = io.stdout.flush();
        }
        Err(e) => io.errln(&format!("tpp: {e}")),
    }
    0
}

#[cfg(test)]
mod tests {
    use super::tail_one;

    #[test]
    fn tail() {
        assert_eq!(tail_one(b""), b"");
        assert_eq!(tail_one(b"a"), b"a");
        assert_eq!(tail_one(b"a\n"), b"a\n");
        assert_eq!(tail_one(b"a\nb"), b"b");
        assert_eq!(tail_one(b"a\nb\n"), b"b\n");
        assert_eq!(tail_one(b"a\nb\n\n"), b"\n");
        assert_eq!(tail_one(b"\n"), b"\n");
    }
}
