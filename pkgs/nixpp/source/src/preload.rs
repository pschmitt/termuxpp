//! A native process can inherit an `LD_PRELOAD` naming a library that no
//! longer exists, for example the controller started long ago from a Termux
//! login whose profile preloaded `usr/lib/libtermux-exec.so`, which the slim
//! outer prefix does not have. Android's linker then refuses to start every
//! child, `/system/bin/sh` included. Drop such entries once at startup.

use std::path::Path;

/// `value` without absolute entries for which `exists` is false; `None` when
/// nothing is left. Entries are separated by `:` or whitespace, like the
/// linker reads them; relative names are kept (the linker searches for them).
pub(crate) fn without_missing(value: &str, exists: impl Fn(&Path) -> bool) -> Option<String> {
    let kept: Vec<&str> = value
        .split(|c: char| c == ':' || c.is_whitespace())
        .filter(|entry| !entry.is_empty())
        .filter(|entry| !entry.starts_with('/') || exists(Path::new(entry)))
        .collect();
    (!kept.is_empty()).then(|| kept.join(":"))
}

/// Remove missing libraries from this process's `LD_PRELOAD`. Call before
/// any thread starts.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub fn drop_missing() {
    let Some(value) = std::env::var_os("LD_PRELOAD") else {
        return;
    };
    let Some(value) = value.to_str() else {
        return;
    };
    let cleaned = without_missing(value, Path::is_file);
    if cleaned.as_deref() == Some(value) {
        return;
    }
    // SAFETY: called at the start of main, before any other thread exists.
    unsafe {
        match cleaned {
            Some(cleaned) => std::env::set_var("LD_PRELOAD", cleaned),
            None => std::env::remove_var("LD_PRELOAD"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::without_missing;
    use std::path::Path;

    fn exists(path: &Path) -> bool {
        path.starts_with("/present")
    }

    #[test]
    fn missing_absolute_entries_are_dropped() {
        assert_eq!(
            without_missing("/present/a.so:/gone/libtermux-exec.so", exists).as_deref(),
            Some("/present/a.so")
        );
        assert_eq!(without_missing("/gone/libtermux-exec.so", exists), None);
    }

    #[test]
    fn relative_names_and_separators() {
        assert_eq!(
            without_missing("libfoo.so /present/b.so  /gone/c.so", exists).as_deref(),
            Some("libfoo.so:/present/b.so")
        );
        assert_eq!(without_missing("", exists), None);
    }
}
