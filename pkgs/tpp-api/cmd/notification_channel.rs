//! `notification-channel`: `tpp notification channel -d id` or `... id name`.
//!
//! Anything else shows the usage and exits with status 0, as the script does.
//!
//! Deviations:
//! the script word-splits `$ARGS`; the id and the name are passed as one
//! argument each instead (an empty one stays an empty argument).
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/notification-channel.txt");

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let first = args.first().map(String::as_str).unwrap_or("");
    if matches!(first, "-h" | "--help" | "") {
        return super::show_usage(io, USAGE);
    }
    let call = if first == "-d" {
        match &args[1..] {
            [id] => Call::new("NotificationChannel")
                .ez("delete", true)
                .es("id", id),
            _ => return super::show_usage(io, USAGE),
        }
    } else {
        match args {
            [id, name] => Call::new("NotificationChannel")
                .es("id", id)
                .es("name", name),
            _ => return super::show_usage(io, USAGE),
        }
    };
    io.run_call(call)
}
