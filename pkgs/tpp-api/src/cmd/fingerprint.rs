//! `fingerprint`: `tpp fingerprint [-t title] [-d description] [-s subtitle] [-c cancel]`.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/fingerprint.txt");
const NAME: &str = "tpp fingerprint";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":ht:d:s:c:");
    let (mut title, mut description, mut subtitle, mut cancel) = (None, None, None, None);
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('t', v) => title = Some(v),
            Opt::Arg('d', v) => description = Some(v),
            Opt::Arg('s', v) => subtitle = Some(v),
            Opt::Arg('c', v) => cancel = Some(v),
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return super::illegal_option(io, NAME, c)
            }
            Opt::Flag(_) => {}
        }
    }
    // operands are ignored; the extras always come in this order
    let mut call = Call::new("Fingerprint");
    for (key, value) in [
        ("title", title),
        ("description", description),
        ("subtitle", subtitle),
        ("cancel", cancel),
    ] {
        if let Some(v) = value {
            call = call.es(key, v);
        }
    }
    io.run_call(call)
}
