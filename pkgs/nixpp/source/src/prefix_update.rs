//! Keep TermuxPP's own files in the native prefix current.
//!
//! The APK's bootstrap installed them once (rbw, the seed under share/nixpp,
//! etc/nixpp/setup.conf) and nothing updated them, short of reinstalling the
//! outer shell. A generation now lists them in `native/prefix.json`: entries
//! `{"path": "bin/rbw", "source": "/nix/store/...", "mode": "0700"}` with
//! paths relative to `$PREFIX`. After a switch the daemon syncs them, the way
//! the app's native `tpp` installer works: it replaces a file only when it is
//! absent, already identical, or unchanged since nixpp installed it (a SHA-256
//! record in `var/lib/nixpp/prefix-install.json`). The bootstrap's own copy,
//! found the first time, is kept as `<file>.nixpp.bak`. A file changed since
//! nixpp installed it (by hand or by `apt`) stays and is reported. Recorded
//! files a generation no longer ships are removed when unchanged. Upstream
//! Termux packages are not listed: `apt` updates them.

use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::path::{Component, Path, PathBuf};

/// The install record, relative to the prefix.
pub(crate) const RECORD: &str = "var/lib/nixpp/prefix-install.json";
const BACKUP_SUFFIX: &str = ".nixpp.bak";

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub(crate) struct Entry {
    pub path: String,
    pub source: String,
    pub mode: String,
}

#[derive(Default, Deserialize, Serialize)]
struct Record {
    files: BTreeMap<String, String>,
}

/// What a sync did, for the daemon's log.
#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct Report {
    pub installed: Vec<String>,
    pub kept: Vec<String>,
    pub removed: Vec<String>,
}

/// The prefix entries of the selected generation, once per generation
/// (`checked` as in native_update), or None without `native/prefix.json`.
pub(crate) fn pending(runtime: &Path, checked: &mut Option<String>) -> Option<Vec<Entry>> {
    let text = fs::read_to_string(runtime.join("current-system")).ok()?;
    let name = text.strip_suffix('\n')?;
    if !crate::store::is_candidate_name(name) || checked.as_deref() == Some(name) {
        return None;
    }
    *checked = Some(name.to_owned());
    let manifest = runtime
        .join("root/nix/store")
        .join(name)
        .join("native/prefix.json");
    let entries: Vec<Entry> = serde_json::from_slice(&fs::read(manifest).ok()?).ok()?;
    Some(entries)
}

/// Sync `entries` into `prefix`; sources are guest store paths under
/// `runtime/root`.
pub(crate) fn sync(runtime: &Path, prefix: &Path, entries: &[Entry]) -> Result<Report> {
    for entry in entries {
        validate(entry)?;
    }
    let record_path = prefix.join(RECORD);
    let mut record: Record = match fs::read(&record_path) {
        Ok(data) => serde_json::from_slice(&data)
            .with_context(|| format!("invalid {}", record_path.display()))?,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Record::default(),
        Err(error) => return Err(error.into()),
    };
    let mut report = Report::default();
    for entry in entries {
        let source = runtime
            .join("root")
            .join(entry.source.trim_start_matches('/'));
        let wanted =
            digest(&source).with_context(|| format!("cannot read {}", source.display()))?;
        let target = prefix.join(&entry.path);
        let current = match digest(&target) {
            Ok(current) => Some(current),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
            Err(error) => {
                return Err(error).with_context(|| format!("cannot read {}", target.display()));
            }
        };
        let mode = u32::from_str_radix(&entry.mode, 8)?;
        match (current, record.files.get(&entry.path)) {
            (Some(current), _) if current == wanted => {
                set_mode(&target, mode)?;
            }
            (Some(current), Some(recorded)) if current != *recorded => {
                // Changed since nixpp installed it.
                report.kept.push(entry.path.clone());
                continue;
            }
            (Some(_), None) => {
                // The bootstrap's copy, never recorded: keep it aside once.
                let backup = PathBuf::from(format!("{}{BACKUP_SUFFIX}", target.display()));
                if fs::symlink_metadata(&backup).is_err() {
                    fs::copy(&target, &backup)
                        .with_context(|| format!("cannot back up {}", target.display()))?;
                }
                install(&source, &target, mode)?;
                report.installed.push(entry.path.clone());
            }
            _ => {
                install(&source, &target, mode)?;
                report.installed.push(entry.path.clone());
            }
        }
        record.files.insert(entry.path.clone(), wanted);
    }
    let shipped: Vec<&str> = entries.iter().map(|entry| entry.path.as_str()).collect();
    let dropped: Vec<String> = record
        .files
        .keys()
        .filter(|path| !shipped.contains(&path.as_str()))
        .cloned()
        .collect();
    for path in dropped {
        let recorded = record.files.remove(&path).unwrap_or_default();
        let target = prefix.join(&path);
        if digest(&target).is_ok_and(|current| current == recorded) {
            fs::remove_file(&target)
                .with_context(|| format!("cannot remove {}", target.display()))?;
            report.removed.push(path);
        }
    }
    let directory = record_path.parent().context("record path has no parent")?;
    fs::create_dir_all(directory)?;
    let data = serde_json::to_vec_pretty(&record)?;
    crate::bootstrap::replace_with(directory, "prefix-install.json", 0o600, |file| {
        std::io::Write::write_all(file, &data)
    })?;
    Ok(report)
}

fn validate(entry: &Entry) -> Result<()> {
    let relative = Path::new(&entry.path);
    ensure!(
        !entry.path.is_empty()
            && relative
                .components()
                .all(|component| matches!(component, Component::Normal(_))),
        "invalid prefix path {:?}",
        entry.path
    );
    ensure!(
        entry.path != RECORD && !entry.path.ends_with(BACKUP_SUFFIX),
        "reserved prefix path {:?}",
        entry.path
    );
    let root = entry
        .source
        .strip_prefix("/nix/store/")
        .and_then(|rest| rest.split('/').next())
        .map(|name| format!("/nix/store/{name}"));
    ensure!(
        root.is_some_and(|root| crate::store::is_store_root(&root))
            && !entry.source.contains("/../"),
        "invalid source {:?}",
        entry.source
    );
    match entry.mode.as_str() {
        "0600" | "0644" | "0700" | "0755" => Ok(()),
        mode => bail!("invalid mode {mode:?} for {}", entry.path),
    }
}

/// Write `source` to `target` atomically with `mode`.
fn install(source: &Path, target: &Path, mode: u32) -> Result<()> {
    let directory = target.parent().context("target has no parent")?;
    fs::create_dir_all(directory)?;
    let name = target
        .file_name()
        .and_then(|name| name.to_str())
        .context("invalid target name")?;
    crate::bootstrap::replace_with(directory, name, mode, |file| {
        std::io::copy(&mut fs::File::open(source)?, file).map(|_| ())
    })
    .with_context(|| format!("cannot install {}", target.display()))
}

fn set_mode(path: &Path, mode: u32) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    if fs::metadata(path)?.permissions().mode() & 0o7777 != mode {
        fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
    }
    Ok(())
}

fn digest(path: &Path) -> std::io::Result<String> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.is_file() {
        return Err(std::io::Error::other("not a regular file"));
    }
    let mut hasher = Sha256::new();
    std::io::copy(&mut fs::File::open(path)?, &mut hasher)?;
    Ok(hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fixture {
        _dir: tempfile::TempDir,
        runtime: PathBuf,
        prefix: PathBuf,
    }

    fn fixture() -> Fixture {
        let dir = tempfile::tempdir().unwrap();
        let runtime = dir.path().join("runtime");
        let prefix = dir.path().join("usr");
        fs::create_dir_all(&prefix).unwrap();
        Fixture {
            _dir: dir,
            runtime,
            prefix,
        }
    }

    impl Fixture {
        /// A store file with `content`; the entry that installs it at `path`.
        fn entry(&self, id: char, path: &str, content: &str, mode: &str) -> Entry {
            let store = format!("/nix/store/{}-pkg", id.to_string().repeat(32));
            let file = self.runtime.join("root").join(&store[1..]).join("file");
            fs::create_dir_all(file.parent().unwrap()).unwrap();
            fs::write(&file, content).unwrap();
            Entry {
                path: path.to_owned(),
                source: format!("{store}/file"),
                mode: mode.to_owned(),
            }
        }
        fn read(&self, path: &str) -> String {
            fs::read_to_string(self.prefix.join(path)).unwrap()
        }
        fn sync(&self, entries: &[Entry]) -> Report {
            sync(&self.runtime, &self.prefix, entries).unwrap()
        }
    }

    #[test]
    fn absent_files_are_installed_with_their_mode() {
        use std::os::unix::fs::PermissionsExt;
        let f = fixture();
        let report = f.sync(&[f.entry('a', "etc/nixpp/setup.conf", "conf", "0600")]);
        assert_eq!(report.installed, ["etc/nixpp/setup.conf"]);
        assert_eq!(f.read("etc/nixpp/setup.conf"), "conf");
        let mode = fs::metadata(f.prefix.join("etc/nixpp/setup.conf"))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o7777, 0o600);
    }

    #[test]
    fn the_bootstrap_copy_is_kept_aside_once_and_later_updates_replace_it() {
        let f = fixture();
        fs::create_dir_all(f.prefix.join("bin")).unwrap();
        fs::write(f.prefix.join("bin/rbw"), "bootstrap").unwrap();
        f.sync(&[f.entry('a', "bin/rbw", "v1", "0700")]);
        assert_eq!(f.read("bin/rbw"), "v1");
        assert_eq!(f.read("bin/rbw.nixpp.bak"), "bootstrap");
        let report = f.sync(&[f.entry('b', "bin/rbw", "v2", "0700")]);
        assert_eq!(report.installed, ["bin/rbw"]);
        assert_eq!(f.read("bin/rbw"), "v2");
        assert_eq!(f.read("bin/rbw.nixpp.bak"), "bootstrap");
    }

    #[test]
    fn a_file_changed_since_it_was_installed_stays() {
        let f = fixture();
        f.sync(&[f.entry('a', "bin/rbw", "v1", "0700")]);
        fs::write(f.prefix.join("bin/rbw"), "apt's rbw").unwrap();
        let report = f.sync(&[f.entry('b', "bin/rbw", "v2", "0700")]);
        assert_eq!(report.kept, ["bin/rbw"]);
        assert_eq!(f.read("bin/rbw"), "apt's rbw");
    }

    #[test]
    fn identical_files_are_adopted_and_dropped_files_removed_when_unchanged() {
        let f = fixture();
        fs::create_dir_all(f.prefix.join("share/nixpp")).unwrap();
        fs::write(f.prefix.join("share/nixpp/manifest.json"), "{}").unwrap();
        let manifest = f.entry('a', "share/nixpp/manifest.json", "{}", "0644");
        let launcher = f.entry('b', "share/nixpp/launcher", "elf", "0700");
        let report = f.sync(&[manifest.clone(), launcher]);
        assert_eq!(report.installed, ["share/nixpp/launcher"]);
        assert!(
            !f.prefix
                .join("share/nixpp/manifest.json.nixpp.bak")
                .exists()
        );
        let report = f.sync(&[manifest]);
        assert_eq!(report.removed, ["share/nixpp/launcher"]);
        assert!(!f.prefix.join("share/nixpp/launcher").exists());
    }

    #[test]
    fn unsafe_entries_are_refused_before_anything_changes() {
        let f = fixture();
        let good = f.entry('a', "bin/rbw", "v1", "0700");
        for bad in [
            Entry {
                path: "../escape".into(),
                ..good.clone()
            },
            Entry {
                path: "/abs".into(),
                ..good.clone()
            },
            Entry {
                path: RECORD.into(),
                ..good.clone()
            },
            Entry {
                source: "/etc/passwd".into(),
                ..good.clone()
            },
            Entry {
                mode: "4755".into(),
                ..good.clone()
            },
        ] {
            assert!(sync(&f.runtime, &f.prefix, &[good.clone(), bad]).is_err());
        }
        assert!(!f.prefix.join("bin/rbw").exists());
    }
}
