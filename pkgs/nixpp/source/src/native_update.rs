//! Install the native nixpp, the launcher and the bootstrap tools that the
//! selected generation ships.
//!
//! All of them came only with the APK's bootstrap, so existing installs never
//! received newer ones. A generation now carries them as `native/nixpp`,
//! `native/launcher` and `bootstrap/{busybox,nix}`. After a switch the daemon
//! installs the nixpp when it differs from its own executable and restarts
//! through the start script. The launcher is replaced in place once it has
//! run the bootstrap busybox, also when exec'd under the current launcher:
//! guest processes keep the launcher they started with, and new ones (and
//! every re-exec) use the new file. The bootstrap tools (session.env, /bin/sh,
//! /usr/bin/env) move once both ran through the launcher.
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
    smoke_test(&runtime.join("launcher"), source, &busybox, runtime)?;
    crate::bootstrap::replace_with(runtime, "launcher", 0o700, |file| {
        std::io::copy(&mut fs::File::open(source)?, file).map(|_| ())
    })
    .context("failed to replace the runtime launcher")
}

/// The bootstrap tools the selected generation ships (`bootstrap/busybox`,
/// `bootstrap/nix`: store roots), when they differ from the runtime's
/// (`BOOTSTRAP_BUSYBOX`, `BOOTSTRAP_NIX` in session.env). `checked` works as
/// for [`pending`].
pub(crate) fn tools_pending(runtime: &Path, checked: &mut Option<String>) -> Option<Tools> {
    let name = selected(runtime)?;
    if checked.as_deref() == Some(name.as_str()) {
        return None;
    }
    *checked = Some(name.clone());
    let generation = runtime.join("root/nix/store").join(&name);
    let shipped = |tool: &str| {
        let target = fs::read_link(generation.join("bootstrap").join(tool)).ok()?;
        let target = target.to_str()?.to_owned();
        crate::store::is_store_root(&target).then_some(target)
    };
    let tools = Tools {
        busybox: shipped("busybox")?,
        nix: shipped("nix")?,
    };
    let environment = fs::read_to_string(runtime.join("session.env")).ok()?;
    let current = |key: &str| {
        environment
            .lines()
            .find_map(|line| line.strip_prefix(key)?.strip_prefix('='))
            .map(str::to_owned)
    };
    (current("BOOTSTRAP_BUSYBOX").as_deref() != Some(tools.busybox.as_str())
        || current("BOOTSTRAP_NIX").as_deref() != Some(tools.nix.as_str()))
    .then_some(tools)
}

/// Store roots of the guest's bootstrap busybox and Nix.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Tools {
    pub busybox: String,
    pub nix: String,
}

/// Make `tools` the runtime's bootstrap tools once both ran through the
/// launcher: point session.env at them (read by every new session), root
/// them, move /bin/sh and /usr/bin/env from the old busybox to the new one
/// (links pointing elsewhere were changed on purpose and stay), and drop the
/// roots of the old tools.
pub(crate) fn install_tools(runtime: &Path, tools: &Tools) -> Result<()> {
    let root = runtime.join("root");
    let host = |store: &str, file: &str| root.join(store.trim_start_matches('/')).join(file);
    let launcher = runtime.join("launcher");
    for (binary, args) in [
        (host(&tools.busybox, "bin/busybox"), ["busybox", "true"]),
        (host(&tools.nix, "bin/nix"), ["nix", "--version"]),
    ] {
        let child = Command::new(&launcher)
            .arg(&binary)
            .args(args)
            .env_clear()
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .with_context(|| format!("cannot run {}", launcher.display()))?;
        wait_ok(child).with_context(|| format!("{} does not run", binary.display()))?;
    }

    let path = runtime.join("session.env");
    let environment = fs::read_to_string(&path)?;
    let mut old = Vec::new();
    let mut lines = Vec::new();
    for line in environment.lines() {
        let (key, value) = line.split_once('=').unwrap_or((line, ""));
        let new = match key {
            "BOOTSTRAP_BUSYBOX" => &tools.busybox,
            "BOOTSTRAP_NIX" => &tools.nix,
            _ => {
                lines.push(line.to_owned());
                continue;
            }
        };
        old.push(value.to_owned());
        lines.push(format!("{key}={new}"));
    }
    anyhow::ensure!(old.len() == 2, "session.env lacks the bootstrap tools");
    let mut content = lines.join("\n");
    content.push('\n');
    crate::bootstrap::replace_with(runtime, "session.env", 0o600, |file| {
        std::io::Write::write_all(file, content.as_bytes())
    })?;
    crate::bootstrap::root_bootstrap_tools(runtime)?;

    let old_busybox = old
        .iter()
        .find(|path| path.contains("-busybox-static-"))
        .cloned()
        .unwrap_or_default();
    for (link, file) in [("bin/sh", "bin/sh"), ("usr/bin/env", "bin/env")] {
        let link = root.join(link);
        let current = fs::read_link(&link).ok();
        if current.as_deref() == Some(Path::new(&format!("{old_busybox}/{file}"))) {
            replace_link(&link, &format!("{}/{file}", tools.busybox))?;
        }
    }

    // What still needs a root: the new tools and any changed shell link.
    let keep = crate::bootstrap::root_bootstrap_tools(runtime)?;
    let roots = root.join(crate::bootstrap::BOOTSTRAP_GCROOT_DIR);
    for path in old {
        if !keep.contains(&path)
            && let Some(name) = path.strip_prefix("/nix/store/")
        {
            let _ = fs::remove_file(roots.join(name));
        }
    }
    Ok(())
}

/// Point the symlink `link` at `target`, atomically.
fn replace_link(link: &Path, target: &str) -> Result<()> {
    let staged = link.with_extension(format!("nixpp-{}", std::process::id()));
    let _ = fs::remove_file(&staged);
    std::os::unix::fs::symlink(target, &staged)
        .with_context(|| format!("cannot create {}", staged.display()))?;
    fs::rename(&staged, link).with_context(|| format!("cannot replace {}", link.display()))
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

/// Check that `candidate` can take over from `current`, the launcher every
/// running guest process was started by:
///
/// - on its own, as a new session starts it: it runs `busybox true`;
/// - exec'd from a process under `current`'s seccomp filter, which survives
///   execve: `current` starts busybox's shell from a scratch copy, the copy is
///   replaced by `candidate`, and the shell's `exec busybox true` re-execs
///   that path. A candidate whose exempt syscall stub moved dies there with
///   SIGSYS, as it would on every exec of a running guest.
///
/// `scratch_parent` holds the scratch copy; it must allow exec.
fn smoke_test(
    current: &Path,
    candidate: &Path,
    busybox: &Path,
    scratch_parent: &Path,
) -> Result<()> {
    let alone = Command::new(candidate)
        .arg(busybox)
        .args(["busybox", "true"])
        .env_clear()
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .with_context(|| format!("cannot run {}", candidate.display()))?;
    wait_ok(alone).context("the new launcher failed to run busybox")?;

    let scratch = Scratch::new(scratch_parent)?;
    let probe = scratch.0.join("launcher");
    copy_executable(current, &probe)?;
    let mut shell = Command::new(&probe)
        .arg(busybox)
        .args(["busybox", "sh", "-c", "read line && exec \"$0\" true"])
        .arg(busybox)
        .env_clear()
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .with_context(|| format!("cannot run {}", probe.display()))?;
    let staged = scratch.0.join("launcher.new");
    let swapped = copy_executable(candidate, &staged)
        .and_then(|()| fs::rename(&staged, &probe).context("cannot swap in the new launcher"));
    // Release the shell either way, so it never outlives the check.
    let released = shell
        .stdin
        .take()
        .map(|mut stdin| std::io::Write::write_all(&mut stdin, b"go\n"));
    swapped?;
    if let Some(Err(error)) = released {
        let _ = shell.kill();
        let _ = shell.wait();
        return Err(error).context("cannot release the probe shell");
    }
    wait_ok(shell)
        .context("the new launcher failed when exec'd under the current launcher's seccomp filter")
}

/// Wait up to [`SMOKE_TIMEOUT`] for `child`; it must exit 0.
fn wait_ok(mut child: std::process::Child) -> Result<()> {
    let deadline = Instant::now() + SMOKE_TIMEOUT;
    loop {
        if let Some(status) = child.try_wait()? {
            anyhow::ensure!(status.success(), "exit status {status}");
            return Ok(());
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            anyhow::bail!("no exit within {SMOKE_TIMEOUT:?}");
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn copy_executable(source: &Path, target: &Path) -> Result<()> {
    use std::os::unix::fs::OpenOptionsExt;
    let mut output = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o700)
        .open(target)
        .with_context(|| format!("cannot create {}", target.display()))?;
    std::io::copy(&mut fs::File::open(source)?, &mut output)?;
    output.sync_all()?;
    Ok(())
}

/// A private scratch directory, removed on drop.
struct Scratch(PathBuf);

impl Scratch {
    fn new(parent: &Path) -> Result<Self> {
        use std::os::unix::fs::DirBuilderExt;
        let path = parent.join(format!(".launcher-check.{}", std::process::id()));
        let _ = fs::remove_dir_all(&path);
        fs::DirBuilder::new()
            .mode(0o700)
            .create(&path)
            .with_context(|| format!("cannot create {}", path.display()))?;
        Ok(Self(path))
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
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
    /// `current` as its launcher and a bootstrap busybox root. The launchers
    /// and busybox are shell scripts that emulate the re-exec: `current`
    /// records its own path, and busybox's `true` re-execs through it with
    /// `--reexec`, as a real launcher re-execs /proc/self/exe.
    fn launcher_fixture(launcher: &str, current: &str) -> (tempfile::TempDir, PathBuf) {
        use std::os::unix::fs::PermissionsExt;
        let executable = |path: &Path, content: &str| {
            fs::write(path, content).unwrap();
            fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
        };
        let (directory, runtime, name) = fixture(b"nixpp");
        let store = runtime.join("root/nix/store");
        let package = format!("{}-launcher", "c".repeat(32));
        fs::create_dir_all(store.join(&package).join("bin")).unwrap();
        executable(&store.join(&package).join("bin/launcher"), launcher);
        symlink(
            format!("/nix/store/{package}/bin/launcher"),
            store.join(&name).join("native/launcher"),
        )
        .unwrap();
        executable(&runtime.join("launcher"), current);
        let busybox = format!("{}-busybox-static-1.36", "d".repeat(32));
        fs::create_dir_all(store.join(&busybox).join("bin")).unwrap();
        executable(&store.join(&busybox).join("bin/busybox"), BUSYBOX);
        let roots = runtime.join("root/nix/var/nix/gcroots/termuxpp-bootstrap");
        fs::create_dir_all(&roots).unwrap();
        symlink(format!("/nix/store/{busybox}"), roots.join(&busybox)).unwrap();
        (directory, runtime)
    }

    const BUSYBOX: &str = "#!/bin/sh\ncase \"$1\" in\n\
        sh) shift; exec /bin/sh \"$@\" ;;\n\
        true) [ -n \"$LAUNCHER\" ] && exec \"$LAUNCHER\" --reexec; exit 0 ;;\n\
        esac\nexit 2\n";
    // TARGET ARGV0 ARGS, recording its own path for busybox's re-exec.
    const CURRENT: &str = "#!/bin/sh\n[ \"$1\" = --reexec ] && exit 0\nt=$1; shift 2; LAUNCHER=$0 exec \"$t\" \"$@\"\n";
    const GOOD: &str =
        "#!/bin/sh\n# new\n[ \"$1\" = --reexec ] && exit 0\nt=$1; shift 2; exec \"$t\" \"$@\"\n";
    // Works on its own, dies like a moved syscall stub when re-exec'd.
    const MOVED: &str =
        "#!/bin/sh\n[ \"$1\" = --reexec ] && kill -SYS $$\nt=$1; shift 2; exec \"$t\" \"$@\"\n";

    #[test]
    fn a_working_launcher_replaces_the_runtime_launcher_once() {
        let (_directory, runtime) = launcher_fixture(GOOD, CURRENT);
        let mut checked = None;
        let source = launcher_pending(&runtime, &mut checked).unwrap();
        assert_eq!(launcher_pending(&runtime, &mut checked), None);
        install_launcher(&runtime, &source).unwrap();
        assert_eq!(fs::read(runtime.join("launcher")).unwrap(), GOOD.as_bytes());
        assert_eq!(launcher_pending(&runtime, &mut None), None);
        let leftovers: Vec<_> = fs::read_dir(&runtime)
            .unwrap()
            .flatten()
            .filter(|entry| {
                entry
                    .file_name()
                    .to_string_lossy()
                    .starts_with(".launcher-check")
            })
            .collect();
        assert!(leftovers.is_empty());
    }

    #[test]
    fn a_launcher_that_cannot_run_busybox_is_refused() {
        let (_directory, runtime) = launcher_fixture("#!/bin/sh\nexit 1\n", CURRENT);
        let source = launcher_pending(&runtime, &mut None).unwrap();
        assert!(install_launcher(&runtime, &source).is_err());
        assert_eq!(
            fs::read(runtime.join("launcher")).unwrap(),
            CURRENT.as_bytes()
        );
    }

    #[test]
    fn a_launcher_that_dies_under_the_current_filter_is_refused() {
        let (_directory, runtime) = launcher_fixture(MOVED, CURRENT);
        let source = launcher_pending(&runtime, &mut None).unwrap();
        let error = install_launcher(&runtime, &source).unwrap_err();
        assert!(format!("{error:#}").contains("seccomp filter"), "{error:#}");
        assert_eq!(
            fs::read(runtime.join("launcher")).unwrap(),
            CURRENT.as_bytes()
        );
    }

    #[test]
    fn the_same_launcher_is_no_update() {
        let (_directory, runtime) = launcher_fixture(GOOD, GOOD);
        assert_eq!(launcher_pending(&runtime, &mut None), None);
    }

    /// A runtime whose bootstrap tools are `old-*`, a generation shipping
    /// `new-*` ones, and pass-through launcher, busybox and nix scripts;
    /// `nix_ok` decides whether the new nix runs.
    fn tools_fixture(nix_ok: bool) -> (tempfile::TempDir, PathBuf, Tools) {
        use std::os::unix::fs::PermissionsExt;
        let executable = |path: &Path, content: &str| {
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, content).unwrap();
            fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
        };
        let (directory, runtime, name) = fixture(b"nixpp");
        let root = runtime.join("root");
        let store =
            |id: char, what: &str| format!("/nix/store/{}-{what}", id.to_string().repeat(32));
        let (old_busybox, old_nix) = (
            store('e', "busybox-static-1.36"),
            store('f', "nix-static-2.24"),
        );
        let tools = Tools {
            busybox: store('g', "busybox-static-1.37"),
            nix: store('h', "nix-static-2.35"),
        };
        for path in [&old_busybox, &tools.busybox] {
            executable(&root.join(&path[1..]).join("bin/busybox"), BUSYBOX);
        }
        executable(
            &root.join(&old_nix[1..]).join("bin/nix"),
            "#!/bin/sh\nexit 0\n",
        );
        executable(
            &root.join(&tools.nix[1..]).join("bin/nix"),
            if nix_ok {
                "#!/bin/sh\n[ \"$1\" = --version ]\n"
            } else {
                "#!/bin/sh\nexit 1\n"
            },
        );
        executable(&runtime.join("launcher"), CURRENT);
        let generation = root.join("nix/store").join(&name).join("bootstrap");
        fs::create_dir_all(&generation).unwrap();
        symlink(&tools.busybox, generation.join("busybox")).unwrap();
        symlink(&tools.nix, generation.join("nix")).unwrap();
        fs::write(
            runtime.join("session.env"),
            format!("SESSION_USER=nix\nBOOTSTRAP_NIX={old_nix}\nBOOTSTRAP_BUSYBOX={old_busybox}\nSESSION_SHELL=/bin/sh\n"),
        )
        .unwrap();
        fs::create_dir_all(root.join("bin")).unwrap();
        fs::create_dir_all(root.join("usr/bin")).unwrap();
        symlink(format!("{old_busybox}/bin/sh"), root.join("bin/sh")).unwrap();
        // Changed on purpose: stays.
        symlink("/run/current-system/sw/bin/env", root.join("usr/bin/env")).unwrap();
        crate::bootstrap::root_bootstrap_tools(&runtime).unwrap();
        (directory, runtime, tools)
    }

    #[test]
    fn new_bootstrap_tools_replace_the_old_ones() {
        let (_directory, runtime, tools) = tools_fixture(true);
        let mut checked = None;
        assert_eq!(tools_pending(&runtime, &mut checked).as_ref(), Some(&tools));
        assert_eq!(tools_pending(&runtime, &mut checked), None);
        install_tools(&runtime, &tools).unwrap();
        let environment = fs::read_to_string(runtime.join("session.env")).unwrap();
        assert!(environment.contains(&format!("BOOTSTRAP_NIX={}\n", tools.nix)));
        assert!(environment.contains(&format!("BOOTSTRAP_BUSYBOX={}\n", tools.busybox)));
        assert!(
            environment.starts_with("SESSION_USER=nix\n")
                && environment.ends_with("SESSION_SHELL=/bin/sh\n")
        );
        let root = runtime.join("root");
        assert_eq!(
            fs::read_link(root.join("bin/sh")).unwrap(),
            Path::new(&format!("{}/bin/sh", tools.busybox))
        );
        assert_eq!(
            fs::read_link(root.join("usr/bin/env")).unwrap(),
            Path::new("/run/current-system/sw/bin/env")
        );
        let mut roots: Vec<_> = fs::read_dir(root.join(crate::bootstrap::BOOTSTRAP_GCROOT_DIR))
            .unwrap()
            .flatten()
            .map(|entry| entry.file_name().into_string().unwrap())
            .collect();
        roots.sort();
        let mut expected = vec![
            tools.busybox.trim_start_matches("/nix/store/").to_owned(),
            tools.nix.trim_start_matches("/nix/store/").to_owned(),
        ];
        expected.sort();
        assert_eq!(roots, expected);
        assert_eq!(tools_pending(&runtime, &mut None), None);
    }

    #[test]
    fn bootstrap_tools_that_do_not_run_change_nothing() {
        let (_directory, runtime, tools) = tools_fixture(false);
        let before = fs::read(runtime.join("session.env")).unwrap();
        let link = fs::read_link(runtime.join("root/bin/sh")).unwrap();
        assert!(install_tools(&runtime, &tools).is_err());
        assert_eq!(fs::read(runtime.join("session.env")).unwrap(), before);
        assert_eq!(fs::read_link(runtime.join("root/bin/sh")).unwrap(), link);
    }

    /// The real thing, on aarch64 Linux: NIXPP_LAUNCHER_ABI_DIR holds the
    /// deployed launcher (`old`), one with a moved stub (`moved`), one with
    /// the pinned stub (`pinned`) and a static `busybox`.
    #[test]
    #[ignore = "needs real launchers: set NIXPP_LAUNCHER_ABI_DIR"]
    fn real_launchers_across_versions() {
        let dir = PathBuf::from(std::env::var_os("NIXPP_LAUNCHER_ABI_DIR").unwrap());
        let scratch = tempfile::tempdir().unwrap();
        let busybox = dir.join("busybox");
        let check = |candidate: &str| {
            smoke_test(
                &dir.join("old"),
                &dir.join(candidate),
                &busybox,
                scratch.path(),
            )
        };
        check("old").unwrap();
        check("pinned").unwrap();
        let error = check("moved").unwrap_err();
        assert!(format!("{error:#}").contains("seccomp filter"), "{error:#}");
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
