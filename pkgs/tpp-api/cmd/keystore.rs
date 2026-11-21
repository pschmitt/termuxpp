//! `keystore`: `tpp keystore list|generate|delete|sign|verify`.
//!
//! Deviations:
//! - `sign` pipes the reply through `base64 -d`; this is a built-in decoder that
//!   follows GNU `base64 -d` (only newlines are skipped, a bad quantum ends the
//!   output with `base64: invalid input` and status 1). When the call itself
//!   fails, `tpp: ...` is printed and the status is 1 (the script ends with the
//!   status of `base64`).
//! - `verify` with a signature path `realpath` cannot resolve prints GNU's
//!   `realpath: PATH: reason` (reasons are the OS error texts) and goes on with
//!   an empty signature, as the script does; names GNU would quote differ.
//! - `generate` with an unknown option or an option without its value prints a
//!   message of this command (the shell's `getopts` names the shell and the
//!   script) and goes on parsing, as the script does.
//! - Arguments are passed intact (the script does not word-split them either,
//!   it quotes them).
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/keystore.txt");
const NAME: &str = "tpp keystore";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let Some(action) = args.first() else {
        return super::show_usage(io, USAGE);
    };
    let rest = &args[1..];
    match action.as_str() {
        "list" => list_keys(rest, io),
        "generate" => generate_key(rest, io),
        "delete" => delete_key(rest, io),
        "sign" => sign_data(rest, io),
        "verify" => verify_data(rest, io),
        _ => super::show_usage(io, USAGE),
    }
}

/// `check_args`: Some(status) when the count is wrong.
fn check_args(io: &mut Io, name: &str, expected: usize, got: usize) -> Option<i32> {
    if expected == got {
        None
    } else {
        io.outln(&format!(
            "{NAME}: {name} needs exactly {expected} arguments"
        ));
        Some(1)
    }
}

fn list_keys(args: &[String], io: &mut Io) -> i32 {
    let call = Call::new("Keystore").e("command", "list");
    let call = if args.first().map(String::as_str) == Some("-d") {
        call.ez("detailed", "true")
    } else {
        call
    };
    io.run_call(call)
}

fn delete_key(args: &[String], io: &mut Io) -> i32 {
    if let Some(status) = check_args(io, "delete", 1, args.len()) {
        return status;
    }
    io.run_call(
        Call::new("Keystore")
            .e("command", "delete")
            .e("alias", &args[0]),
    )
}

fn sign_data(args: &[String], io: &mut Io) -> i32 {
    if let Some(status) = check_args(io, "sign", 2, args.len()) {
        return status;
    }
    let call = Call::new("Keystore")
        .e("command", "sign")
        .e("alias", &args[0])
        .e("algorithm", &args[1]);
    match io.call_capture(call) {
        Ok((out, mut reply)) => {
            reply.close_fd();
            let (decoded, ok) = base64_decode(&out);
            let _ = io.stdout.write_all(&decoded);
            let _ = io.stdout.flush();
            if ok {
                0
            } else {
                io.errln("base64: invalid input");
                1
            }
        }
        Err(e) => {
            io.errln(&format!("tpp: {e}"));
            1
        }
    }
}

fn verify_data(args: &[String], io: &mut Io) -> i32 {
    if let Some(status) = check_args(io, "verify", 3, args.len()) {
        return status;
    }
    // `-e signature "$(realpath "$3")"`: a failing realpath does not end the
    // script (it is no assignment), the argument is then empty.
    let signature = match io.realpath(&args[2]) {
        Ok(p) => p,
        Err(e) => {
            io.errln(&format!("realpath: {}", strip_os_error(&e.to_string())));
            String::new()
        }
    };
    io.run_call(
        Call::new("Keystore")
            .e("command", "verify")
            .e("alias", &args[0])
            .e("algorithm", &args[1])
            .e("signature", signature),
    )
}

/// "No such file or directory (os error 2)" -> "No such file or directory".
fn strip_os_error(msg: &str) -> String {
    match msg.rfind(" (os error ") {
        Some(i) if msg.ends_with(')') => msg[..i].to_string(),
        _ => msg.to_string(),
    }
}

fn generate_key(args: &[String], io: &mut Io) -> i32 {
    let Some(alias) = args.first() else {
        io.outln(&format!("{NAME} generate: alias argument is required"));
        return 1;
    };
    let mut algorithm = String::from("RSA");
    let mut size = String::from("-1");
    let mut curve = "secp256r1";
    let mut validity = String::from("0");

    let mut g = Getopts::new(&args[1..], "a:s:c:u:");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Arg('a', v) => algorithm = v,
            Opt::Arg('s', v) => size = v,
            Opt::Arg('u', v) => validity = v,
            Opt::Illegal(c) => io.errln(&format!("{NAME}: illegal option -- {c}")),
            Opt::MissingArg(c) => io.errln(&format!("{NAME}: option requires an argument -- {c}")),
            Opt::Arg(..) | Opt::Flag(_) => {}
        }
    }

    if algorithm == "RSA" {
        match size.as_str() {
            "-1" => size = String::from("2048"),
            "2048" | "3072" | "4096" => {}
            _ => {
                io.outln(&format!("{NAME}: invalid RSA key size {size}"));
                return 1;
            }
        }
    } else if algorithm == "EC" {
        match size.as_str() {
            "-1" | "256" => curve = "secp256r1",
            "384" => curve = "secp384r1",
            "521" => curve = "secp521r1",
            _ => {
                io.outln(&format!("{NAME}: invalid EC key size {size}"));
                return 1;
            }
        }
    } else {
        io.outln(&format!("{NAME}: invalid algorithm {algorithm}"));
        return 1;
    }

    // purpose 12 is SIGN+VERIFY
    io.run_call(
        Call::new("Keystore")
            .e("command", "generate")
            .e("alias", alias)
            .e("algorithm", &algorithm)
            .ei("purposes", 12)
            .esa("digests", "NONE,SHA-1,SHA-256,SHA-384,SHA-512")
            .ei("size", &size)
            .e("curve", curve)
            .ei("validity", &validity),
    )
}

/// The value of a base64 digit.
fn digit(c: u8) -> Option<u8> {
    match c {
        b'A'..=b'Z' => Some(c - b'A'),
        b'a'..=b'z' => Some(c - b'a' + 26),
        b'0'..=b'9' => Some(c - b'0' + 52),
        b'+' => Some(62),
        b'/' => Some(63),
        _ => None,
    }
}

/// Decode one quantum of up to four characters; false when it is invalid (the
/// bytes it yielded before that are in `out`, as GNU `base64 -d` writes them).
fn decode_quantum(q: &[u8], out: &mut Vec<u8>) -> bool {
    if q.len() < 2 {
        return false;
    }
    let (Some(a), Some(b)) = (digit(q[0]), digit(q[1])) else {
        return false;
    };
    out.push(a << 2 | b >> 4);
    if q.len() == 2 {
        return b & 0xf == 0;
    }
    if q[2] == b'=' {
        return q.len() == 4 && q[3] == b'=' && b & 0xf == 0;
    }
    let Some(c) = digit(q[2]) else { return false };
    out.push((b << 4) & 0xf0 | c >> 2);
    if q.len() == 3 {
        return c & 3 == 0;
    }
    if q[3] == b'=' {
        return c & 3 == 0;
    }
    let Some(d) = digit(q[3]) else { return false };
    out.push((c << 6) & 0xc0 | d);
    true
}

/// `base64 -d` as GNU coreutils does it: newlines are skipped, nothing else
/// is. Returns what was decoded and whether the input was valid.
pub fn base64_decode(input: &[u8]) -> (Vec<u8>, bool) {
    let data: Vec<u8> = input.iter().copied().filter(|b| *b != b'\n').collect();
    let mut out = Vec::with_capacity(data.len() / 4 * 3);
    for q in data.chunks(4) {
        if !decode_quantum(q, &mut out) {
            return (out, false);
        }
    }
    (out, true)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dec(s: &str) -> (Vec<u8>, bool) {
        base64_decode(s.as_bytes())
    }

    #[test]
    fn decodes_valid_input() {
        assert_eq!(dec(""), (vec![], true));
        assert_eq!(dec("QQ=="), (b"A".to_vec(), true));
        assert_eq!(dec("QUI="), (b"AB".to_vec(), true));
        assert_eq!(dec("QUJD"), (b"ABC".to_vec(), true));
        assert_eq!(dec("QUJDRA=="), (b"ABCD".to_vec(), true));
        assert_eq!(dec("QUJD\nRA==\n"), (b"ABCD".to_vec(), true));
        assert_eq!(dec("+/+/"), (vec![0xfb, 0xff, 0xbf], true));
        assert_eq!(dec("/w=="), (vec![0xff], true));
    }

    #[test]
    fn accepts_what_gnu_accepts() {
        // concatenated padded groups and unpadded tails
        assert_eq!(dec("QQ==QQ=="), (b"AA".to_vec(), true));
        assert_eq!(dec("QQ"), (b"A".to_vec(), true));
        assert_eq!(dec("QUI"), (b"AB".to_vec(), true));
    }

    #[test]
    fn rejects_what_gnu_rejects_after_the_decoded_prefix() {
        assert_eq!(dec("QQ="), (b"A".to_vec(), false));
        assert_eq!(dec("QR=="), (b"A".to_vec(), false)); // trailing bits
        assert_eq!(dec("QR"), (b"A".to_vec(), false));
        assert_eq!(dec("QUK="), (b"AB".to_vec(), false));
        assert_eq!(dec("QUK"), (b"AB".to_vec(), false));
        assert_eq!(dec("Q"), (vec![], false));
        assert_eq!(dec("QUJD!!!!"), (b"ABC".to_vec(), false));
        assert_eq!(dec("QU JD"), (b"A".to_vec(), false)); // spaces are invalid
        assert_eq!(dec("===="), (vec![], false));
    }

    #[test]
    fn os_error_suffix_is_stripped() {
        assert_eq!(
            strip_os_error("/x/y: No such file or directory (os error 2)"),
            "/x/y: No such file or directory"
        );
        assert_eq!(strip_os_error("plain"), "plain");
    }

    /// The same inputs through the system `base64 -d`; skipped without it.
    #[test]
    fn matches_the_system_base64() {
        use std::io::Write;
        use std::process::{Command, Stdio};
        let probe = Command::new("base64")
            .arg("--version")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        if probe.is_err() {
            eprintln!("skipped: no base64");
            return;
        }
        let run = |input: &[u8]| -> Option<(Vec<u8>, bool)> {
            let mut child = Command::new("base64")
                .arg("-d")
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn()
                .ok()?;
            let mut sin = child.stdin.take()?;
            let data = input.to_vec();
            let writer = std::thread::spawn(move || {
                let _ = sin.write_all(&data);
            });
            let out = child.wait_with_output().ok()?;
            let _ = writer.join();
            Some((out.stdout, out.status.success()))
        };
        let mut inputs: Vec<Vec<u8>> = vec![
            b"QUJD\n\nRA==\n".to_vec(),
            b"QUJD RA==".to_vec(),
            b"QUJD!".to_vec(),
            b"+/+/+/==".to_vec(),
            b"AAAA=AAA".to_vec(),
        ];
        let alphabet = *b"AQR=";
        let mut level: Vec<Vec<u8>> = vec![vec![]];
        for _ in 0..5 {
            let mut next = Vec::new();
            for prefix in &level {
                for c in alphabet {
                    let mut v = prefix.clone();
                    v.push(c);
                    next.push(v);
                }
            }
            inputs.extend(next.iter().cloned());
            level = next;
        }
        for input in inputs {
            let Some(expected) = run(&input) else {
                eprintln!("skipped: base64 did not run");
                return;
            };
            assert_eq!(
                base64_decode(&input),
                expected,
                "input {:?}",
                String::from_utf8_lossy(&input)
            );
        }
    }
}
