//! `download`: `tpp download [-d description] [-t title] [-p path] url-to-download`.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/download.txt");
const NAME: &str = "tpp download";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":hd:t:p:");
    let (mut desc, mut title, mut path) = (None, None, None);
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('d', v) => desc = Some(v),
            Opt::Arg('t', v) => title = Some(v),
            Opt::Arg('p', v) => path = Some(v),
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return super::illegal_option(io, NAME, c)
            }
            Opt::Flag(_) => {}
        }
    }
    let rest = g.rest();
    if rest.is_empty() {
        io.outln(&format!("{NAME}: no url specified"));
        return 1;
    }
    if rest.len() > 1 {
        io.outln(&format!("{NAME}: too many arguments"));
        return 1;
    }
    let mut call = Call::new("Download");
    // The order is always description, title, path, whatever the option order.
    if let Some(v) = desc {
        call = call.es("description", v);
    }
    if let Some(v) = title {
        call = call.es("title", v);
    }
    if let Some(v) = path {
        call = call.es("path", v);
    }
    io.run_call(call.arg(&rest[0]))
}
