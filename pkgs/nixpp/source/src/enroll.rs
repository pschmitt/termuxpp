//! Enroll a new managed guest beside the current runtime.

use crate::lease::{self, Agent, Build, CacheOptions, Controller, Profile, Scratch, Side};
use crate::{bootstrap, controller, fsutil, guest, provision, runner::SessionRunner, store};
use anyhow::{Context, Result, ensure};
use sha2::{Digest, Sha256};
use std::fs::{self, DirBuilder};
use std::os::unix::fs::DirBuilderExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};

/// Where the first system comes from.
pub enum Origin {
    /// `nixpp bootstrap`: a build host that the credentials' ssh_config names.
    BuildHost,
    /// `nixpp setup`: a freshly leased rworker builds the latest flake.
    Lease(Box<LeaseOrigin>),
    /// `nixpp setup --published`: the system CI pinned in the binary cache.
    Published(Box<PublishedOrigin>),
}

pub struct LeaseOrigin {
    pub controller: Controller,
    pub profiles: Vec<Profile>,
    pub ttl: String,
    pub queue_timeout: std::time::Duration,
    /// `user@host`: names the leases of this phone.
    pub owner: String,
    pub ssh_inputs: Vec<String>,
    pub github_known_hosts: String,
    /// Put the key on runners that cannot forward the ssh-agent.
    pub allow_key_upload: bool,
}

pub struct PublishedOrigin {
    pub pin_url: String,
    pub region: String,
    pub store: String,
    pub public_keys: Vec<String>,
}

pub fn enroll(
    runtime: &Path,
    user: &str,
    flake: &str,
    host: &str,
    credentials: &Path,
) -> Result<()> {
    enroll_with(runtime, user, flake, host, credentials, &Origin::BuildHost)
}

pub fn enroll_with(
    runtime: &Path,
    user: &str,
    flake: &str,
    host: &str,
    credentials: &Path,
    origin: &Origin,
) -> Result<()> {
    ensure!(
        cfg!(target_os = "android"),
        "run bootstrap in a native TermuxPP session"
    );
    ensure!(runtime.is_absolute(), "the runtime path must be absolute");
    ensure!(store::is_user_name(user), "invalid guest user");
    let build = guest::build_command(flake, host, guest::Worker::Builders)?;
    let build_host = match origin {
        Origin::BuildHost => {
            let build_host = fsutil::read_bounded(&credentials.join("build_host"), 4096)
                .context("missing initial build worker")?;
            let build_host = std::str::from_utf8(&build_host)?.trim().to_owned();
            ensure!(
                store::is_host_name(&build_host),
                "invalid initial build worker SSH alias"
            );
            build_host
        }
        _ => String::new(),
    };
    let target = build.last().context("missing Nix build target")?;
    let parent = runtime
        .parent()
        .context("runtime requires a parent directory")?;
    fs::create_dir_all(parent)?;
    let staged = sibling(runtime, ".enrollment")?;
    let previous = sibling(runtime, ".before-enrollment")?;
    let _lock = fsutil::FileLock::acquire(&sibling(runtime, ".enrollment.lock")?)?;

    if runtime.join("system-state.json").is_file() {
        ensure!(
            fs::read_to_string(runtime.join("enrolled-host"))
                .unwrap_or_default()
                .trim()
                == host
                && fs::read_to_string(runtime.join("enrolled-user"))
                    .unwrap_or_default()
                    .trim()
                    == user,
            "this runtime is already managed; use nixpp rebuild in the guest"
        );
        println!("The guest is already enrolled. Open a new TermuxPP session.");
        return Ok(());
    }
    ensure!(
        runtime.try_exists()? || !previous.try_exists()? || staged.try_exists()?,
        "an earlier enrollment backup exists without a staged runtime"
    );
    ensure!(
        !runtime.try_exists()? || !previous.try_exists()?,
        "the enrollment backup path is already occupied"
    );

    let staged_config = staged.join("session.env");
    if staged_config.is_file() {
        ensure!(
            fs::read_to_string(staged_config)?
                .lines()
                .any(|line| line == format!("SESSION_USER={user}")),
            "an incomplete enrollment exists for a different user"
        );
    }
    match provision::setup_state(&staged) {
        provision::Setup::Ready => {}
        provision::Setup::Resume => bootstrap::resume(&staged)?,
        provision::Setup::Init => provision::init(
            &staged,
            provision::Inputs {
                seed: None,
                manifest: None,
                certificate: None,
                bootstrap_dir: None,
                user,
            },
        )?,
    }

    // Guest sockets are translated to native paths. The staging directory
    // suffix would push tmux and other Unix sockets past Android's limit.
    use_short_tmp(&staged, runtime)?;
    // Enrollment only writes into the newly created private guest home.
    let home = staged.join("root/home").join(user);
    let cache = EnrollmentCache::read(credentials)?;
    install_credentials(&home, credentials, matches!(origin, Origin::BuildHost))?;
    let activate_result = if !staged.join("current-system").is_file()
        || fs::read_to_string(staged.join("current-system"))?
            .trim()
            .is_empty()
        || staged.join("system-pending.json").exists()
    {
        activate(
            &staged,
            Activation {
                user,
                host,
                home: &home,
                credentials,
                origin,
                build_host: &build_host,
                target,
                cache: cache.as_ref(),
            },
        )
    } else {
        Ok(())
    };
    let cached_credentials = home.join(".config/nixpp-enrollment/cache_credentials");
    match fs::remove_file(cached_credentials) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error).context("remove temporary cache credentials"),
    }
    activate_result?;

    fs::remove_dir_all(
        staged
            .join("root/home")
            .join(user)
            .join(".config/nixpp-enrollment"),
    )
    .context("failed to remove initial worker SSH settings")?;
    fsutil::atomic_write(&staged.join("system.enabled"), "")?;
    fsutil::atomic_write(&staged.join("enrolled-host"), host)?;
    fsutil::atomic_write(&staged.join("enrolled-user"), user)?;
    if runtime.try_exists()? {
        fs::rename(runtime, &previous).context("failed to retain the previous guest")?;
    }
    if let Err(error) = fs::rename(&staged, runtime) {
        if previous.try_exists()? {
            fs::rename(&previous, runtime)
                .context("failed to restore previous guest after enrollment")?;
        }
        return Err(error).context("failed to select the enrolled guest");
    }
    println!("Enrollment complete. Open a new TermuxPP session.");
    Ok(())
}

fn sibling(path: &Path, suffix: &str) -> Result<PathBuf> {
    let mut name = path
        .file_name()
        .context("invalid runtime directory")?
        .to_os_string();
    name.push(suffix);
    Ok(path.with_file_name(name))
}

fn use_short_tmp(staged: &Path, runtime: &Path) -> Result<()> {
    let file = staged.join("session.env");
    let mut environment = fs::read_to_string(&file)?;
    if environment
        .lines()
        .any(|line| line.starts_with("SESSION_TMP_DIR="))
    {
        return Ok(());
    }
    let data = std::env::var_os("TERMUX_APP__DATA_DIR")
        .context("enrollment requires the TermuxPP app data directory")?;
    let files = fs::canonicalize(PathBuf::from(data).join("files"))?;
    let key = format!(
        "{:x}",
        Sha256::digest(runtime.as_os_str().as_encoded_bytes())
    );
    let temp = files.join(format!(".npt-{}", &key[..8]));
    DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(&temp)?;
    ensure!(
        fs::symlink_metadata(&temp)?.is_dir(),
        "guest temp must be a directory"
    );
    let temp = temp.to_str().context("guest temp path must be UTF-8")?;
    environment.push_str(&format!(
        "SESSION_TMP_DIR={}\n",
        bootstrap::shell_quote(temp)
    ));
    fsutil::atomic_write(&file, &environment)?;
    Ok(())
}

struct EnrollmentCache {
    store: String,
    public_keys: String,
    /// AWS shared credentials file (the `niks3` profile the store names).
    credentials: String,
}

impl EnrollmentCache {
    fn read(source: &Path) -> Result<Option<Self>> {
        if !source.join("cache_credentials").is_file() {
            return Ok(None);
        }
        let read = |name: &str| {
            String::from_utf8(fsutil::read_bounded(&source.join(name), 4096)?)
                .map(|value| value.trim().to_owned())
                .context("enrollment cache settings must be UTF-8")
        };
        let store = read("cache_store")?;
        let public_keys = read("cache_public_keys")?;
        ensure!(
            store.starts_with("s3://"),
            "expected an S3 enrollment cache"
        );
        ensure!(
            !public_keys.is_empty(),
            "enrollment cache signing keys are missing"
        );
        let credentials = read("cache_credentials")?;
        Ok(Some(Self {
            store,
            public_keys,
            credentials: format!("{credentials}\n"),
        }))
    }

    fn copy(&self, runtime: &Path, user: &str, generation: &str) -> Result<()> {
        session(
            runtime,
            &[
                "env",
                &format!(
                    "AWS_SHARED_CREDENTIALS_FILE=/home/{user}/.config/nixpp-enrollment/cache_credentials"
                ),
                "nix",
                "--option",
                "trusted-public-keys",
                &self.public_keys,
                "--option",
                "require-sigs",
                "true",
                "copy",
                "--from",
                &self.store,
                generation,
            ],
        )?;
        Ok(())
    }
}

fn install_credentials(home: &Path, source: &Path, legacy: bool) -> Result<()> {
    for (input, target, required) in [
        ("id_ed25519", ".ssh/id_ed25519", true),
        ("id_ed25519.pub", ".ssh/id_ed25519.pub", true),
        // Only a build host named by the credentials needs these.
        ("ssh_config", ".config/nixpp-enrollment/ssh_config", legacy),
        (
            "known_hosts",
            ".config/nixpp-enrollment/known_hosts",
            legacy,
        ),
        ("age_key", ".config/sops/age/keys.txt", false),
        (
            "cache_credentials",
            ".config/nixpp-enrollment/cache_credentials",
            false,
        ),
    ] {
        let source = source.join(input);
        if !required && !source.try_exists()? {
            continue;
        }
        let bytes = fsutil::read_bounded(&source, 128 * 1024)
            .with_context(|| format!("cannot read enrollment credential {input}"))?;
        let value = std::str::from_utf8(&bytes).context("enrollment credential must be text")?;
        ensure!(
            !value.trim().is_empty(),
            "empty enrollment credential {input}"
        );
        let target = home.join(target);
        let parent = target.parent().context("invalid credential destination")?;
        DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(parent)?;
        fsutil::atomic_write(&target, value)?;
    }
    Ok(())
}

/// What `activate` needs besides the runtime.
struct Activation<'a> {
    user: &'a str,
    host: &'a str,
    /// The guest user's home, as the native side sees it.
    home: &'a Path,
    credentials: &'a Path,
    origin: &'a Origin,
    build_host: &'a str,
    target: &'a str,
    cache: Option<&'a EnrollmentCache>,
}

fn activate(runtime: &Path, activation: Activation<'_>) -> Result<()> {
    let mut runner = SessionRunner::new();
    if runtime.join("system-pending.json").exists() {
        let status = controller::recover(runtime, &mut runner)?;
        ensure!(status == 0, "enrollment recovery failed ({status})");
    }
    let generation = match activation.origin {
        Origin::BuildHost => from_build_host(runtime, &activation)?,
        Origin::Lease(lease) => from_lease(runtime, &activation, lease)?,
        Origin::Published(published) => from_published(runtime, &activation, published)?,
    };
    let generation = generation.as_str();
    let candidate = generation
        .strip_prefix("/nix/store/")
        .context("invalid candidate path")?;
    if !runtime.join("system-candidates").join(candidate).is_dir() {
        session(runtime, &["nixpp", "prepare", generation])?;
    }
    let data = controller::candidate_manifest(runtime, candidate)?;
    let home = data.home_manager;
    let has_state = runtime.join("system-state.json").is_file();
    if !has_state {
        eprintln!("Activating the initial Home Manager generation…");
        session(
            runtime,
            &[
                "bash",
                "-c",
                r#"set -eu
if [ ! -e "$HOME/.nix-profile" ]; then
  nix profile add "$1/home-path"
fi
exec "$1/activate""#,
                "nixpp",
                &home,
            ],
        )?;
    }
    let status = controller::switch(
        runtime,
        candidate,
        (!has_state).then_some(home.as_str()),
        &mut runner,
        None,
    )?;
    ensure!(status == 0, "system activation checks failed ({status})");
    Ok(())
}

/// Copy `generation` from the signed enrollment cache when it has it.
fn copy_from_cache(
    runtime: &Path,
    user: &str,
    cache: Option<&EnrollmentCache>,
    generation: &str,
) -> bool {
    cache.is_some_and(|cache| {
        eprintln!("Checking the signed enrollment cache…");
        match cache.copy(runtime, user, generation) {
            Ok(()) => true,
            Err(_) => {
                eprintln!("Could not use the signed cache. Copying from the pinned worker.");
                false
            }
        }
    })
}

/// The initial worker of `nixpp bootstrap`: built and copied over its ssh_config.
fn from_build_host(runtime: &Path, activation: &Activation<'_>) -> Result<String> {
    let Activation {
        user,
        build_host,
        target,
        cache,
        ..
    } = *activation;
    eprintln!("Building the guest configuration through Nix…");
    // Keep private flake access on the initial worker. OpenSSH passes a
    // command string to the remote shell, so quote the validated target.
    let target = target.replace('\'', "'\"'\"'");
    let command = format!("nix build --no-link --print-out-paths -- '{target}'");
    let ssh_config = format!("/home/{user}/.config/nixpp-enrollment/ssh_config");
    let output = session(
        runtime,
        &[
            "ssh",
            "-F",
            &ssh_config,
            "-oBatchMode=yes",
            build_host,
            &command,
        ],
    )?;
    let generation = std::str::from_utf8(&output.stdout)?.trim().to_owned();
    ensure!(
        store::is_store_root(&generation),
        "Nix returned an invalid generation"
    );
    if !copy_from_cache(runtime, user, cache, &generation) {
        // This is the same pinned worker that returned the verified store path.
        // Its disposable store does not sign locally built outputs.
        let store = format!("ssh://{build_host}");
        session(
            runtime,
            &[
                "env",
                &format!("NIX_SSHOPTS=-F {ssh_config}"),
                "nix",
                "copy",
                "--no-check-sigs",
                "--from",
                &store,
                &generation,
            ],
        )?;
    }
    Ok(generation)
}

/// Build the latest flake on a freshly leased worker, then copy the result.
fn from_lease(runtime: &Path, activation: &Activation<'_>, origin: &LeaseOrigin) -> Result<String> {
    let Activation {
        user,
        host,
        home,
        credentials,
        cache,
        ..
    } = *activation;
    let scratch = Scratch::new("nixpp-lease")?;
    let key = credentials.join("id_ed25519");
    let native = Side {
        key: key.clone(),
        known_hosts: credentials.join("known_hosts"),
    };
    let guest_dir = format!("/home/{user}/.config/nixpp-enrollment");
    let guest = Side {
        key: PathBuf::from(format!("/home/{user}/.ssh/id_ed25519")),
        known_hosts: PathBuf::from(format!("{guest_dir}/known_hosts")),
    };
    let request = lease::Request {
        public_key: String::from_utf8(fsutil::read_bounded(
            &credentials.join("id_ed25519.pub"),
            8192,
        )?)?
        .trim()
        .to_owned(),
        owner: origin.owner.clone(),
        topic: format!("nixpp setup {host}"),
        ttl: origin.ttl.clone(),
    };
    ensure!(
        !request.public_key.is_empty() && !request.public_key.contains('\n'),
        "invalid enrollment public key"
    );
    // GitHub runners cannot forward the ssh-agent; private inputs then need
    // the key on the runner, which the user may have forbidden.
    let needs_key = !origin.ssh_inputs.is_empty();
    let profiles: Vec<Profile> = origin
        .profiles
        .iter()
        .filter(|profile| {
            let usable = origin.allow_key_upload || !needs_key || !profile.is_hosted();
            if !usable {
                eprintln!(
                    "Skipping {profile:?}: it cannot forward the ssh-agent and --no-key-upload is set."
                );
            }
            usable
        })
        .cloned()
        .collect();
    let mut lease = lease::lease(
        &origin.controller,
        &native,
        &request,
        &profiles,
        origin.queue_timeout,
    )?;
    // The worker is pinned under its name, on both sides.
    let worker_line = format!("{}\n", lease.worker().known_hosts_line());
    append(&native.known_hosts, &worker_line)?;
    let enrollment = home.join(".config/nixpp-enrollment");
    append(&enrollment.join("known_hosts"), &worker_line)?;
    fsutil::atomic_write(
        &enrollment.join("ssh_config"),
        &lease.worker().ssh_config(&guest, &origin.controller),
    )?;

    let upload = needs_key && lease.worker().is_hosted();
    let agent = if upload {
        None
    } else {
        Some(Agent::start(scratch.path(), &key)?)
    };
    let spec = Build {
        flake: activation.flake_of_target()?,
        target_attribute: format!("termuxppConfigurations.{host}.system"),
        ssh_inputs: origin.ssh_inputs.clone(),
        cache: cache.map(|cache| CacheOptions {
            store: cache.store.clone(),
            public_keys: cache.public_keys.clone(),
            credentials: cache.credentials.clone(),
        }),
        github_known_hosts: origin.github_known_hosts.clone(),
        uploaded_key: if upload {
            Some(String::from_utf8(fsutil::read_bounded(&key, 64 * 1024)?)?)
        } else {
            None
        },
        refresh: false,
    };
    let generation = lease::build(&lease, agent.as_ref(), &spec)?;
    if !copy_from_cache(runtime, user, cache, &generation) {
        eprintln!("Copying the system from {}…", lease.worker().destination());
        session(
            runtime,
            &[
                "env",
                &format!("NIX_SSHOPTS=-F {guest_dir}/ssh_config"),
                "nix",
                "copy",
                "--no-check-sigs",
                "--from",
                &lease.worker().store_uri(),
                &generation,
            ],
        )?;
    }
    lease.release();
    drop(agent);
    Ok(generation)
}

fn append(path: &Path, line: &str) -> Result<()> {
    use std::io::Write;
    let mut file = fs::OpenOptions::new()
        .append(true)
        .create(true)
        .open(path)?;
    file.write_all(line.as_bytes())?;
    Ok(())
}

/// Install the system CI published for this host, verified like `nixpp upgrade`.
fn from_published(
    runtime: &Path,
    activation: &Activation<'_>,
    origin: &PublishedOrigin,
) -> Result<String> {
    let Activation {
        user,
        host,
        credentials,
        ..
    } = *activation;
    let scratch = Scratch::new("nixpp-pin")?;
    let cache = EnrollmentCache::read(credentials)?
        .context("the published system needs the cache credentials")?;
    // curl reads the SigV4 settings from a private file, not from arguments.
    let credentials_text = &cache.credentials;
    let value = |name: &str| {
        credentials_text
            .lines()
            .find_map(|line| line.strip_prefix(name))
            .and_then(|rest| rest.trim_start().strip_prefix('='))
            .map(|rest| rest.trim().to_owned())
            .with_context(|| format!("cache credentials have no {name}"))
    };
    let curl_config = scratch.path().join("pin.curl");
    {
        use std::io::Write;
        use std::os::unix::fs::OpenOptionsExt;
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&curl_config)?;
        writeln!(file, "aws-sigv4 = \"aws:amz:{}:s3\"", origin.region)?;
        writeln!(
            file,
            "user = \"{}:{}\"",
            value("aws_access_key_id")?,
            value("aws_secret_access_key")?
        )?;
    }
    let target = guest::UpgradeTarget {
        flake: activation.flake_of_target()?,
        host: host.to_owned(),
        cache: Some(guest::UpgradeCache {
            store: origin.store.clone(),
            pin_reader: PathBuf::from("/nixpp-setup-pin"),
            public_keys: origin.public_keys.clone(),
            credentials_file: Some(PathBuf::from(format!(
                "/home/{user}/.config/nixpp-enrollment/cache_credentials"
            ))),
        }),
        lease: None,
    };
    let mut builder = SetupBuilder {
        runtime,
        pin_url: &origin.pin_url,
        curl_config: &curl_config,
    };
    let path = guest::cached_upgrade(&target, false, &mut builder, |path| {
        eprintln!("Installing the published system {}", path.display());
    })?;
    Ok(path
        .to_str()
        .context("the published system path is not UTF-8")?
        .to_owned())
}

/// Runs the commands of `cached_upgrade` in the guest, except the pin read,
/// which happens here with the native curl.
struct SetupBuilder<'a> {
    runtime: &'a Path,
    pin_url: &'a str,
    curl_config: &'a Path,
}

impl guest::Builder for SetupBuilder<'_> {
    fn output(&mut self, command: &[String]) -> crate::error::Result<String> {
        let invalid = |message: String| crate::error::Error::invalid(message);
        if command == ["/nixpp-setup-pin"] {
            let output = Command::new("curl")
                .args(["--disable", "--config"])
                .arg(self.curl_config)
                .args([
                    "--fail",
                    "--silent",
                    "--show-error",
                    "--compressed",
                    "--proto",
                    "=https",
                    "--max-time",
                    "30",
                    "--max-filesize",
                    "4096",
                    self.pin_url,
                ])
                .stdin(Stdio::null())
                .stderr(Stdio::inherit())
                .output()
                .map_err(|error| invalid(format!("cannot run curl: {error}")))?;
            if !output.status.success() {
                return Err(invalid(format!(
                    "cannot read the cache pin ({})",
                    output.status
                )));
            }
            return String::from_utf8(output.stdout)
                .map_err(|_| invalid("the cache pin is not UTF-8".to_owned()));
        }
        let output =
            session(self.runtime, command).map_err(|error| invalid(format!("{error:#}")))?;
        String::from_utf8(output.stdout)
            .map_err(|_| invalid("command output is not UTF-8".to_owned()))
    }
}

impl Activation<'_> {
    /// The flake URI of the target (`FLAKE#ATTRIBUTE`).
    fn flake_of_target(&self) -> Result<String> {
        let (flake, _) = self
            .target
            .rsplit_once('#')
            .context("invalid Nix build target")?;
        Ok(flake.to_owned())
    }
}

fn session(runtime: &Path, args: &[impl AsRef<std::ffi::OsStr>]) -> Result<Output> {
    let output = Command::new(runtime.join("session.sh"))
        .args(["--legacy", "--"])
        .args(args)
        .stdin(Stdio::inherit())
        .stderr(Stdio::inherit())
        .output()
        .context("unable to start the enrollment guest")?;
    ensure!(
        output.status.success(),
        "enrollment guest command failed ({})",
        output.status
    );
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    #[test]
    fn credentials_install_with_private_permissions_and_support_retry() {
        let fixture = tempfile::tempdir().unwrap();
        let input = fixture.path().join("input");
        let home = fixture.path().join("home");
        fs::create_dir(&input).unwrap();
        for name in ["id_ed25519", "id_ed25519.pub", "ssh_config", "known_hosts"] {
            fs::write(input.join(name), format!("fixture for {name}\n")).unwrap();
        }
        install_credentials(&home, &input, true).unwrap();
        assert!(!home.join(".config/sops/age/keys.txt").exists());
        fs::write(input.join("age_key"), "fixture age key\n").unwrap();
        install_credentials(&home, &input, true).unwrap();
        for name in [
            ".ssh/id_ed25519",
            ".config/nixpp-enrollment/ssh_config",
            ".config/sops/age/keys.txt",
        ] {
            let path = home.join(name);
            assert_eq!(
                fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
            assert!(fs::read_to_string(path).unwrap().starts_with("fixture"));
        }
    }
}
