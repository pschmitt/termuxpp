//! util-linux `getopt(1)` (the enhanced, GNU `getopt_long` based one) as the
//! shell scripts call it:
//!
//! ```sh
//! TEMP=`getopt -n "$SCRIPTNAME" -o hc:i:t: --long help,content: -s bash -- "$@"`
//! eval set -- "$TEMP"
//! ```
//!
//! [`getopt_long`] returns what `eval set -- "$TEMP"` would leave in `"$@"`,
//! split into the options (normalized: a long option is always spelled in
//! full, `--cont` becomes `--content`) and the operands, or the text getopt
//! prints on standard error when it fails (the script then exits with status
//! 1: `TEMP=...` fails under `set -e`).
//!
//! Faithful to GNU getopt: arguments are permuted (operands may come before
//! options) unless the option string starts with `+` or `POSIXLY_CORRECT` is
//! set; `--` ends the options; a lone `-` is an operand; long options may be
//! abbreviated to any unambiguous prefix (an exact name always wins; a prefix
//! that matches several options is an error that lists them); `--name=value`;
//! clusters of short options (`-ab`, `-cvalue`); `x:` takes a value, `x::` an
//! optional attached one (which getopt prints as an empty value when absent).
//! All errors are reported (each one on its own line), not only the first.
//!
//! Not reproduced: `GETOPT_COMPATIBLE`, `-a` (long options with one dash),
//! `-q`, and the byte-wise handling of multibyte characters inside a cluster of
//! short options (this works on characters).

/// How a long option takes its value.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Arg {
    No,
    Required,
    Optional,
}

/// The result of a successful [`getopt_long`]: the options in the order given
/// and the operands (what follows `--` in getopt's output).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Parsed {
    /// `("-c", Some(value))`, `("--ongoing", None)`. Options that take a value
    /// always have `Some` (an absent optional value is the empty string).
    pub options: Vec<(String, Option<String>)>,
    pub operands: Vec<String>,
}

fn parse_short_spec(spec: &str) -> (bool, Vec<(char, Arg)>) {
    let mut stop_at_operand = false;
    let mut s = spec;
    if let Some(r) = s.strip_prefix('+') {
        stop_at_operand = true;
        s = r;
    } else if let Some(r) = s.strip_prefix('-') {
        s = r;
    }
    let mut out = Vec::new();
    let mut it = s.chars().peekable();
    while let Some(c) = it.next() {
        let mut arg = Arg::No;
        if it.peek() == Some(&':') {
            it.next();
            arg = Arg::Required;
            if it.peek() == Some(&':') {
                it.next();
                arg = Arg::Optional;
            }
        }
        out.push((c, arg));
    }
    (stop_at_operand, out)
}

fn parse_long_spec(longs: &[&str]) -> Vec<(String, Arg)> {
    longs
        .iter()
        .map(|l| {
            if let Some(n) = l.strip_suffix("::") {
                (n.to_string(), Arg::Optional)
            } else if let Some(n) = l.strip_suffix(':') {
                (n.to_string(), Arg::Required)
            } else {
                (l.to_string(), Arg::No)
            }
        })
        .collect()
}

/// Run `getopt -n NAME -o SHORT --long LONGS... -- ARGS...`.
///
/// `short` is the `-o` string (`"hc:i:t:"`), `longs` the comma separated
/// `--long` list split into its items (`["help", "content:"]`). `posixly_correct`
/// is whether `POSIXLY_CORRECT` is set in the environment. `Err` is what getopt
/// wrote to standard error (the exit status is 1).
pub fn getopt_long(
    name: &str,
    args: &[String],
    short: &str,
    longs: &[&str],
    posixly_correct: bool,
) -> Result<Parsed, String> {
    let (plus, shorts) = parse_short_spec(short);
    let stop_at_operand = plus || posixly_correct;
    let longs = parse_long_spec(longs);
    let mut parsed = Parsed::default();
    let mut errors = String::new();
    let mut err = |msg: String| {
        errors.push_str(name);
        errors.push_str(": ");
        errors.push_str(&msg);
        errors.push('\n');
    };

    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if a == "--" {
            parsed.operands.extend(args[i + 1..].iter().cloned());
            break;
        }
        if !a.starts_with('-') || a == "-" {
            if stop_at_operand {
                parsed.operands.extend(args[i..].iter().cloned());
                break;
            }
            parsed.operands.push(a.clone());
            i += 1;
            continue;
        }
        i += 1;
        if let Some(body) = a.strip_prefix("--") {
            let (opt_name, inline) = match body.split_once('=') {
                Some((n, v)) => (n, Some(v)),
                None => (body, None),
            };
            let matched: Vec<&(String, Arg)> =
                if let Some(exact) = longs.iter().find(|(n, _)| n == opt_name) {
                    vec![exact]
                } else {
                    longs
                        .iter()
                        .filter(|(n, _)| n.starts_with(opt_name))
                        .collect()
                };
            match matched.len() {
                0 => err(format!("unrecognized option '--{body}'")),
                1 => {
                    let (full, arg) = matched[0];
                    let arg = *arg;
                    match (arg, inline) {
                        (Arg::No, None) => parsed.options.push((format!("--{full}"), None)),
                        (Arg::No, Some(_)) => {
                            err(format!("option '--{full}' doesn't allow an argument"))
                        }
                        (Arg::Required, Some(v)) => parsed
                            .options
                            .push((format!("--{full}"), Some(v.to_string()))),
                        (Arg::Required, None) => match args.get(i) {
                            Some(v) => {
                                i += 1;
                                parsed.options.push((format!("--{full}"), Some(v.clone())));
                            }
                            None => err(format!("option '--{full}' requires an argument")),
                        },
                        (Arg::Optional, v) => parsed
                            .options
                            .push((format!("--{full}"), Some(v.unwrap_or("").to_string()))),
                    }
                }
                _ => {
                    let list: String = matched.iter().map(|(n, _)| format!(" '--{n}'")).collect();
                    err(format!(
                        "option '--{body}' is ambiguous; possibilities:{list}"
                    ));
                }
            }
            continue;
        }
        // a cluster of short options
        let chars: Vec<char> = a.chars().skip(1).collect();
        let mut p = 0;
        while p < chars.len() {
            let c = chars[p];
            p += 1;
            let arg = shorts
                .iter()
                .find(|(o, _)| *o == c && c != ':')
                .map(|s| s.1);
            match arg {
                None => err(format!("invalid option -- '{c}'")),
                Some(Arg::No) => parsed.options.push((format!("-{c}"), None)),
                Some(Arg::Optional) => {
                    let v: String = chars[p..].iter().collect();
                    p = chars.len();
                    parsed.options.push((format!("-{c}"), Some(v)));
                }
                Some(Arg::Required) => {
                    if p < chars.len() {
                        let v: String = chars[p..].iter().collect();
                        p = chars.len();
                        parsed.options.push((format!("-{c}"), Some(v)));
                    } else if let Some(v) = args.get(i) {
                        i += 1;
                        parsed.options.push((format!("-{c}"), Some(v.clone())));
                    } else {
                        err(format!("option requires an argument -- '{c}'"));
                    }
                }
            }
        }
    }
    if errors.is_empty() {
        Ok(parsed)
    } else {
        Err(errors)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    const LONGS: &[&str] = &["help", "help-actions", "content:", "channel:", "ongoing"];

    #[test]
    fn permutes_and_normalizes() {
        let p = getopt_long(
            "t",
            &v(&["x", "-ccc", "--cont=a", "--ongo", "--", "-h"]),
            "hc:",
            LONGS,
            false,
        )
        .unwrap();
        assert_eq!(
            p.options,
            vec![
                ("-c".to_string(), Some("cc".to_string())),
                ("--content".to_string(), Some("a".to_string())),
                ("--ongoing".to_string(), None),
            ]
        );
        assert_eq!(p.operands, v(&["x", "-h"]));
    }

    #[test]
    fn posixly_correct_stops_at_the_first_operand() {
        let p = getopt_long("t", &v(&["-h", "x", "-h"]), "h", LONGS, true).unwrap();
        assert_eq!(p.options.len(), 1);
        assert_eq!(p.operands, v(&["x", "-h"]));
    }

    #[test]
    fn errors_are_all_reported() {
        let e = getopt_long(
            "t",
            &v(&["--hel", "--x=1", "-z", "--ongoing=1", "--channel"]),
            "hc:",
            LONGS,
            false,
        )
        .unwrap_err();
        assert_eq!(
            e,
            "t: option '--hel' is ambiguous; possibilities: '--help' '--help-actions'\n\
             t: unrecognized option '--x=1'\n\
             t: invalid option -- 'z'\n\
             t: option '--ongoing' doesn't allow an argument\n\
             t: option '--channel' requires an argument\n"
        );
    }

    #[test]
    fn a_missing_short_value_is_an_error() {
        let e = getopt_long("t", &v(&["-hc"]), "hc:", LONGS, false).unwrap_err();
        assert_eq!(e, "t: option requires an argument -- 'c'\n");
    }

    #[test]
    fn exact_name_beats_prefix() {
        let p = getopt_long("t", &v(&["--help"]), "", LONGS, false).unwrap();
        assert_eq!(p.options, vec![("--help".to_string(), None)]);
    }
}
