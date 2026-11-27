//! Install the native nixpp and launcher that the selected generation ships.
//!
//! Both came only with the APK's bootstrap, so existing installs never
//! received newer ones. A generation now carries them as `native/nixpp` and
//! `native/launcher`. After a switch the daemon installs the nixpp when it
//! differs from its own executable and restarts through the start script.
//! The launcher is replaced in place once it has run the bootstrap busybox:
//! guest processes keep the launcher they started with, and new ones (and
//! every re-exec) use the new file.

use anyhow::{Context, Result};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// How long the candidate launcher may take to run busybox's `true`.
const SMOKE_TIMEOUT: Duration = Duration::from_secs(20);

/// The native nixpp of the selected generation, when it differs from
/// `current`. `checked` remembers the generation already looked at, so the
/// executables are hashed once per switch rather than on every call.
pub(crate) fn pending(
    runtime: &Path,
    current: &Path,
    checked: &mut Option<String>,
) -> Option<PathBuf> {
    let name = selected(runtime)?;
    if checked.as_deref() == Some(name.as_str()) {
        return None;
    }
    *checked = Some(name.clone());
    let source = shipped(runtime, &name, "nixpp")?;
    (digest(&source).ok()? != digest(current).ok()?).then_some(source)
}

/// The launcher of the selected generation, when it differs from
/// `runtime/launcher`. `checked` works as for [`pending`].
pub(crate) fn launcher_pending(runtime: &Path, checked: &mut Option<String>) -> Option<PathBuf> {
    let name = selected(runtime)?;
    if checked.as_deref() == Some(name.as_str()) {
        return None;
    }
    *checked = Some(name.clone());
    let source = shipped(runtime, &name, "launcher")?;
    (digest(&source).ok()? != digest(&runtime.join("launcher")).ok()?).then_some(source)
}

/// Replace `runtime/launcher` with `source` once `source` has started the
/// bootstrap busybox. A launcher that cannot would lock every session out of
/// the guest, so it is refused and the current one stays.
pub(crate) fn install_launcher(runtime: &Path, source: &Path) -> Result<()> {
    let busybox = bootstrap_busybox(runtime).context("the bootstrap busybox is missing")?;
    smoke_test(source, &busybox)?;
    crate::bootstrap::replace_with(runtime, "launcher", 0o700, |file| {
        std::io::copy(&mut fs::File::open(source)?, file).map(|_| ())
    })
    .context("failed to replace the runtime launcher")
}

/// The static busybox of the bootstrap seed, rooted under
/// nix/var/nix/gcroots/termuxpp-bootstrap, as a native path.
fn bootstrap_busybox(runtime: &Path) -> Option<PathBuf> {
    let root = runtime.join("root");
    fs::read_dir(root.join("nix/var/nix/gcroots/termuxpp-bootstrap"))
        .ok()?
        .flatten()
        .filter_map(|entry| entry.file_name().into_string().ok())
        .filter(|name| name.contains("-busybox-static-"))
        .map(|name| root.join("nix/store").join(name).join("bin/busybox"))
        .find(|path| path.is_file())
}

/// Run `busybox true` through `launcher`, with no binds and an empty
/// environment; it must exit 0 within [`SMOKE_TIMEOUT`].
fn smoke_test(launcher: &Path, busybox: &Path) -> Result<()> {
    let mut child = Command::new(launcher)
        .arg(busybox)
        .args(["busybox", "true"])
        .env_clear()
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .with_context(|| format!("cannot run {}", launcher.display()))?;
    let deadline = Instant::now() + SMOKE_TIMEOUT;
    loop {
        if let Some(status) = child.try_wait()? {
            anyhow::ensure!(
                status.success(),
                "the new launcher failed to run busybox: {status}"
            );
            return Ok(());
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            anyhow::bail!("the new launcher did not run busybox within {SMOKE_TIMEOUT:?}");
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// Replace `runtime/nixpp` and, when present, `$PREFIX/bin/nixpp` with
/// `source`. Running processes keep their open executable.
pub(crate) fn install(runtime: &Path, source: &Path, prefix: Option<&Path>) -> Result<()> {
    crate::bootstrap::stage_executable_from(runtime, source)?;
    if let Some(prefix) = prefix {
        let bin = prefix.join("bin");
        if bin.join("nixpp").is_file() {
            crate::bootstrap::replace_with(&bin, "nixpp", 0o755, |file| {
                std::io::copy(&mut fs::File::open(source)?, file).map(|_| ())
            })
            .with_context(|| format!("failed to update {}", bin.join("nixpp").display()))?;
        }
    }
    Ok(())
}

fn selected(runtime: &Path) -> Option<String> {
    let text = fs::read_to_string(runtime.join("current-system")).ok()?;
    let name = text.strip_suffix('\n')?;
    crate::store::is_candidate_name(name).then(|| name.to_owned())
}

/// `native/<file>` of generation `name` as a native path. The link targets
/// the guest's /nix/store, which lives under `runtime/root`.
fn shipped(runtime: &Path, name: &str, file: &str) -> Option<PathBuf> {
    let link = runtime
        .join("root/nix/store")
        .join(name)
        .join("native")
        .join(file);
    let target = fs::read_link(&link).ok()?;
    let source = match target.strip_prefix("/") {
        Ok(relative) => runtime.join("root").join(relative),
        Err(_) => link.parent()?.join(target),
    };
    source.is_file().then_some(source)
}

fn digest(path: &Path) -> std::io::Result<[u8; 32]> {
    let mut hasher = Sha256::new();
    std::io::copy(&mut fs::File::open(path)?, &mut hasher)?;
    Ok(hasher.finalize().into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;

    fn fixture(content: &[u8]) -> (tempfile::TempDir, PathBuf, String) {
        let directory = tempfile::tempdir().unwrap();
        let runtime = directory.path().join("runtime");
        let name = format!("{}-termuxpp-system-test", "a".repeat(32));
        let package = format!("{}-nixpp-runtime-termux-0.5.0", "b".repeat(32));
        let store = runtime.join("root/nix/store");
        fs::create_dir_all(store.join(&name).join("native")).unwrap();
        fs::create_dir_all(store.join(&package).join("bin")).unwrap();
        fs::write(store.join(&package).join("bin/nixpp"), content).unwrap();
        symlink(
            format!("/nix/store/{package}/bin/nixpp"),
            store.join(&name).join("native/nixpp"),
        )
        .unwrap();
        fs::write(runtime.join("current-system"), format!("{name}\n")).unwrap();
        (directory, runtime, name)
    }

    #[test]
    fn same_executable_is_no_update() {
        let (directory, runtime, _) = fixture(b"same");
        let current = directory.path().join("current");
        fs::write(&current, b"same").unwrap();
        let mut checked = None;
        assert_eq!(pending(&runtime, &current, &mut checked), None);
    }

    #[test]
    fn a_different_executable_is_installed_once_per_generation() {
        let (directory, runtime, name) = fixture(b"new");
        let current = directory.path().join("current");
        fs::write(&current, b"old").unwrap();
        let mut checked = None;
        let source = pending(&runtime, &current, &mut checked).unwrap();
        assert!(source.starts_with(runtime.join("root/nix/store")));
        assert_eq!(checked.as_deref(), Some(name.as_str()));
        assert_eq!(pending(&runtime, &current, &mut checked), None);

        let prefix = directory.path().join("usr");
        fs::create_dir_all(prefix.join("bin")).unwrap();
        fs::write(prefix.join("bin/nixpp"), b"old").unwrap();
        install(&runtime, &source, Some(&prefix)).unwrap();
        assert_eq!(fs::read(runtime.join("nixpp")).unwrap(), b"new");
        assert_eq!(fs::read(prefix.join("bin/nixpp")).unwrap(), b"new");
    }

    /// A generation shipping `launcher` (as native/launcher), a runtime with
    /// `current` as its launcher and a bootstrap busybox root.
    fn launcher_fixture(launcher: &str, current: &[u8]) -> (tempfile::TempDir, PathBuf) {
        use std::os::unix::fs::PermissionsExt;
        let (directory, runtime, name) = fixture(b"nixpp");
        let store = runtime.join("root/nix/store");
        let package = format!("{}-launcher", "c".repeat(32));
        fs::create_dir_all(store.join(&package).join("bin")).unwrap();
        let file = store.join(&package).join("bin/launcher");
        fs::write(&file, launcher).unwrap();
        fs::set_permissions(&file, fs::Permissions::from_mode(0o755)).unwrap();
        symlink(
            format!("/nix/store/{package}/bin/launcher"),
            store.join(&name).join("native/launcher"),
        )
        .unwrap();
        fs::write(runtime.join("launcher"), current).unwrap();
        let busybox = format!("{}-busybox-static-1.36", "d".repeat(32));
        fs::create_dir_all(store.join(&busybox).join("bin")).unwrap();
        fs::write(store.join(&busybox).join("bin/busybox"), b"").unwrap();
        let roots = runtime.join("root/nix/var/nix/gcroots/termuxpp-bootstrap");
        fs::create_dir_all(&roots).unwrap();
        symlink(format!("/nix/store/{busybox}"), roots.join(&busybox)).unwrap();
        (directory, runtime)
    }

    // Runs busybox's `true` the way the launcher is called: TARGET ARGV0 ARGS.
    const GOOD: &str = "#!/bin/sh\n[ -f \"$1\" ] && [ \"$2 $3\" = \"busybox true\" ]\n";

    #[test]
    fn a_working_launcher_replaces_the_runtime_launcher_once() {
        let (_directory, runtime) = launcher_fixture(GOOD, b"old");
        let mut checked = None;
        let source = launcher_pending(&runtime, &mut checked).unwrap();
        assert_eq!(launcher_pending(&runtime, &mut checked), None);
        install_launcher(&runtime, &source).unwrap();
        assert_eq!(fs::read(runtime.join("launcher")).unwrap(), GOOD.as_bytes());
        assert_eq!(launcher_pending(&runtime, &mut None), None);
    }

    #[test]
    fn a_launcher_that_cannot_run_busybox_is_refused() {
        let (_directory, runtime) = launcher_fixture("#!/bin/sh\nexit 1\n", b"old");
        let source = launcher_pending(&runtime, &mut None).unwrap();
        assert!(install_launcher(&runtime, &source).is_err());
        assert_eq!(fs::read(runtime.join("launcher")).unwrap(), b"old");
    }

    #[test]
    fn the_same_launcher_is_no_update() {
        let (_directory, runtime) = launcher_fixture(GOOD, GOOD.as_bytes());
        assert_eq!(launcher_pending(&runtime, &mut None), None);
    }

    #[test]
    fn a_generation_without_native_nixpp_is_no_update() {
        let (directory, runtime, name) = fixture(b"new");
        fs::remove_file(
            runtime
                .join("root/nix/store")
                .join(&name)
                .join("native/nixpp"),
        )
        .unwrap();
        let current = directory.path().join("current");
        fs::write(&current, b"old").unwrap();
        assert_eq!(pending(&runtime, &current, &mut None), None);
    }
}
