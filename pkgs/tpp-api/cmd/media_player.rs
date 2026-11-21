//! `media-player`: `tpp media player cmd [args]`.
//!
//! Deviations:
//! the text of a `realpath` failure is approximated.
use crate::{Call, Io};
use std::path::Path;

pub const USAGE: &str = include_str!("../help/media-player.txt");
const NAME: &str = "tpp media player";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let first = args.first().map(String::as_str).unwrap_or("");
    match first {
        "play" => {
            if args.len() > 2 {
                io.outln(&format!("Error! {NAME} can only play one file at a time!"));
                1
            } else if args.len() > 1 {
                let file = &args[1];
                if !Path::new(file).is_file() {
                    io.outln(&format!("Error: '{file}' is not a file!"));
                    return 1;
                }
                // "$(realpath "$2")" as an argument: a failure does not stop the script
                let path = match io.realpath(file) {
                    Ok(p) => p,
                    Err(e) => {
                        super::wallpaper::realpath_error(io, file, &e);
                        String::new()
                    }
                };
                io.run_call(
                    Call::new("MediaPlayer")
                        .args(["-a", "play"])
                        .es("file", path),
                )
            } else {
                io.run_call(Call::new("MediaPlayer").args(["-a", "resume"]))
            }
        }
        "info" | "pause" | "stop" => {
            if args.len() > 1 {
                io.outln(&format!("Error! '{first}' takes no arguments!"));
                1
            } else {
                io.run_call(Call::new("MediaPlayer").args(["-a", first]))
            }
        }
        "help" | "-h" | "--help" | "" => super::show_usage(io, USAGE),
        _ => {
            io.outln(&format!("{NAME}: Invalid cmd: '{first}'"));
            io.out(USAGE);
            1
        }
    }
}
