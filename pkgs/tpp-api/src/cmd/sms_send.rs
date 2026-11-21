//! `sms-send`: `tpp sms send -n number[,number2,...] [-s slot] [text]`.
//!
//! The text is the operands (`echo "$@"`, with the echo of bash: options
//! `-n`, `-e`, `-E`) or, without operands, the standard input.
//!
//! Deviations: the option values are passed to the app as one argument each;
//! the script's unquoted `$RECIPIENTS` and `$SLOT` would split a value with
//! blanks in it into several words.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/sms-send.txt");
const NAME: &str = "tpp sms send";

/// An unquoted `$VAR` in the script: split into words at blanks. Nothing or a
/// single word gives exactly what the shell passes; a value with several
/// words is passed as one argument (not split).
fn words(v: &str) -> Vec<String> {
    let parts: Vec<&str> = v
        .split([' ', '\t', '\n'])
        .filter(|w| !w.is_empty())
        .collect();
    match parts.len() {
        0 => Vec::new(),
        1 => vec![parts[0].to_string()],
        _ => vec![v.to_string()],
    }
}

/// Whether `s` is an option of the `echo` builtin (`-n`, `-e`, `-E` or a
/// cluster of them).
fn echo_option(s: &str) -> bool {
    s.len() > 1 && s.starts_with('-') && s[1..].chars().all(|c| matches!(c, 'n' | 'e' | 'E'))
}

/// The bytes `echo ARGS...` prints (bash, not in `xpg_echo` mode): leading
/// `-n`/`-e`/`-E` options, the arguments joined by a blank, the escapes of
/// `-e` (`\c` ends the output), a newline unless `-n`.
pub(crate) fn echo(args: &[String]) -> Vec<u8> {
    let mut newline = true;
    let mut escapes = false;
    let mut i = 0;
    while i < args.len() && echo_option(&args[i]) {
        for c in args[i][1..].chars() {
            match c {
                'n' => newline = false,
                'e' => escapes = true,
                _ => escapes = false,
            }
        }
        i += 1;
    }
    let text = args[i..].join(" ");
    let mut out = Vec::new();
    if !escapes {
        out.extend_from_slice(text.as_bytes());
    } else if !echo_escapes(text.as_bytes(), &mut out) {
        return out;
    }
    if newline {
        out.push(b'\n');
    }
    out
}

fn hex_value(b: u8) -> Option<u32> {
    (b as char).to_digit(16)
}

/// Process the backslash escapes of `echo -e`; false after `\c`.
fn echo_escapes(s: &[u8], out: &mut Vec<u8>) -> bool {
    let mut i = 0;
    while i < s.len() {
        if s[i] != b'\\' || i + 1 >= s.len() {
            out.push(s[i]);
            i += 1;
            continue;
        }
        let c = s[i + 1];
        i += 2;
        match c {
            b'a' => out.push(7),
            b'b' => out.push(8),
            b'c' => return false,
            b'e' | b'E' => out.push(27),
            b'f' => out.push(12),
            b'n' => out.push(b'\n'),
            b'r' => out.push(b'\r'),
            b't' => out.push(b'\t'),
            b'v' => out.push(11),
            b'\\' => out.push(b'\\'),
            b'0' => {
                // \0nnn: up to three more octal digits
                let mut v: u32 = 0;
                let mut n = 0;
                while n < 3 && i < s.len() && (b'0'..=b'7').contains(&s[i]) {
                    v = v * 8 + u32::from(s[i] - b'0');
                    i += 1;
                    n += 1;
                }
                out.push((v & 0xff) as u8);
            }
            b'x' | b'u' | b'U' => {
                let max = match c {
                    b'x' => 2,
                    b'u' => 4,
                    _ => 8,
                };
                let mut v: u32 = 0;
                let mut n = 0;
                while n < max && i < s.len() {
                    match hex_value(s[i]) {
                        Some(d) => {
                            v = v.wrapping_mul(16).wrapping_add(d);
                            i += 1;
                            n += 1;
                        }
                        None => break,
                    }
                }
                if n == 0 {
                    out.push(b'\\');
                    out.push(c);
                } else if c == b'x' {
                    out.push((v & 0xff) as u8);
                } else if let Some(ch) = char::from_u32(v) {
                    let mut buf = [0u8; 4];
                    out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
                } else {
                    out.extend_from_slice(&s[i - n - 2..i]);
                }
            }
            other => {
                out.push(b'\\');
                out.push(other);
            }
        }
    }
    true
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":hn:s:");
    // RECIPIENTS="--esa recipients $OPTARG": set (even if empty) by -n
    let mut recipients: Option<String> = None;
    let mut slot: Option<String> = None;
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('n', v) => recipients = Some(v),
            Opt::Arg('s', v) => slot = Some(v),
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) | Opt::Flag(c) => {
                return super::illegal_option(io, NAME, c)
            }
        }
    }
    let Some(recipients) = recipients else {
        io.outln(&format!("{NAME}: no recipient number given"));
        return 1;
    };
    let mut call = Call::new("SmsSend")
        .arg("--esa")
        .arg("recipients")
        .args(words(&recipients));
    if let Some(slot) = slot {
        call = call.arg("--ei").arg("slot").args(words(&slot));
    }
    let rest = g.rest();
    if !rest.is_empty() {
        call = call.stdin_bytes(echo(rest));
    }
    io.run_call(call)
}

#[cfg(test)]
mod tests {
    use super::echo;

    fn e(a: &[&str]) -> Vec<u8> {
        echo(&a.iter().map(|s| s.to_string()).collect::<Vec<_>>())
    }

    #[test]
    fn echo_like_bash() {
        assert_eq!(e(&["a", "b"]), b"a b\n");
        assert_eq!(e(&["-n", "a"]), b"a");
        assert_eq!(e(&["-e", "a\\tb"]), b"a\tb\n");
        assert_eq!(e(&["-e", "-E", "a\\tb"]), b"a\\tb\n");
        assert_eq!(e(&["-ne", "a\\cb"]), b"a");
        assert_eq!(e(&["-x", "a"]), b"-x a\n");
        assert_eq!(e(&["-e", "\\0101\\x41\\101"]), b"AA\\101\n");
    }
}
