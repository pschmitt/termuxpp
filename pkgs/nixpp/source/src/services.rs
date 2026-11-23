//! Native launcher for the persistent guest runit supervisor.
//!
//! A guest launcher tears down its process namespace when a one-shot command
//! returns. Keep the service root in a separate, locked native process so
//! ordinary `nixpp enter -- svc ...` calls only control an existing runit tree.

use crate::error::{Error, Result};
use crate::fsutil::{FileLock, read_bounded, regular_file_size};
use crate::store::{is_candidate_name, is_store_root};
use std::ffi::{OsStr, OsString};
use std::fs::{self, OpenOptions};
use std::io;
use std::os::fd::AsRawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::thread::sleep;
use std::time::Duration;

const SERVICE_STATE: &str = "service-state";
const MANAGER_LOCK: &str = "manager.lock";
const SUPERVISOR_LOG: &str = "supervisor.log";
const SERVICE_COMMAND: &str = "/run/current-system/sw/bin/svc";

/// Run the native manager until its process is stopped.
///
/// The legacy separate supervisor process; `nixpp serve` now supervises the
/// guest services in a thread (see [`spawn`]).
pub fn run(runtime: &Path) -> Result<()> {
    let state = runtime.join(SERVICE_STATE);
    fs::create_dir_all(&state)?;
    let Some(lock) = FileLock::try_acquire(&state.join(MANAGER_LOCK))? else {
        return Ok(());
    };
    let log = open_log(&state)?;
    supervise(runtime, &log, None)?;
    drop(lock);
    crate::runtime_apk::restart(runtime, &["--services"])
}

/// Supervise the guest services from a thread of the native daemon.
///
/// The thread waits for the manager lock (an older, separate supervisor may
/// still hold it) and then keeps the guest's `svc supervise` running. It
/// passes the daemon's PID along, so the guest tree retires when the daemon
/// dies; a daemon restarted by exec keeps its PID, and the guest supervisor
/// retires on the changed APK marker by itself.
pub fn spawn(runtime: &Path) -> Result<()> {
    let runtime = runtime.to_owned();
    std::thread::Builder::new()
        .name("services".into())
        .spawn(move || {
            let state = runtime.join(SERVICE_STATE);
            let lock = loop {
                match fs::create_dir_all(&state)
                    .map_err(Error::from)
                    .and_then(|()| FileLock::try_acquire(&state.join(MANAGER_LOCK)))
                {
                    Ok(Some(lock)) => break lock,
                    Ok(None) => sleep(Duration::from_secs(5)),
                    Err(error) => {
                        eprintln!("nixpp: service supervision unavailable: {error}");
                        sleep(Duration::from_secs(30));
                    }
                }
            };
            let _lock = lock;
            let owner = std::process::id();
            loop {
                let result =
                    open_log(&state).and_then(|log| supervise(&runtime, &log, Some(owner)));
                if let Err(error) = result {
                    eprintln!("nixpp: service supervision failed: {error}");
                    sleep(Duration::from_secs(5));
                }
            }
        })?;
    Ok(())
}

fn open_log(state: &Path) -> Result<fs::File> {
    Ok(OpenOptions::new()
        .append(true)
        .create(true)
        .custom_flags(libc::O_NOFOLLOW)
        .mode(0o600)
        .open(state.join(SUPERVISOR_LOG))?)
}

/// A retiring launcher must stay alive while its SSH sessions still use it.
/// The guest supervisor lock serializes release of the listeners and startup
/// of the replacement. Exit 76 is retried when that lock is still held.
fn supervise(runtime: &Path, log: &fs::File, owner: Option<u32>) -> Result<()> {
    let mut active: Option<((String, OsString), Child)> = None;
    let mut retiring: Vec<Child> = Vec::new();
    let mut watch = crate::watch::Watch::new(
        runtime,
        vec![b"current-system".to_vec(), b"apk-library".to_vec()],
    );
    loop {
        if owner.is_none() && crate::runtime_apk::replaced(runtime) {
            return Ok(());
        }
        let mut index = 0;
        while index < retiring.len() {
            if retiring[index].try_wait()?.is_some() {
                let mut child = retiring.swap_remove(index);
                child.wait()?;
            } else {
                index += 1;
            }
        }
        if let Some((_, child)) = &mut active
            && child.try_wait()?.is_some()
        {
            active = None;
        }
        match selected_candidate(runtime) {
            Ok(candidate) => {
                let selected = candidate.map(|(name, _)| {
                    (
                        name,
                        crate::runtime_apk::marker(runtime).unwrap_or_default(),
                    )
                });
                if active.as_ref().map(|(selection, _)| selection) != selected.as_ref() {
                    if let Some((_, child)) = active.take() {
                        retiring.push(child);
                    }
                    if let Some(selection) = selected {
                        match launch(runtime, log, owner, &selection.0, &selection.1) {
                            Ok(child) => active = Some((selection, child)),
                            Err(error) => {
                                eprintln!("nixpp: could not launch guest services: {error}")
                            }
                        }
                    }
                }
            }
            Err(error) => {
                eprintln!("nixpp: could not read the selected service generation: {error}")
            }
        }
        let wait_seconds = if active.is_some() { 5 } else { 1 };
        if let Some(watch) = &mut watch {
            let mut ready = libc::pollfd {
                fd: watch.fd().as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            };
            // SAFETY: poll borrows one initialized descriptor for a bounded wait.
            if unsafe { libc::poll(&mut ready, 1, wait_seconds * 1000) } > 0 {
                watch.drain();
            }
        } else {
            sleep(Duration::from_secs(wait_seconds as u64));
        }
    }
}

fn launch(
    runtime: &Path,
    log: &fs::File,
    owner: Option<u32>,
    name: &str,
    apk_marker: &OsStr,
) -> Result<Child> {
    Ok(Command::new(runtime.join("session.sh"))
        .arg("--system")
        .arg(name)
        .arg("--")
        // Fix the APK snapshot before entering the guest so a replacement
        // during startup also retires this service tree.
        .args([
            "/bin/sh",
            "-c",
            r#"export NIXPP_SVC_APK_LIBRARY="$1" NIXPP_SVC_OWNER_PID="$2"; shift 2; exec "$@""#,
            "svc",
        ])
        .arg(apk_marker)
        .arg(owner.map(|pid| pid.to_string()).unwrap_or_default())
        .arg(SERVICE_COMMAND)
        .arg("supervise")
        .stdin(Stdio::null())
        .stdout(Stdio::from(log.try_clone()?))
        .stderr(Stdio::from(log.try_clone()?))
        .spawn()?)
}

fn selected_candidate(runtime: &Path) -> Result<Option<(String, String)>> {
    let selector = runtime.join("current-system");
    if regular_file_size(&selector).is_none() {
        if fs::symlink_metadata(&selector)
            .is_err_and(|error| error.kind() == io::ErrorKind::NotFound)
        {
            return Ok(None);
        }
        return Err(Error::invalid("system selector is not a regular file"));
    }
    let selector_text = String::from_utf8(read_bounded(&selector, 4096)?)
        .map_err(|_| Error::invalid("system selector is not UTF-8"))?;
    if selector_text == "\n" {
        return Ok(None);
    }
    let name = selector_text
        .strip_suffix('\n')
        .filter(|name| !name.contains('\n') && is_candidate_name(name))
        .ok_or_else(|| Error::invalid("invalid system selector"))?;
    let candidate = runtime.join("system-candidates").join(name);
    if fs::symlink_metadata(runtime.join("system-candidates"))?
        .file_type()
        .is_symlink()
        || fs::symlink_metadata(&candidate)?.file_type().is_symlink()
    {
        return Err(Error::invalid("selected system candidate is a symlink"));
    }
    let generation_file = candidate.join("generation");
    if regular_file_size(&generation_file).is_none() {
        return Err(Error::invalid("selected system generation is unavailable"));
    }
    let generation_text = String::from_utf8(read_bounded(&generation_file, 4096)?)
        .map_err(|_| Error::invalid("system generation is not UTF-8"))?;
    let generation = generation_text
        .strip_suffix('\n')
        .filter(|path| is_store_root(path) && path.strip_prefix("/nix/store/") == Some(name))
        .ok_or_else(|| Error::invalid("invalid selected system generation"))?;
    Ok(Some((name.to_owned(), generation.to_owned())))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;
    use std::path::PathBuf;

    fn fixture() -> (tempfile::TempDir, PathBuf, String) {
        let directory = tempfile::tempdir().unwrap();
        let runtime = directory.path().join("runtime");
        let name = format!("{}-termuxpp-system-test", "a".repeat(32));
        let candidate = runtime.join("system-candidates").join(&name);
        fs::create_dir_all(&candidate).unwrap();
        fs::write(candidate.join("generation"), format!("/nix/store/{name}\n")).unwrap();
        fs::write(runtime.join("current-system"), format!("{name}\n")).unwrap();
        (directory, runtime, name)
    }

    #[test]
    fn reads_the_selected_generation() {
        let (_directory, runtime, name) = fixture();
        assert_eq!(
            selected_candidate(&runtime).unwrap(),
            Some((name.clone(), format!("/nix/store/{name}")))
        );
    }

    #[test]
    fn rejects_a_symlinked_candidate_tree() {
        let (_directory, runtime, name) = fixture();
        let candidate = runtime.join("system-candidates").join(&name);
        let target = runtime.join("candidate-target");
        fs::rename(&candidate, &target).unwrap();
        symlink(&target, &candidate).unwrap();
        assert!(selected_candidate(&runtime).is_err());
    }

    #[test]
    fn missing_selector_means_no_active_services() {
        let (_directory, runtime, _) = fixture();
        fs::remove_file(runtime.join("current-system")).unwrap();
        assert_eq!(selected_candidate(&runtime).unwrap(), None);
    }
}
