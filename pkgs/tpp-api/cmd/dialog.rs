//! `dialog`: `tpp dialog [widget] [options]`.
//!
//! Reproduces the script's quirks: an option without its value (`-t` as the
//! last argument) is reported as an illegal option (the `?)` case pattern
//! matches the `:` of `getopts` too), `-h`, `-l` and the usage after an illegal first argument or too many
//! arguments exit with status 0, a first argument that is not a widget but
//! contains a dash followed by a lowercase letter anywhere (`[[ $1 =~ -[a-z] ]]`)
//! is taken as options of the default widget, and the "Illegal argument" line
//! is printed by `echo -e`, which interprets backslash escapes in the argument.
//!
//! Deviations: the `\u` and `\U` escapes of `echo -e` are always written as
//! UTF-8 (bash decides by the locale).
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/dialog.txt");
const NAME: &str = "tpp dialog";
const DEFAULT_WIDGET: &str = "text";
const WIDGETS: &[&str] = &[
    "confirm", "checkbox", "counter", "date", "radio", "sheet", "spinner", "speech", "text", "time",
];

const OPT_HINT_DESC: &str = "[-i hint] text hint (optional)";
const OPT_MULTI_LINE_DESC: &str = "[-m] multiple lines instead of single (optional)";
const OPT_PASS_DESC: &str = "[-p] enter input as password (optional)";
const OPT_NUMERIC_DESC: &str = "[-n] enter input as numbers (optional)";
const OPT_TITLE_DESC: &str = "[-t title] set title of dialog (optional)";
const OPT_RANGE_DESC: &str = "[-r min,max,start] comma delim of (3) numbers to use (optional)";
const OPT_VALUES_DESC: &str = "[-v \",,,\"] comma delim values to use (required)";
const OPT_DATEFORMAT_DESC: &str =
    "[-d \"dd-MM-yyyy k:m:s\"] SimpleDateFormat Pattern for date widget output (optional)";

const HINT_FLAG: u32 = 1;
const MULTI_LINE_FLAG: u32 = 2;
const PASS_FLAG: u32 = 4;
const RANGE_FLAG: u32 = 8;
const VALUES_FLAG: u32 = 16;
const NUM_FLAG: u32 = 32;

/// The bytes bash's `echo -e` writes for `s`, and whether `\c` cut the output
/// short (which also suppresses the final newline).
fn echo_e(s: &str) -> (Vec<u8>, bool) {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    let is_octal = |c: u8| (b'0'..=b'7').contains(&c);
    while i < b.len() {
        if b[i] != b'\\' || i + 1 >= b.len() {
            out.push(b[i]);
            i += 1;
            continue;
        }
        i += 1;
        let c = b[i];
        i += 1;
        match c {
            b'a' => out.push(7),
            b'b' => out.push(8),
            b'c' => return (out, true),
            b'e' | b'E' => out.push(27),
            b'f' => out.push(12),
            b'n' => out.push(b'\n'),
            b'r' => out.push(b'\r'),
            b't' => out.push(b'\t'),
            b'v' => out.push(11),
            b'\\' => out.push(b'\\'),
            b'0' => {
                let mut v: u32 = 0;
                let mut n = 0;
                while n < 3 && i < b.len() && is_octal(b[i]) {
                    v = v * 8 + u32::from(b[i] - b'0');
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
                while n < max && i < b.len() && b[i].is_ascii_hexdigit() {
                    v = v.wrapping_mul(16) + (b[i] as char).to_digit(16).unwrap_or(0);
                    i += 1;
                    n += 1;
                }
                if n == 0 {
                    out.extend_from_slice(&[b'\\', c]);
                } else if c == b'x' {
                    out.push((v & 0xff) as u8);
                } else if let Some(ch) = char::from_u32(v) {
                    let mut buf = [0u8; 4];
                    out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
                } else {
                    out.extend_from_slice(&b[i - n - 2..i]);
                }
            }
            _ => {
                out.push(b'\\');
                out.push(c);
            }
        }
    }
    (out, false)
}

/// `echo -e "$s"`
fn echo_e_line(io: &mut Io, s: &str) {
    let (mut bytes, stop) = echo_e(s);
    if !stop {
        bytes.push(b'\n');
    }
    let _ = io.stdout.write_all(&bytes);
    let _ = io.stdout.flush();
}

/// `widget_usage`
fn widget_usage(io: &mut Io, widget: &str) {
    io.out(&format!("{widget} - "));
    let lines: Vec<String> = match widget {
        "confirm" => vec![
            "Show confirmation dialog".into(),
            format!("    {OPT_HINT_DESC}"),
            format!("    {OPT_TITLE_DESC}"),
        ],
        "checkbox" => vec![
            "Select multiple values using checkboxes".into(),
            format!("    {OPT_VALUES_DESC}"),
            format!("    {OPT_TITLE_DESC}"),
        ],
        "counter" => vec![
            "Pick a number in specified range".into(),
            format!("    {OPT_RANGE_DESC}"),
            format!("    {OPT_TITLE_DESC}"),
        ],
        "date" => vec![
            "Pick a date".into(),
            format!("    {OPT_TITLE_DESC}"),
            format!("    {OPT_DATEFORMAT_DESC}"),
        ],
        "radio" => vec![
            "Pick a single value from radio buttons".into(),
            format!("    {OPT_VALUES_DESC}"),
            format!("    {OPT_TITLE_DESC}"),
        ],
        "sheet" => vec![
            "Pick a value from sliding bottom sheet".into(),
            format!("    {OPT_VALUES_DESC}"),
            format!("    {OPT_TITLE_DESC}"),
        ],
        "speech" => vec![
            "Obtain speech using device microphone".into(),
            format!("    {OPT_HINT_DESC}"),
            format!("    {OPT_TITLE_DESC}"),
        ],
        "spinner" => vec![
            "Pick a single value from a dropdown spinner".into(),
            format!("    {OPT_VALUES_DESC}"),
            format!("    {OPT_TITLE_DESC}"),
        ],
        "text" => vec![
            "Input text (default if no widget specified)".into(),
            format!("    {OPT_HINT_DESC}"),
            format!("    {OPT_MULTI_LINE_DESC}*"),
            format!("    {OPT_NUMERIC_DESC}*"),
            format!("    {OPT_PASS_DESC}"),
            format!("    {OPT_TITLE_DESC}"),
            "       * cannot use [-m] with [-n]".into(),
        ],
        "time" => vec!["Pick a time value".into(), format!("    {OPT_TITLE_DESC}")],
        _ => vec![format!("\\tUnknown usage for '{widget}'")],
    };
    for l in lines {
        io.outln(&l);
    }
}

/// `list_widgets`
fn list_widgets(io: &mut Io) {
    io.outln("Supported widgets:");
    io.outln("");
    for w in WIDGETS {
        widget_usage(io, w);
        io.outln("");
    }
}

/// `options_error`: exit status 1
fn options_error(io: &mut Io, widget: &str) -> i32 {
    echo_e_line(
        io,
        &format!("ERROR: Invalid option(s) detected for '{widget}'\\n"),
    );
    io.outln(&format!("Usage '{widget}'"));
    widget_usage(io, widget);
    1
}

/// `[[ $s =~ -[a-z] ]]`
fn has_dash_lowercase(s: &str) -> bool {
    s.as_bytes()
        .windows(2)
        .any(|w| w[0] == b'-' && w[1].is_ascii_lowercase())
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let (widget, args): (&str, &[String]) = if args.is_empty() {
        (DEFAULT_WIDGET, args)
    } else if !WIDGETS.contains(&args[0].as_str()) {
        // the first argument is not a widget: see whether it at least looks
        // like options (even illegal ones)
        if !has_dash_lowercase(&args[0]) {
            echo_e_line(io, &format!("ERROR: Illegal argument {}\\n", args[0]));
            return super::show_usage(io, USAGE);
        }
        (DEFAULT_WIDGET, args)
    } else {
        (args[0].as_str(), &args[1..])
    };

    let mut hint: Option<String> = None;
    let mut multi = false;
    let mut pass = false;
    let mut numeric = false;
    let mut range: Option<String> = None;
    let mut title: Option<String> = None;
    let mut values: Option<String> = None;
    let mut date_format: Option<String> = None;
    let mut flags: u32 = 0;

    let mut g = Getopts::new(args, ":hlmnpr:i:t:d:v:");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Flag('l') => {
                list_widgets(io);
                return 0;
            }
            Opt::Arg('i', v) => {
                hint = Some(v);
                flags |= HINT_FLAG;
            }
            Opt::Flag('m') => {
                multi = true;
                flags |= MULTI_LINE_FLAG;
            }
            Opt::Flag('p') => {
                pass = true;
                flags |= PASS_FLAG;
            }
            Opt::Flag('n') => {
                numeric = true;
                flags |= NUM_FLAG;
            }
            Opt::Arg('t', v) => title = Some(v),
            Opt::Arg('d', v) => date_format = Some(v),
            Opt::Arg('v', v) => {
                values = Some(v);
                flags |= VALUES_FLAG;
            }
            Opt::Arg('r', v) => {
                range = Some(v);
                flags |= RANGE_FLAG;
            }
            // `?)` is a pattern for any one character: it also catches the `:`
            // of an option without its value
            Opt::Illegal(c) | Opt::MissingArg(c) => return super::illegal_option(io, NAME, c),
            // not reachable: the option string has no other options
            Opt::Flag(_) | Opt::Arg(..) => {}
        }
    }
    let rest = g.rest();

    // the combinations of options the widget accepts
    let invalid = match widget {
        "text" => {
            flags & (RANGE_FLAG | VALUES_FLAG) != 0
                || (flags & MULTI_LINE_FLAG != 0 && flags & NUM_FLAG != 0)
        }
        "confirm" | "speech" => {
            flags & (MULTI_LINE_FLAG | PASS_FLAG | RANGE_FLAG | VALUES_FLAG | NUM_FLAG) != 0
        }
        "counter" => flags & (MULTI_LINE_FLAG | PASS_FLAG | VALUES_FLAG | NUM_FLAG) != 0,
        "date" | "time" => {
            flags & (HINT_FLAG | MULTI_LINE_FLAG | PASS_FLAG | RANGE_FLAG | VALUES_FLAG | NUM_FLAG)
                != 0
        }
        _ => {
            // checkbox, radio, sheet, spinner
            if values.is_none() {
                io.outln(&format!(
                    "ERROR: '{widget}' must be called with {OPT_VALUES_DESC}"
                ));
                return 1;
            }
            flags & (HINT_FLAG | MULTI_LINE_FLAG | PASS_FLAG | RANGE_FLAG | NUM_FLAG) != 0
        }
    };
    if invalid {
        return options_error(io, widget);
    }

    // all valid args should be consumed by now
    if !rest.is_empty() {
        io.outln("ERROR: Too many arguments!");
        return super::show_usage(io, USAGE);
    }

    let mut call = Call::new("Dialog").es("input_method", widget);
    if let Some(v) = &hint {
        call = call.es("input_hint", v);
    }
    if let Some(v) = &title {
        call = call.es("input_title", v);
    }
    if let Some(v) = &range {
        call = call.eia("input_range", v);
    }
    if let Some(v) = &values {
        call = call.es("input_values", v);
    }
    if multi {
        call = call.ez("multiple_lines", true);
    }
    if pass {
        call = call.ez("password", true);
    }
    if numeric {
        call = call.ez("numeric", true);
    }
    if let Some(v) = &date_format {
        call = call.es("date_format", v);
    }
    io.run_call(call)
}
