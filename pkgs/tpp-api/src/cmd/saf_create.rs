//! `saf-create`: `tpp saf create [-t mime-type] folder-uri name`.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/saf-create.txt");
const NAME: &str = "tpp saf create";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":ht:");
    let mut mime = String::new();
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('t', v) => mime = v,
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return super::illegal_option(io, NAME, c)
            }
            Opt::Flag(_) => {}
        }
    }
    let rest = g.rest();
    if rest.len() != 2 {
        io.outln(&format!("{NAME}: Invalid argument count"));
        return 1;
    }
    io.run_call(
        Call::new("SAF")
            .es("safmethod", "createDocument")
            .es("treeuri", &rest[0])
            .es("filename", &rest[1])
            .es("mimetype", mime),
    )
}
