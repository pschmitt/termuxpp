//! `camera-photo`: `tpp camera photo [-c camera-id] output-file`.
//!
//! Deviations:
//! the script word-splits `$PARAMS`; the camera id is passed as one argument
//! instead. A file name that `touch` or `realpath` would take for an option
//! (`-- -x`) is not special, and the text of their failures is approximated.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};
use std::fs::{File, OpenOptions};
use std::io;
use std::path::Path;
use std::time::SystemTime;

pub const USAGE: &str = include_str!("../help/camera-photo.txt");
const NAME: &str = "tpp camera photo";

/// `touch FILE`: create it, or bring its modification time up to date.
fn touch(path: &str) -> io::Result<()> {
    let p = Path::new(path);
    let f = match OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .open(p)
    {
        Ok(f) => f,
        // a directory, or a file we may not write but own
        Err(e) if p.exists() => File::open(p).map_err(|_| e)?,
        Err(e) => return Err(e),
    };
    f.set_modified(SystemTime::now())
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut g = Getopts::new(args, ":hc:");
    let mut camera: Option<String> = None;
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return super::show_usage(io, USAGE),
            Opt::Arg('c', v) => camera = Some(v),
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
    if rest.len() != 1 {
        io.outln(&format!("{NAME}: too many arguments"));
        return 1;
    }
    // touch "$1" (set -e)
    if let Err(e) = touch(&rest[0]) {
        io.errln(&format!(
            "touch: cannot touch '{}': {}",
            rest[0],
            super::wallpaper::os_message(&e)
        ));
        return 1;
    }
    // file=$(realpath "$1") || exit 1
    let file = match io.realpath(&rest[0]) {
        Ok(p) => p,
        Err(e) => {
            super::wallpaper::realpath_error(io, &rest[0], &e);
            return 1;
        }
    };
    let mut call = Call::new("CameraPhoto");
    if let Some(c) = camera {
        call = call.es("camera", c);
    }
    io.run_call(call.es("file", file))
}
