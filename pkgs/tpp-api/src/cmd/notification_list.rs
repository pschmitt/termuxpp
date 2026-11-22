//! `notification-list`: `tpp notification list`. The app answers with an
//! object holding `API_ERROR` instead of the array when it cannot list.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/notification-list.txt");
const NAME: &str = "tpp notification list";

/// `sed -n 's/^[[:space:]]*"API_ERROR":[[:space:]]*"\(.*\)"[[:space:]]*$/\1/p'`
fn api_error_message(output: &str) -> String {
    let mut found = Vec::new();
    for line in output.lines() {
        let Some(rest) = line.trim_start().strip_prefix("\"API_ERROR\":") else {
            continue;
        };
        let rest = rest.trim();
        if rest.len() >= 2 && rest.starts_with('"') && rest.ends_with('"') {
            found.push(&rest[1..rest.len() - 1]);
        }
    }
    found.join("\n")
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let rest = match super::only_help(args, io, NAME, USAGE) {
        Ok(r) => r,
        Err(status) => return status,
    };
    if let Some(status) = super::no_operands(io, NAME, rest) {
        return status;
    }
    let output = match io.call_text(Call::new("NotificationList")) {
        Ok(o) => o,
        Err(e) => {
            io.errln(&format!("tpp: {e}"));
            return 1;
        }
    };
    if output.is_empty() {
        io.errln(&format!("{NAME}: no response from TermuxPP"));
        return 1;
    }
    if output.starts_with('{') {
        let message = api_error_message(&output);
        let message = if message.is_empty() {
            "failed to list the notifications"
        } else {
            &message
        };
        io.errln(&format!("{NAME}: {message}"));
        if message.starts_with("Notification access is not granted to TermuxPP.") {
            io.errln(
                "On TermuxPP guests, run 'tpp permissions grant notification-access', then retry.",
            );
        }
        return 1;
    }
    io.outln(&output);
    0
}
