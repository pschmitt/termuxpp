use std::ffi::OsString;
use std::fs;
use std::os::unix::fs::symlink;
use std::path::{Path, PathBuf};
use std::process::Command;

const USAGE: &str = "Usage: tpp-setup-storage\n";

pub fn run(args: Vec<OsString>) -> i32 {
    if args.first().is_some_and(|a| a == "-h" || a == "--help") {
        println!("{USAGE}Create shortcuts to Android shared storage.");
        return 0;
    }
    if !args.is_empty() {
        eprint!("{USAGE}");
        return 2;
    }
    let output = match Command::new("id").arg("-u").output() {
        Ok(output) if output.status.success() => output,
        _ => {
            eprintln!("tpp-setup-storage: unable to determine user ID");
            return 1;
        }
    };
    let uid = match String::from_utf8_lossy(&output.stdout)
        .trim()
        .parse::<u32>()
    {
        Ok(uid) => uid,
        Err(_) => {
            eprintln!("tpp-setup-storage: invalid user ID");
            return 1;
        }
    };
    let user = uid / 100_000;
    let Some(home) = std::env::var_os("HOME") else {
        eprintln!("tpp-setup-storage: HOME is not set");
        return 1;
    };
    let shared = PathBuf::from(format!("/android/storage/emulated/{user}"));
    setup(&shared, Path::new(&home), user)
}

fn setup(shared: &Path, home: &Path, user: u32) -> i32 {
    let accessible = Command::new("test")
        .arg("-r")
        .arg(shared)
        .arg("-a")
        .arg("-x")
        .arg(shared)
        .status()
        .is_ok_and(|status| status.success());
    if !accessible {
        let status = Command::new("am")
            .args([
                "broadcast",
                "--user",
                &user.to_string(),
                "-a",
                "dev.pschmitt.termux.app.request_storage_permissions",
                "dev.pschmitt.termux",
            ])
            .status();
        match status {
            Ok(status) if status.success() => {}
            Ok(status) => return status.code().unwrap_or(1),
            Err(error) => {
                eprintln!("tpp-setup-storage: {error}");
                return 1;
            }
        }
        eprintln!("\nGrant storage access in Android, then run tpp-setup-storage again.");
        return 1;
    }

    let shortcuts = home.join("storage");
    if fs::symlink_metadata(&shortcuts).is_ok_and(|m| m.file_type().is_symlink()) {
        eprintln!(
            "Existing storage link left in place: {}",
            shortcuts.display()
        );
        return 1;
    }
    if let Err(error) = fs::create_dir_all(&shortcuts) {
        eprintln!("tpp-setup-storage: {error}");
        return 1;
    }
    for (target, name) in [
        ("", "shared"),
        ("DCIM", "dcim"),
        ("Documents", "documents"),
        ("Download", "downloads"),
        ("Movies", "movies"),
        ("Music", "music"),
        ("Pictures", "pictures"),
    ] {
        let destination = shortcuts.join(name);
        let source = if target.is_empty() {
            shared.to_path_buf()
        } else {
            shared.join(target)
        };
        if fs::symlink_metadata(&destination).is_ok() {
            if fs::read_link(&destination).is_ok_and(|link| link == source) {
                continue;
            }
            eprintln!("Existing path left in place: {}", destination.display());
            continue;
        }
        if let Err(error) = symlink(&source, &destination) {
            eprintln!("tpp-setup-storage: {error}");
            return 1;
        }
    }
    println!(
        "Android storage shortcuts are available in {}",
        shortcuts.display()
    );
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parity_child() {
        if let Some(root) = std::env::var_os("TPP_STORAGE_PARITY_CHILD") {
            use std::io::Write;
            let root = PathBuf::from(root);
            println!("TPP_PARITY_OUTPUT_START");
            std::io::stdout().flush().unwrap();
            std::process::exit(setup(&root.join("shared"), &root.join("home"), 0));
        }
    }

    #[test]
    fn filesystem_matches_shell() {
        let root = std::env::temp_dir().join(format!("tpp-storage-diff-{}", std::process::id()));
        fs::create_dir(&root).unwrap();
        fs::create_dir(root.join("shared")).unwrap();
        let source = fs::read_to_string(
            Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/oracle/setup-storage.sh"),
        )
        .unwrap();
        let original = "local shared=\"/android/storage/emulated/$android_user\"";
        assert!(source.contains(original));
        let oracle = source.replace(original, "local shared=\"$TPP_TEST_SHARED\"");
        for case in ["new", "conflicts", "storage-link"] {
            let home = root.join("home");
            fs::create_dir(&home).unwrap();
            let prepare = || match case {
                "conflicts" => {
                    fs::create_dir(home.join("storage")).unwrap();
                    symlink(root.join("shared"), home.join("storage/shared")).unwrap();
                    fs::write(home.join("storage/music"), b"keep").unwrap();
                    symlink("missing", home.join("storage/pictures")).unwrap();
                }
                "storage-link" => symlink("missing", home.join("storage")).unwrap(),
                _ => {}
            };
            prepare();
            let shell = Command::new("bash")
                .arg("-c")
                .arg(&oracle)
                .env("HOME", &home)
                .env("TPP_TEST_SHARED", root.join("shared"))
                .output()
                .unwrap();
            let snapshot = storage_snapshot(&home.join("storage"));
            fs::remove_dir_all(&home).unwrap();
            fs::create_dir(&home).unwrap();
            prepare();
            let rust = Command::new(std::env::current_exe().unwrap())
                .args(["--exact", "storage::tests::parity_child", "--nocapture"])
                .env("TPP_STORAGE_PARITY_CHILD", &root)
                .output()
                .unwrap();
            let marker = b"TPP_PARITY_OUTPUT_START\n";
            let offset = rust
                .stdout
                .windows(marker.len())
                .position(|w| w == marker)
                .unwrap();
            assert_eq!(
                &rust.stdout[offset + marker.len()..],
                shell.stdout,
                "{case}"
            );
            assert_eq!(rust.stderr, shell.stderr, "{case}");
            assert_eq!(rust.status.code(), shell.status.code(), "{case}");
            assert_eq!(storage_snapshot(&home.join("storage")), snapshot, "{case}");
            fs::remove_dir_all(&home).unwrap();
        }
        fs::remove_dir_all(root).unwrap();
    }

    fn storage_snapshot(path: &Path) -> Vec<(String, String)> {
        let metadata = match fs::symlink_metadata(path) {
            Ok(metadata) => metadata,
            Err(_) => return vec![],
        };
        if metadata.file_type().is_symlink() {
            return vec![(
                "storage".into(),
                format!("link:{:?}", fs::read_link(path).unwrap()),
            )];
        }
        let mut entries = fs::read_dir(path)
            .unwrap()
            .map(|entry| {
                let entry = entry.unwrap();
                let path = entry.path();
                let value = if entry.file_type().unwrap().is_symlink() {
                    format!("link:{:?}", fs::read_link(path).unwrap())
                } else {
                    format!("file:{:?}", fs::read(path).unwrap())
                };
                (entry.file_name().to_string_lossy().into_owned(), value)
            })
            .collect::<Vec<_>>();
        entries.sort();
        entries
    }

    #[test]
    fn creates_links_and_preserves_conflicts() {
        let root = std::env::temp_dir().join(format!("tpp-storage-test-{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let shared = root.join("shared");
        let home = root.join("home");
        fs::create_dir_all(&shared).unwrap();
        fs::create_dir_all(home.join("storage")).unwrap();
        fs::write(home.join("storage/music"), b"keep").unwrap();
        symlink("missing", home.join("storage/pictures")).unwrap();
        assert_eq!(setup(&shared, &home, 0), 0);
        assert_eq!(fs::read_link(home.join("storage/shared")).unwrap(), shared);
        assert_eq!(
            fs::read_link(home.join("storage/dcim")).unwrap(),
            shared.join("DCIM")
        );
        assert_eq!(fs::read(home.join("storage/music")).unwrap(), b"keep");
        assert_eq!(
            fs::read_link(home.join("storage/pictures")).unwrap(),
            Path::new("missing")
        );
        assert_eq!(setup(&shared, &home, 0), 0);
        fs::remove_dir_all(root).unwrap();
    }
}
