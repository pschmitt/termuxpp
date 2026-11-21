//! `share`: `tpp share [-a action] [-c content-type] [-d] [-t title] [file]`.
//!
//! Deviations:
//! the script word-splits `$PARAMS`; the value of an option is passed as one
//! argument instead (so a value with spaces stays together, an empty one stays
//! an empty argument). The text of a `realpath` failure is approximated.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};
use std::path::Path;

pub const USAGE: &str = include_str!("../help/share.txt");
const NAME: &str = "tpp share";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":ha:c:dt:");
    let mut call = Call::new("Share");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('a', v) => {
                if !matches!(v.as_str(), "edit" | "send" | "view") {
                    io.outln(&format!("{NAME}: Unsupported action: '{v}'"));
                    return 1;
                }
                call = call.es("action", v);
            }
            Opt::Arg('c', v) => call = call.es("content-type", v),
            Opt::Flag('d') => call = call.ez("default-receiver", true),
            Opt::Arg('t', v) => call = call.es("title", v),
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return super::illegal_option(io, NAME, c)
            }
            Opt::Flag(_) => {}
        }
    }
    let rest = g.rest();
    if rest.len() > 1 {
        io.outln(&format!("{NAME}: too many arguments"));
        return 1;
    }
    if let Some(file) = rest.first() {
        if !Path::new(file).is_file() {
            io.outln(&format!("{file} is not a file"));
            return 1;
        }
        // "$(realpath "$1")" as an argument: a failure does not stop the script
        let path = match io.realpath(file) {
            Ok(p) => p,
            Err(e) => {
                super::wallpaper::realpath_error(io, file, &e);
                String::new()
            }
        };
        call = call.es("file", path);
    }
    io.run_call(call)
}
