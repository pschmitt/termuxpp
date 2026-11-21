//! `media-scan`: `tpp media scan [-v] [-r] file [file...]`.
//!
//! Deviations:
//! the text of a `realpath` failure is approximated.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};
use std::path::Path;

pub const USAGE: &str = include_str!("../help/media-scan.txt");
const NAME: &str = "tpp media scan";

/// `$(pwd)`: `$PWD` if it names the current directory, else the physical one.
fn pwd(io: &Io) -> String {
    let physical = std::env::current_dir().ok();
    if let Some(p) = io.env.get("PWD").filter(|p| p.starts_with('/')) {
        let same = match (std::fs::canonicalize(p), &physical) {
            (Ok(a), Some(b)) => std::fs::canonicalize(b).map(|b| a == b).unwrap_or(false),
            _ => false,
        };
        if same {
            return p.to_string();
        }
    }
    physical
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_default()
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":hrv");
    // each option is put in front of "--esa paths"
    let mut flags: Vec<char> = Vec::new();
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Flag(c @ ('r' | 'v')) => flags.push(c),
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return super::illegal_option(io, NAME, c)
            }
            Opt::Flag(_) => {}
        }
    }
    let rest = g.rest();
    if rest.is_empty() {
        io.outln(&format!("{NAME}: missing file argument"));
        return 1;
    }
    // PATHS=`get_paths "$@" | sed ':a;N;$!ba;s/,/\\\\,/g;s/\n/,/g'`: with several
    // lines sed joins them with commas and escapes the commas of the paths; a
    // single line is left alone (`N` finds no next line and sed stops before
    // the substitutions).
    let mut text = String::new();
    for arg in rest {
        if Path::new(arg).exists() {
            match io.realpath(arg) {
                Ok(p) => {
                    text.push_str(&p);
                    text.push('\n');
                }
                Err(e) => {
                    super::wallpaper::realpath_error(io, arg, &e);
                    break;
                }
            }
        } else if arg.starts_with('/') {
            text.push_str(arg);
            text.push('\n');
        } else {
            text.push_str(&format!("{}/{}\n", pwd(io), arg));
        }
    }
    let body = text.strip_suffix('\n').unwrap_or(&text);
    let paths = if body.contains('\n') {
        body.split('\n')
            .map(|l| l.replace(',', "\\,"))
            .collect::<Vec<_>>()
            .join(",")
    } else {
        body.to_string()
    };
    let mut call = Call::new("MediaScanner");
    for f in flags.iter().rev() {
        call = match f {
            'r' => call.ez("recursive", true),
            _ => call.ez("verbose", true),
        };
    }
    io.run_call(call.esa("paths", paths))
}
