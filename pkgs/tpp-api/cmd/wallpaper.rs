//! `wallpaper`: `tpp wallpaper [-h] [-l] [-f file] [-u url]`.
//!
//! Also holds the helpers the other commands of this group use for the
//! messages of the external programs the scripts call (`realpath`, `touch`).
//!
//! Deviations:
//! the text of a `realpath` failure is rebuilt from the I/O error (quoting of
//! unusual paths in GNU `realpath` messages is not reproduced), and a path that
//! `realpath` would take for an option (`-f -x`) is not special.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};
use std::io;
use std::path::Path;

pub const USAGE: &str = include_str!("../help/wallpaper.txt");
const NAME: &str = "tpp wallpaper";

/// The message of an I/O error as `strerror` gives it (without Rust's
/// ` (os error N)`).
pub(crate) fn os_message(e: &io::Error) -> String {
    let s = e.to_string();
    match s.rfind(" (os error ") {
        Some(i) => s[..i].to_string(),
        None => s,
    }
}

/// What `realpath PATH` prints to standard error when it fails.
pub(crate) fn realpath_error(io: &mut Io, path: &str, e: &io::Error) {
    let msg = if path.is_empty() {
        "'': No such file or directory".to_string()
    } else {
        os_message(e)
    };
    io.errln(&format!("realpath: {msg}"));
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    // The option string of the script is ":h,:l,f:,u:": it makes `,` an option
    // that takes a value (which then lands in the `?)` branch).
    let mut g = Getopts::new(args, ":h,:l,f:,u:");
    let mut lockscreen = false;
    let mut file = String::new();
    let mut url = String::new();
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Flag('l') => lockscreen = true,
            Opt::Arg('f', v) => {
                // path="$(realpath "$OPTARG")" under set -e
                let path = match io.realpath(&v) {
                    Ok(p) => p,
                    Err(e) => {
                        realpath_error(io, &v, &e);
                        return 1;
                    }
                };
                if !Path::new(&path).is_file() {
                    io.outln(&format!("{NAME}: {path} is not a file!"));
                    return 1;
                }
                file = path;
            }
            Opt::Arg('u', v) => url = v,
            Opt::Arg(',', v) => {
                io.outln(&format!("{NAME}: illegal option -{v}"));
                return 1;
            }
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return super::illegal_option(io, NAME, c)
            }
            Opt::Flag(_) => {}
        }
    }
    if file.is_empty() && url.is_empty() {
        io.outln(&format!("{NAME}: you must specify either -f or -u"));
        return 1;
    } else if !file.is_empty() && !url.is_empty() {
        io.outln(&format!(
            "{NAME}: you must specify either -f or -u, but not both"
        ));
        return 1;
    }
    if let Some(status) = super::no_operands(io, NAME, g.rest()) {
        return status;
    }
    let mut call = Call::new("Wallpaper");
    if lockscreen {
        call = call.ez("lockscreen", true);
    }
    if !file.is_empty() {
        call = call.es("file", file);
    }
    if !url.is_empty() {
        call = call.es("url", url);
    }
    io.run_call(call)
}
