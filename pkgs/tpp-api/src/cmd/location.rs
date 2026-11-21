//! `location`: `tpp location [-p provider] [-r request]`.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/location.txt");
const NAME: &str = "tpp location";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":hr:p:");
    let mut call = Call::new("Location");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('r', v) => {
                if !matches!(v.as_str(), "once" | "last" | "updates") {
                    io.outln(&format!("{NAME}: Unsupported request: '{v}'"));
                    return 1;
                }
                call = call.es("request", v);
            }
            Opt::Arg('p', v) => {
                if !matches!(v.as_str(), "gps" | "network" | "passive") {
                    io.outln(&format!("{NAME}: Unsupported provider: '{v}'"));
                    return 1;
                }
                call = call.es("provider", v);
            }
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return super::illegal_option(io, NAME, c)
            }
            Opt::Flag(_) => {}
        }
    }
    if let Some(status) = super::no_operands(io, NAME, g.rest()) {
        return status;
    }
    io.run_call(call)
}
