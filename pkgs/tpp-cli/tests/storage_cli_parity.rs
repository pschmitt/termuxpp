#![cfg(unix)]

use std::fs;
use std::os::unix::fs::{symlink, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::sync::atomic::{AtomicUsize, Ordering};

static NEXT: AtomicUsize = AtomicUsize::new(0);

struct Fixture(PathBuf);

impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "tpp-storage-parity-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        fs::create_dir(root.join("bin")).unwrap();
        symlink(env!("CARGO_BIN_EXE_tpp"), root.join("tpp-setup-storage")).unwrap();
        fs::write(
            root.join("bin/am"),
            "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$AM_LOG\"\nexit \"$AM_STATUS\"\n",
        )
        .unwrap();
        fs::set_permissions(root.join("bin/am"), fs::Permissions::from_mode(0o755)).unwrap();
        Self(root)
    }

    fn run(&self, rust: bool, args: &[&str], status: &str) -> (Output, Option<Vec<u8>>) {
        let log = self.0.join(if rust { "rust.log" } else { "shell.log" });
        let mut command = if rust {
            Command::new(self.0.join("tpp-setup-storage"))
        } else {
            let mut command = Command::new("bash");
            command
                .arg(Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/oracle/setup-storage.sh"));
            command
        };
        let path = format!(
            "{}:{}",
            self.0.join("bin").display(),
            std::env::var("PATH").unwrap_or_default()
        );
        let output = command
            .args(args)
            .env("HOME", &self.0)
            .env("PATH", path)
            .env("AM_LOG", &log)
            .env("AM_STATUS", status)
            .output()
            .unwrap();
        (output, fs::read(log).ok())
    }

    fn compare(&self, args: &[&str], status: &str) {
        let (shell, shell_log) = self.run(false, args, status);
        let (rust, rust_log) = self.run(true, args, status);
        assert_eq!(rust.status.code(), shell.status.code(), "args {args:?}");
        assert_eq!(rust.stdout, shell.stdout, "args {args:?}");
        assert_eq!(rust.stderr, shell.stderr, "args {args:?}");
        assert_eq!(rust_log, shell_log, "args {args:?}");
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

#[test]
fn help_and_invalid_arguments_match_shell() {
    for args in [&["-h"][..], &["--help"], &["-h", "extra"], &["invalid"]] {
        Fixture::new().compare(args, "0");
    }
}

#[test]
fn missing_storage_broadcast_and_failure_match_shell() {
    let uid = Command::new("id").arg("-u").output().unwrap();
    let uid: u32 = String::from_utf8(uid.stdout)
        .unwrap()
        .trim()
        .parse()
        .unwrap();
    if Path::new(&format!("/android/storage/emulated/{}", uid / 100_000)).exists() {
        eprintln!("skipped: Android shared storage exists on this host");
        return;
    }
    for status in ["0", "7"] {
        Fixture::new().compare(&[], status);
    }
}
