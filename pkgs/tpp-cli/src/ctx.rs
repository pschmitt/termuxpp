//! Where tpp finds its files: the prefix and the plugin directories.

use std::env;
use std::ffi::{OsStr, OsString};
use std::fs;
use std::io::IsTerminal;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use crate::table::Table;

/// The prefix of the Termux app, used when the binary does not live in a
/// `PREFIX/bin` directory and was not built for a prefix.
const DEFAULT_PREFIX: &str = "/data/data/dev.pschmitt.termux/files/usr";

pub struct Ctx {
    /// colon-separated plugin directories, may be empty
    pub plugin_path: OsString,
}

/// The value of an environment variable that is set and not empty.
fn env_nonempty(name: &str) -> Option<OsString> {
    env::var_os(name).filter(|v| !v.is_empty())
}

/// The prefix: the one tpp was built for (`TPP_PREFIX` at build time), else
/// the one it is installed in (`PREFIX/bin/tpp`), else the Termux app prefix.
fn prefix() -> PathBuf {
    if let Some(p) = option_env!("TPP_PREFIX") {
        return PathBuf::from(p);
    }
    if let Ok(exe) = env::current_exe() {
        if let Some(bin) = exe.parent() {
            if bin.file_name().is_some_and(|n| n == "bin") {
                if let Some(prefix) = bin.parent() {
                    return prefix.to_path_buf();
                }
            }
        }
    }
    PathBuf::from(DEFAULT_PREFIX)
}

pub fn is_executable_file(path: &Path) -> bool {
    fs::metadata(path).is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
}

/// Plugin names are lower case words: `[a-z][a-z0-9-]*`.
pub fn valid_plugin_name(name: &str) -> bool {
    let mut chars = name.chars();
    chars.next().is_some_and(|c| c.is_ascii_lowercase())
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

/// Whether to color the output: NO_COLOR switches it off, CLICOLOR_FORCE on,
/// else it is on for a terminal.
pub fn color_help(text: &str) -> String {
    style_help(text, use_color())
}

pub fn color_stderr_help(text: &str) -> String {
    style_help(text, color_enabled(std::io::stderr().is_terminal()))
}

fn style_help(text: &str, color: bool) -> String {
    if !color {
        return text.to_owned();
    }
    let mut result = String::new();
    for line in text.split_inclusive('\n') {
        let content = line.trim_end_matches('\n');
        let newline = if line.ends_with('\n') { "\n" } else { "" };
        if content.starts_with("Usage:")
            || content.starts_with("usage:")
            || content.ends_with(':') && !content.starts_with(' ')
        {
            result.push_str(&format!("\x1b[1;36m{content}\x1b[0m{newline}"));
        } else {
            let mut words = content.split_inclusive(char::is_whitespace);
            let first = words
                .clone()
                .find(|word| !word.trim().is_empty())
                .unwrap_or("");
            let is_row = content.starts_with("  ") && !content.starts_with("    ");
            let mut first_word = true;
            for word in &mut words {
                let token = word.trim_end();
                let is_option = token.trim_start_matches(['[', '(', '\'']).starts_with('-');
                if is_option || (is_row && first_word && !token.is_empty() && word == first) {
                    result.push_str(if is_option { "\x1b[33m" } else { "\x1b[32m" });
                    result.push_str(token);
                    result.push_str("\x1b[0m");
                    result.push_str(&word[token.len()..]);
                } else {
                    result.push_str(word);
                }
                first_word &= token.is_empty();
            }
            result.push_str(newline);
        }
    }
    result
}

pub fn use_color() -> bool {
    color_enabled(std::io::stdout().is_terminal())
}

fn color_enabled(is_terminal: bool) -> bool {
    if env_nonempty("NO_COLOR").is_some() {
        return false;
    }
    if env_nonempty("CLICOLOR_FORCE").is_some_and(|v| v != "0") {
        return true;
    }
    is_terminal && env::var_os("TERM").is_none_or(|t| t != "dumb")
}

impl Ctx {
    /// The context from the environment: `TPP_PLUGIN_DIR` (default
    /// PREFIX/libexec/tpp/plugins, an empty value means no plugins).
    pub fn from_env() -> Ctx {
        let plugin_path = env::var_os("TPP_PLUGIN_DIR").unwrap_or_else(|| {
            prefix()
                .join("libexec")
                .join("tpp")
                .join("plugins")
                .into_os_string()
        });
        Ctx { plugin_path }
    }

    pub fn plugin_dirs(&self) -> Vec<PathBuf> {
        self.plugin_path
            .as_bytes()
            .split(|b| *b == b':')
            .filter(|d| !d.is_empty())
            .map(|d| PathBuf::from(OsStr::from_bytes(d)))
            .collect()
    }

    /// The executable of the plugin NAME, in the first directory that has it.
    pub fn find_plugin(&self, name: &str) -> Option<PathBuf> {
        if !valid_plugin_name(name) {
            return None;
        }
        self.plugin_dirs()
            .into_iter()
            .map(|d| d.join(name))
            .find(|p| is_executable_file(p))
    }

    /// The names of the plugins that are not shadowed by a command or by a
    /// plugin in an earlier directory.
    pub fn list_plugins(&self, table: &Table) -> Vec<String> {
        let mut names: Vec<String> = Vec::new();
        for dir in self.plugin_dirs() {
            let Ok(read) = fs::read_dir(&dir) else {
                continue;
            };
            let mut found: Vec<String> = read
                .filter_map(|e| e.ok())
                .filter_map(|e| e.file_name().into_string().ok())
                .filter(|n| valid_plugin_name(n) && is_executable_file(&dir.join(n)))
                .collect();
            found.sort();
            for name in found {
                if !names.contains(&name)
                    && !table.is_group(&name)
                    && !crate::native::is_native(&name)
                {
                    names.push(name);
                }
            }
        }
        names
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plugin_names() {
        for ok in ["a", "session", "my-plugin2"] {
            assert!(valid_plugin_name(ok), "{ok}");
        }
        for bad in ["", "-a", "1a", "A", "a_b", "a.b", "a b", "../x"] {
            assert!(!valid_plugin_name(bad), "{bad}");
        }
    }
}
