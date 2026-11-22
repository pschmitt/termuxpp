//! `tpp-session`: list, focus, close, kill, move and rename TermuxPP terminal sessions, and reload
//! the app settings, over the app's abstract Unix socket.
//!
//! The command-line behavior (usage and help text, exit statuses 0, 1 and 2, `--json` before or
//! after the command, abbreviations, JSON layout and colors) reproduces the Python/argparse
//! implementation that this crate replaced, so scripts and the shell completions keep working.

pub mod args;
pub mod cli;
pub mod client;
pub mod complete;
pub mod help;
pub mod json;
pub mod printable;
pub mod unicode;

/// One line that describes the command, for the command overview.
pub const SUMMARY: &str = "Manage TermuxPP terminal sessions";

/// The main help text (what `tpp-session --help` prints).
pub fn usage() -> &'static str {
    help::MAIN
}

#[cfg(test)]
mod tests {
    #[test]
    fn usage_is_the_main_help() {
        assert!(super::usage().starts_with("usage: tpp-session "));
        assert!(super::usage().contains("Manage TermuxPP terminal sessions."));
        assert!(!super::SUMMARY.ends_with('.'));
    }
}
