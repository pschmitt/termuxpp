//! `nixpp setup`: enroll a fresh TermuxPP phone into its guest system.
//!
//! Reads the enrollment credentials from Bitwarden with the bundled rbw,
//! builds the system on a freshly leased rworker (or installs the CI-published
//! pin with `--published`), activates it and clones the dotfiles. Every step
//! can be repeated after an interruption.

use crate::enroll::{self, LeaseOrigin, Origin, PublishedOrigin};
use crate::lease::{Controller, Profile};
use crate::{fsutil, store};
use anyhow::{Context, Result, bail, ensure};
use std::collections::HashMap;
use std::ffi::{OsStr, OsString};
use std::fs::{self, DirBuilder, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

/// GitHub's public SSH host keys, pinned for `git+ssh` on the worker and for
/// the dotfiles clone (https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints).
pub const GITHUB_KNOWN_HOSTS: &str = "\
ssh.github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl
ssh.github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=
ssh.github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=
github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl
github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=
github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=
";

/// Defaults that stay out of the public sources are read from this file
/// (`KEY=VALUE` lines), which the bootstrap builder installs.
const CONFIG_FILE: &str = "etc/nixpp/setup.conf";
const DEFAULT_FLAKE: &str = "github:pschmitt/nixos-config/main";

/// Enroll this phone: credentials from Bitwarden, a build, activation, dotfiles.
#[derive(Debug, clap::Args)]
pub struct Args {
    /// Host name of the system configuration (and of the Bitwarden items)
    #[arg(long)]
    pub host: String,
    /// Guest account expected by the configuration (default: `user` of the setup config)
    #[arg(long, env = "NIXPP_SETUP_USER")]
    pub user: Option<String>,
    /// Flake URI, without an output fragment
    #[arg(long, default_value = DEFAULT_FLAKE)]
    pub flake: String,
    /// File with RBW_MASTER_PASSWORD and RBW_TOTP lines (owner-only, deleted after reading)
    #[arg(long, value_name = "FILE")]
    pub secrets_file: Option<PathBuf>,
    /// Install the system CI published for this host instead of building the latest
    #[arg(long)]
    pub published: bool,
    /// Bitwarden account email, set when rbw has none (default: `email` of the setup config)
    #[arg(long, env = "RBW_EMAIL")]
    pub email: Option<String>,
    /// rworker controller, `[USER@]HOST[:PORT]` (default: `controller` of the setup config)
    #[arg(long, env = "NIXPP_SETUP_CONTROLLER", value_name = "HOST")]
    pub rworker_controller: Option<String>,
    /// Public host key of the controller, `ssh-ed25519 AAAA...` (default: `controller_host_key`)
    #[arg(long, env = "NIXPP_SETUP_CONTROLLER_HOST_KEY", value_name = "KEY")]
    pub rworker_host_key: Option<String>,
    /// Worker profile to lease; repeat to set the fallback order
    /// (default: gh-aarch64, then the controller's default profile)
    #[arg(long, value_name = "PROFILE")]
    pub worker_profile: Vec<String>,
    /// Lifetime of the leased worker
    #[arg(long, default_value = "2h", value_name = "DURATION")]
    pub worker_ttl: String,
    /// Seconds to wait for a GitHub runner before the next profile is tried
    #[arg(long, default_value_t = 420, value_name = "SECONDS")]
    pub queue_timeout: u64,
    /// Root input of the flake that is private on GitHub; the worker fetches it
    /// over SSH with this phone's key at the revision of the flake's lock file
    #[arg(long, value_name = "NAME")]
    pub ssh_input: Vec<String>,
    /// Never put the phone's key on a GitHub runner (they cannot forward the
    /// ssh-agent): with private inputs, only OpenStack profiles are used
    #[arg(long)]
    pub no_key_upload: bool,
    /// Binary cache the worker and the phone may use (default: `cache_store`)
    #[arg(long, value_name = "URI")]
    pub cache_store: Option<String>,
    /// Trusted signing key of that cache; repeatable (default: `cache_public_keys`)
    #[arg(long, value_name = "KEY")]
    pub cache_public_key: Vec<String>,
    /// URL of the published system's pin, with {host} (default: `cache_pin_url`)
    #[arg(long, value_name = "URL")]
    pub cache_pin_url: Option<String>,
    /// Bitwarden item with the phone's SSH key (default: `{user}@{host}`)
    #[arg(long, value_name = "ITEM")]
    pub key_item: Option<String>,
    /// Bitwarden item with the optional `age_key` field (default: `nixpp-enrollment-{host}`)
    #[arg(long, value_name = "ITEM")]
    pub enrollment_item: Option<String>,
    /// Bitwarden item with the cache's access key (`username`) and secret (`password`)
    #[arg(long, default_value = "nixpp Niks3 cache", value_name = "ITEM")]
    pub cache_item: String,
    /// Bitwarden item with the dotfiles deploy key (`private_key`)
    #[arg(long, default_value = "yadm-init-deploy-key", value_name = "ITEM")]
    pub deploy_key_item: String,
    /// Repository the dotfiles are cloned from, an ssh:// URL reached through
    /// ssh.github.com (default: `dotfiles_repo` of the setup config; none: no clone)
    #[arg(long, value_name = "URL")]
    pub dotfiles_repo: Option<String>,
    /// Do not clone the dotfiles
    #[arg(long)]
    pub no_dotfiles: bool,
    /// Setup defaults (default: $PREFIX/etc/nixpp/setup.conf)
    #[arg(long, env = "NIXPP_SETUP_CONFIG", value_name = "FILE")]
    pub config: Option<PathBuf>,
}

/// `KEY=VALUE` lines; `#` starts a comment line.
pub fn parse_config(text: &str) -> HashMap<String, String> {
    text.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .filter_map(|line| line.split_once('='))
        .map(|(key, value)| (key.trim().to_owned(), value.trim().to_owned()))
        .collect()
}

/// The options with the setup config's defaults applied.
#[derive(Debug, PartialEq, Eq)]
pub struct Resolved {
    pub host: String,
    pub user: String,
    pub flake: String,
    pub email: Option<String>,
    pub key_item: String,
    pub enrollment_item: String,
    pub cache_item: String,
    pub deploy_key_item: String,
    pub dotfiles_repo: Option<String>,
    pub origin: ResolvedOrigin,
    /// Public keys and store of the cache, when one is configured.
    pub cache: Option<ResolvedCache>,
}

#[derive(Debug, PartialEq, Eq)]
pub struct ResolvedCache {
    pub store: String,
    pub public_keys: Vec<String>,
    pub region: String,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ResolvedOrigin {
    Lease {
        controller: Controller,
        profiles: Vec<Profile>,
        ttl: String,
        queue_timeout: Duration,
        ssh_inputs: Vec<String>,
        allow_key_upload: bool,
    },
    Published {
        pin_url: String,
    },
}

fn first(option: Option<&String>, config: &HashMap<String, String>, key: &str) -> Option<String> {
    option
        .cloned()
        .or_else(|| config.get(key).cloned())
        .filter(|value| !value.is_empty())
}

fn list(
    options: &[String],
    config: &HashMap<String, String>,
    key: &str,
    separator: &[char],
) -> Vec<String> {
    if options.is_empty() {
        config
            .get(key)
            .map(|value| {
                value
                    .split(separator)
                    .map(str::trim)
                    .filter(|item| !item.is_empty())
                    .map(str::to_owned)
                    .collect()
            })
            .unwrap_or_default()
    } else {
        options.to_vec()
    }
}

fn valid_item(name: &str) -> bool {
    !name.is_empty() && !name.starts_with('-') && !name.contains(['\n', '\0'])
}

pub fn resolve(args: &Args, config: &HashMap<String, String>) -> Result<Resolved> {
    ensure!(store::is_host_name(&args.host), "invalid host name");
    let user = first(args.user.as_ref(), config, "user").context(
        "the guest user is unknown: pass --user (or set NIXPP_SETUP_USER or `user` in the setup config)",
    )?;
    ensure!(store::is_user_name(&user), "invalid guest user");
    ensure!(
        !args.flake.is_empty() && !args.flake.starts_with('-') && !args.flake.contains('#'),
        "supply a flake URI without an output fragment"
    );
    let template = |value: Option<&String>, default: String| {
        value.map_or(default, |item| {
            item.replace("{user}", &user).replace("{host}", &args.host)
        })
    };
    let key_item = template(args.key_item.as_ref(), format!("{user}@{}", args.host));
    let enrollment_item = template(
        args.enrollment_item.as_ref(),
        format!("nixpp-enrollment-{}", args.host),
    );
    for item in [
        &key_item,
        &enrollment_item,
        &args.cache_item,
        &args.deploy_key_item,
    ] {
        ensure!(valid_item(item), "invalid Bitwarden item name");
    }
    let public_keys = list(
        &args.cache_public_key,
        config,
        "cache_public_keys",
        &[' ', ',', '\n'],
    );
    let cache = match first(args.cache_store.as_ref(), config, "cache_store") {
        Some(store) => {
            ensure!(store.starts_with("s3://"), "expected an S3 binary cache");
            ensure!(
                !public_keys.is_empty(),
                "the binary cache needs its signing keys"
            );
            ensure!(
                public_keys
                    .iter()
                    .all(|key| key.contains(':') && !key.contains(char::is_whitespace)),
                "invalid cache signing key"
            );
            Some(ResolvedCache {
                store,
                public_keys,
                region: config
                    .get("cache_region")
                    .cloned()
                    .unwrap_or_else(|| "default".to_owned()),
            })
        }
        None => None,
    };
    let origin = if args.published {
        ensure!(
            cache.is_some(),
            "--published needs a binary cache (--cache-store)"
        );
        let url = first(args.cache_pin_url.as_ref(), config, "cache_pin_url")
            .context("--published needs the pin URL (--cache-pin-url)")?
            .replace("{host}", &args.host);
        ensure!(
            url.starts_with("https://") && !url.contains(char::is_whitespace),
            "the pin URL must be an https:// URL"
        );
        ResolvedOrigin::Published { pin_url: url }
    } else {
        let spec = first(args.rworker_controller.as_ref(), config, "controller")
            .context("no rworker controller: pass --rworker-controller (or set `controller` in the setup config)")?;
        let key = first(args.rworker_host_key.as_ref(), config, "controller_host_key")
            .context("no rworker controller host key: pass --rworker-host-key (or set `controller_host_key`)")?;
        let names = list(&args.worker_profile, config, "worker_profiles", &[' ', ',']);
        let names = if names.is_empty() {
            vec!["gh-aarch64".to_owned(), "default".to_owned()]
        } else {
            names
        };
        let ssh_inputs = list(&args.ssh_input, config, "ssh_inputs", &[' ', ',']);
        ensure!(
            ssh_inputs.iter().all(|name| valid_input_name(name)),
            "invalid flake input name"
        );
        ensure!(
            !args.worker_ttl.is_empty()
                && args.worker_ttl.bytes().all(|b| b.is_ascii_alphanumeric()),
            "invalid worker lifetime"
        );
        ResolvedOrigin::Lease {
            controller: Controller::new(&spec, &key)?,
            profiles: names
                .iter()
                .map(|name| Profile::parse(name))
                .collect::<Result<_>>()?,
            ttl: args.worker_ttl.clone(),
            queue_timeout: Duration::from_secs(args.queue_timeout.clamp(30, 3600)),
            ssh_inputs,
            allow_key_upload: !args.no_key_upload,
        }
    };
    let dotfiles_repo =
        first(args.dotfiles_repo.as_ref(), config, "dotfiles_repo").filter(|_| !args.no_dotfiles);
    ensure!(
        dotfiles_repo
            .as_ref()
            .is_none_or(|repo| repo.starts_with("ssh://")
                && !repo.starts_with("ssh://-")
                && !repo.contains(char::is_whitespace)),
        "the dotfiles repository must be an ssh:// URL"
    );
    Ok(Resolved {
        host: args.host.clone(),
        user,
        flake: args.flake.clone(),
        email: first(args.email.as_ref(), config, "email"),
        key_item,
        enrollment_item,
        cache_item: args.cache_item.clone(),
        deploy_key_item: args.deploy_key_item.clone(),
        dotfiles_repo,
        origin,
        cache,
    })
}

pub(crate) fn valid_input_name(name: &str) -> bool {
    !name.is_empty()
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.'))
}

/// What the secrets file holds. Debug output never shows a value.
#[derive(Default, PartialEq, Eq)]
pub struct Secrets {
    pub password: Option<String>,
    pub totp: Option<String>,
}

impl std::fmt::Debug for Secrets {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("Secrets")
            .field("password", &self.password.as_ref().map(|_| "<redacted>"))
            .field("totp", &self.totp.as_ref().map(|_| "<redacted>"))
            .finish()
    }
}

/// Parse `RBW_MASTER_PASSWORD=...` / `RBW_TOTP=...` lines. Values are taken
/// literally (no quoting); other keys and comments are ignored.
pub fn parse_secrets(text: &str) -> Secrets {
    let mut secrets = Secrets::default();
    for line in text.lines() {
        let line = line.strip_suffix('\r').unwrap_or(line);
        match line.split_once('=') {
            Some(("RBW_MASTER_PASSWORD", value)) if !value.is_empty() => {
                secrets.password = Some(value.to_owned());
            }
            Some(("RBW_TOTP", value)) if !value.is_empty() => {
                secrets.totp = Some(value.to_owned());
            }
            _ => {}
        }
    }
    secrets
}

/// Read and delete the secrets file. It must be a regular file (not a link)
/// owned by this user and readable by nobody else.
pub fn read_secrets_file(path: &Path) -> Result<Secrets> {
    let metadata = fs::symlink_metadata(path)
        .with_context(|| format!("cannot read the secrets file {}", path.display()))?;
    ensure!(
        metadata.file_type().is_file(),
        "the secrets file must be a regular file"
    );
    // SAFETY: geteuid has no preconditions.
    let uid = unsafe { libc::geteuid() };
    ensure!(
        metadata.uid() == uid,
        "the secrets file must be owned by the current user"
    );
    ensure!(
        metadata.permissions().mode() & 0o777 == 0o600,
        "the secrets file must have mode 0600"
    );
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)
        .context("cannot open the secrets file")?;
    let mut data = Vec::new();
    let read = (&mut file).take(64 * 1024 + 1).read_to_end(&mut data);
    // Consumed: remove it even when it turns out to be unusable.
    let removed = fs::remove_file(path);
    read.context("cannot read the secrets file")?;
    removed.context("cannot delete the secrets file")?;
    ensure!(data.len() <= 64 * 1024, "the secrets file is too large");
    let text = String::from_utf8(data).context("the secrets file must be UTF-8 text")?;
    Ok(parse_secrets(&text))
}

/// The Bitwarden client. A trait so the login flow is testable.
pub trait Vault {
    fn unlocked(&self) -> bool;
    fn sync(&self) -> Result<()>;
    fn email(&self) -> Option<String>;
    fn set_email(&self, email: &str) -> Result<()>;
    /// Unlock (and log in), the password on stdin. An empty `totp` is none.
    fn unlock(&self, password: &str, totp: &str) -> Result<()>;
    /// A field of an entry; `Ok(None)` when the entry or field does not exist.
    fn field(&self, item: &str, field: &str) -> Result<Option<String>>;
}

/// The real rbw (`RBW_BIN`, else `rbw` from PATH).
pub struct Rbw(PathBuf);

impl Rbw {
    pub fn locate() -> Self {
        Self(
            std::env::var_os("RBW_BIN")
                .filter(|value| !value.is_empty())
                .map_or_else(|| PathBuf::from("rbw"), PathBuf::from),
        )
    }

    fn command(&self) -> Command {
        let mut command = Command::new(&self.0);
        // The agent is started from the same directory (rbw-agent).
        command.stdin(Stdio::null());
        command
    }
}

impl Vault for Rbw {
    fn unlocked(&self) -> bool {
        self.command()
            .arg("unlocked")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .is_ok_and(|status| status.success())
    }

    fn sync(&self) -> Result<()> {
        let status = self
            .command()
            .arg("sync")
            .status()
            .context("cannot run rbw")?;
        ensure!(status.success(), "rbw sync failed");
        Ok(())
    }

    fn email(&self) -> Option<String> {
        let output = self
            .command()
            .args(["config", "get", "email"])
            .stderr(Stdio::null())
            .output()
            .ok()?;
        let email = String::from_utf8(output.stdout).ok()?;
        (output.status.success() && !email.trim().is_empty()).then(|| email.trim().to_owned())
    }

    fn set_email(&self, email: &str) -> Result<()> {
        let status = self
            .command()
            .args(["config", "set", "email", email])
            .status()
            .context("cannot run rbw")?;
        ensure!(status.success(), "rbw config set email failed");
        Ok(())
    }

    fn unlock(&self, password: &str, totp: &str) -> Result<()> {
        // pinentry does not work in a Termux session: the password goes in on
        // stdin (the agent runs detached and has no terminal of its own).
        let mut command = self.command();
        command.args(["unlock", "--stdin"]);
        if !totp.is_empty() {
            command.args(["--totp", totp]);
        }
        let mut child = command
            .stdin(Stdio::piped())
            .spawn()
            .context("cannot run rbw")?;
        if let Some(mut stdin) = child.stdin.take() {
            writeln!(stdin, "{password}")?;
        }
        let status = child.wait()?;
        ensure!(status.success(), "Failed to unlock the Bitwarden vault");
        Ok(())
    }

    fn field(&self, item: &str, field: &str) -> Result<Option<String>> {
        let output = self
            .command()
            .args(["get", "--exact", item, "-f", field])
            .stderr(Stdio::null())
            .output()
            .context("cannot run rbw")?;
        if !output.status.success() {
            return Ok(None);
        }
        let value = String::from_utf8(output.stdout).context("Bitwarden field is not UTF-8")?;
        let value = value.strip_suffix('\n').unwrap_or(&value);
        Ok((!value.is_empty()).then(|| value.to_owned()))
    }
}

/// Make sure the vault is unlocked: reuse an unlocked agent, else use the
/// secrets file, else ask on the terminal (one retry with a fresh TOTP).
pub fn login(
    vault: &dyn Vault,
    secrets: Option<Secrets>,
    email: Option<&str>,
    prompt: &mut dyn FnMut(&str) -> Result<String>,
) -> Result<()> {
    if vault.unlocked() {
        eprintln!("Bitwarden is already unlocked");
        if vault.sync().is_err() {
            eprintln!("Could not sync the Bitwarden vault; using the cached copy");
        }
        return Ok(());
    }
    if vault.email().is_none() {
        let email = email.context(
            "rbw has no email configured: pass --email (or set RBW_EMAIL or `email` in the setup config)",
        )?;
        vault.set_email(email)?;
    }
    let Secrets {
        mut password,
        mut totp,
    } = secrets.unwrap_or_default();
    for attempt in 0..2 {
        let pass = match password.clone() {
            Some(password) => password,
            None => {
                let entered = prompt("Bitwarden password")?;
                password = Some(entered.clone());
                entered
            }
        };
        if attempt == 1 {
            eprintln!("Bitwarden authentication failed; enter a fresh TOTP to retry once");
            totp = None;
        }
        let code = match totp.take() {
            Some(code) => code,
            None => prompt("Bitwarden TOTP code (blank if none)").unwrap_or_default(),
        };
        eprintln!("Logging into Bitwarden...");
        if vault.unlock(&pass, &code).is_ok() {
            return Ok(());
        }
    }
    bail!("Bitwarden login failed after one fresh-TOTP retry")
}

/// Read a line from the terminal without echo.
pub fn prompt_secret(prompt: &str) -> Result<String> {
    let mut tty = OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/tty")
        .context("no terminal to ask for the Bitwarden credentials (use --secrets-file)")?;
    write!(tty, "{prompt}: ")?;
    tty.flush()?;
    let fd = std::os::fd::AsRawFd::as_raw_fd(&tty);
    // SAFETY: tcgetattr/tcsetattr only read and write the termios struct of an
    // open terminal descriptor.
    let saved = unsafe {
        let mut state = std::mem::zeroed::<libc::termios>();
        (libc::tcgetattr(fd, &mut state) == 0).then_some(state)
    };
    if let Some(mut quiet) = saved {
        quiet.c_lflag &= !libc::ECHO;
        // SAFETY: as above.
        unsafe { libc::tcsetattr(fd, libc::TCSAFLUSH, &quiet) };
    }
    let mut line = Vec::new();
    let mut byte = [0_u8; 1];
    let result = loop {
        match tty.read(&mut byte) {
            Ok(0) => break Ok(()),
            Ok(_) if byte[0] == b'\n' => break Ok(()),
            Ok(_) => line.push(byte[0]),
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
            Err(error) => break Err(error),
        }
    };
    if let Some(state) = saved {
        // SAFETY: restores the state read above.
        unsafe { libc::tcsetattr(fd, libc::TCSAFLUSH, &state) };
    }
    writeln!(tty)?;
    result?;
    let text = String::from_utf8(line).context("input is not UTF-8")?;
    let text = text.strip_suffix('\r').unwrap_or(&text).to_owned();
    ensure!(!text.is_empty(), "empty input");
    Ok(text)
}

fn write_private(path: &Path, text: &str) -> Result<()> {
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
        .with_context(|| format!("cannot create {}", path.display()))?;
    file.write_all(text.as_bytes())?;
    Ok(())
}

fn required(vault: &dyn Vault, item: &str, field: &str) -> Result<String> {
    vault
        .field(item, field)?
        .with_context(|| format!("Bitwarden item '{item}' has no {field}"))
}

/// Write the enrollment credentials into `directory` (empty, private):
/// the phone's SSH key, the optional age key, the optional cache credentials
/// and the pinned rworker controller.
pub fn stage_credentials(vault: &dyn Vault, resolved: &Resolved, directory: &Path) -> Result<()> {
    let key = required(vault, &resolved.key_item, "private_key")?;
    let public = required(vault, &resolved.key_item, "public_key")?;
    write_private(
        &directory.join("id_ed25519"),
        &format!("{}\n", key.trim_end()),
    )?;
    write_private(
        &directory.join("id_ed25519.pub"),
        &format!("{}\n", public.trim()),
    )?;
    if let Some(age) = vault.field(&resolved.enrollment_item, "age_key")? {
        write_private(&directory.join("age_key"), &format!("{}\n", age.trim_end()))?;
    }
    let access = vault.field(&resolved.cache_item, "username")?;
    let secret = vault.field(&resolved.cache_item, "password")?;
    match (&resolved.cache, access, secret) {
        (Some(cache), Some(access), Some(secret)) => {
            write_private(
                &directory.join("cache_credentials"),
                &format!(
                    "[niks3]\naws_access_key_id = {access}\naws_secret_access_key = {secret}\n"
                ),
            )?;
            write_private(
                &directory.join("cache_store"),
                &format!("{}\n", cache.store),
            )?;
            write_private(
                &directory.join("cache_public_keys"),
                &format!("{}\n", cache.public_keys.join(" ")),
            )?;
        }
        _ if matches!(resolved.origin, ResolvedOrigin::Published { .. }) => {
            bail!(
                "--published needs the cache credentials from Bitwarden item '{}'",
                resolved.cache_item
            );
        }
        _ => eprintln!("No cache credentials: the system is copied from the build worker."),
    }
    if let ResolvedOrigin::Lease { controller, .. } = &resolved.origin {
        write_private(
            &directory.join("known_hosts"),
            &format!("{}\n", controller.known_hosts_line()),
        )?;
    }
    Ok(())
}

impl Resolved {
    fn enroll_origin(&self, owner: String) -> Origin {
        match &self.origin {
            ResolvedOrigin::Lease {
                controller,
                profiles,
                ttl,
                queue_timeout,
                ssh_inputs,
                allow_key_upload,
            } => Origin::Lease(Box::new(LeaseOrigin {
                controller: controller.clone(),
                profiles: profiles.clone(),
                ttl: ttl.clone(),
                queue_timeout: *queue_timeout,
                owner,
                ssh_inputs: ssh_inputs.clone(),
                github_known_hosts: GITHUB_KNOWN_HOSTS.to_owned(),
                allow_key_upload: *allow_key_upload,
            })),
            ResolvedOrigin::Published { pin_url } => Origin::Published(Box::new(PublishedOrigin {
                pin_url: pin_url.clone(),
                region: self
                    .cache
                    .as_ref()
                    .map_or_else(|| "default".to_owned(), |cache| cache.region.clone()),
                public_keys: self
                    .cache
                    .as_ref()
                    .map(|cache| cache.public_keys.clone())
                    .unwrap_or_default(),
                store: self
                    .cache
                    .as_ref()
                    .map(|cache| cache.store.clone())
                    .unwrap_or_default(),
            })),
        }
    }
}

fn load_config(path: Option<&Path>) -> Result<HashMap<String, String>> {
    let path = match path {
        Some(path) => path.to_owned(),
        None => match std::env::var_os("PREFIX") {
            Some(prefix) => PathBuf::from(prefix).join(CONFIG_FILE),
            None => return Ok(HashMap::new()),
        },
    };
    match fsutil::read_bounded(&path, 64 * 1024) {
        Ok(data) => Ok(parse_config(
            std::str::from_utf8(&data).context("the setup config must be UTF-8")?,
        )),
        Err(error) if !path.try_exists().unwrap_or(true) => {
            // The default location need not exist; an explicit one must.
            let _ = error;
            Ok(HashMap::new())
        }
        Err(error) => Err(error).with_context(|| format!("cannot read {}", path.display())),
    }
}

/// Prefix under which a guest session sees the whole native file system.
const GUEST_NATIVE_ROOT: &str = "/android";
/// Global options that take a value, which `native_invocation` must skip to
/// find the subcommand.
const VALUE_OPTIONS: [&str; 3] = ["--runtime", "--socket", "--color"];

/// Build the native command line for `nixpp setup ...` typed in a guest.
///
/// `argv` is the guest process's full argument vector. Everything after the
/// `setup` subcommand is forwarded unchanged, except that a guest path given
/// to `--secrets-file` is rewritten to the path the native side sees (see
/// [`native_path`]). `--json`, `--quiet` and `--color` given before `setup`
/// are carried over; `--runtime` and `--socket` name guest paths and are not.
/// The result starts with the native `nixpp-setup` launcher.
pub fn native_invocation(
    argv: &[OsString],
    cwd: &Path,
    runtime: Option<&OsStr>,
) -> Result<Vec<OsString>> {
    let mut forwarded = vec![OsString::from("nixpp-setup")];
    let mut rest = argv.iter().skip(1);
    let mut found = false;
    while let Some(arg) = rest.next() {
        let text = arg.to_string_lossy();
        if text == "setup" {
            found = true;
            break;
        }
        if VALUE_OPTIONS.contains(&text.as_ref()) {
            let value = rest
                .next()
                .context("a global option is missing its value")?;
            if text == "--color" {
                forwarded.extend([arg.clone(), value.clone()]);
            }
        } else if text == "--json"
            || text == "-q"
            || text == "--quiet"
            || text.starts_with("--color=")
        {
            forwarded.push(arg.clone());
        }
    }
    ensure!(found, "cannot find the setup subcommand in the arguments");
    let mut rest = rest.peekable();
    while let Some(arg) = rest.next() {
        if arg == "--" {
            forwarded.push(arg.clone());
            forwarded.extend(rest.by_ref().cloned());
            break;
        }
        if arg == "--secrets-file" {
            forwarded.push(arg.clone());
            let value = rest.next().context("--secrets-file is missing its value")?;
            forwarded.push(native_path(Path::new(value), cwd, runtime)?.into_os_string());
        } else if let Some(value) = arg.as_bytes().strip_prefix(b"--secrets-file=") {
            let path = native_path(Path::new(OsStr::from_bytes(value)), cwd, runtime)?;
            let mut joined = OsString::from("--secrets-file=");
            joined.push(path);
            forwarded.push(joined);
        } else {
            forwarded.push(arg.clone());
        }
    }
    Ok(forwarded)
}

/// The native path of a file named in a guest session.
///
/// The guest's `/home` is `<runtime>/root/home` natively, and the whole native
/// file system is mounted at `/android`, so the app's own home
/// (`/data/data/<package>/files/home`) is `/android/data/data/...` in the
/// guest. A path below `/data` is taken to be native already (the form the
/// helper prints, which does not exist in the guest). Anything else (`/tmp` is
/// configurable, `/etc` and `/run` are overlays) has no reliable native
/// counterpart and is refused rather than guessed.
pub fn native_path(path: &Path, cwd: &Path, runtime: Option<&OsStr>) -> Result<PathBuf> {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        cwd.join(path)
    };
    ensure!(
        !absolute
            .components()
            .any(|part| matches!(part, std::path::Component::ParentDir)),
        "the path {} must not contain `..`",
        absolute.display()
    );
    if let Ok(rest) = absolute.strip_prefix(GUEST_NATIVE_ROOT) {
        return Ok(Path::new("/").join(rest));
    }
    if absolute.starts_with("/data") {
        return Ok(absolute);
    }
    if let Ok(rest) = absolute.strip_prefix("/home") {
        let native = runtime
            .map(Path::new)
            .and_then(|runtime| runtime.strip_prefix(GUEST_NATIVE_ROOT).ok())
            .context("cannot map a guest home path: NIXPP_RUNTIME is not a /android path")?;
        return Ok(Path::new("/").join(native).join("root/home").join(rest));
    }
    bail!(
        "{} is not visible to the native side; put the file below /home or give its \
         native path as /android/... (for example /android/data/data/PACKAGE/files/home/FILE)",
        absolute.display()
    )
}

/// Run `nixpp setup` from a guest by handing the whole invocation to the native
/// side, which has the Android prefix, the bundled rbw and the real terminal.
pub fn hand_off() -> Result<u8> {
    let argv: Vec<OsString> = std::env::args_os().collect();
    let forwarded = native_invocation(
        &argv,
        &std::env::current_dir().context("cannot determine the working directory")?,
        std::env::var_os("NIXPP_RUNTIME").as_deref(),
    )?;
    crate::outer::request(&forwarded).context(
        "setup needs the native TermuxPP side and found no native parent for this session; \
         open the app's failsafe session and run \"$PREFIX/bin/nixpp\" native-shell, \
         then nixpp-setup there",
    )
}

pub fn run(runtime: &Path, args: &Args) -> Result<()> {
    ensure!(
        cfg!(target_os = "android"),
        "run setup in a native TermuxPP session"
    );
    let config = load_config(args.config.as_deref())?;
    if args.config.is_some() {
        ensure!(
            args.config.as_ref().is_some_and(|path| path.is_file()),
            "the setup config does not exist"
        );
    }
    let resolved = resolve(args, &config)?;
    // Read (and delete) the secrets file first: it is single-use even when a
    // later step fails.
    let secrets = args
        .secrets_file
        .as_deref()
        .map(read_secrets_file)
        .transpose()?;
    let vault = Rbw::locate();
    login(&vault, secrets, resolved.email.as_deref(), &mut |prompt| {
        prompt_secret(prompt)
    })?;

    let staging = crate::lease::Scratch::new("nixpp-setup")?;
    let credentials = staging.path().join("credentials");
    DirBuilder::new().mode(0o700).create(&credentials)?;
    stage_credentials(&vault, &resolved, &credentials)?;
    let origin = resolved.enroll_origin(format!("{}@{}", resolved.user, resolved.host));
    enroll::enroll_with(
        runtime,
        &resolved.user,
        &resolved.flake,
        &resolved.host,
        &credentials,
        &origin,
    )?;
    match &resolved.dotfiles_repo {
        Some(repo) => clone_dotfiles(&vault, &resolved, repo, staging.path())?,
        None => eprintln!("No dotfiles repository configured: skipping the dotfiles."),
    }
    println!("Setup complete.");
    Ok(())
}

/// The script run in the guest; `$1` is the host, `$2` the repository.
const DOTFILES_SCRIPT: &str = r#"
set -euo pipefail
umask 077
if ! yadm rev-parse --verify HEAD >/dev/null 2>&1
then
  staging=$(mktemp -d /tmp/nixpp-setup.XXXXXXXX)
  trap 'rm -rf -- "$staging"' EXIT
  tar -xf - -C "$staging"
  export GIT_CONFIG_COUNT=2
  export GIT_CONFIG_KEY_0=yadm.auto-alt GIT_CONFIG_VALUE_0=false
  export GIT_CONFIG_KEY_1=yadm.auto-perms GIT_CONFIG_VALUE_1=false
  printf -v GIT_SSH_COMMAND "ssh -F /dev/null -oBatchMode=yes -oIdentitiesOnly=yes -oStrictHostKeyChecking=yes -oHostKeyAlias=ssh.github.com -oUserKnownHostsFile=%q -i %q" \
    "$staging/known_hosts" "$staging/deploy_key"
  export GIT_SSH_COMMAND
  yadm clone --no-bootstrap "$2"
fi
yadm config yadm.auto-alt false
yadm config local.hostname "$1"
completion="$HOME/.config/zsh/completions/_yadm"
managed="$HOME/.nix-profile/share/zsh/site-functions/_yadm"
legacy="$HOME/.local/share/yadm.git/completion/zsh/_yadm"
if [[ -L "$completion" && ! -e "$completion" && -r "$managed" &&
  "$(readlink "$completion")" == ../../../.local/share/yadm.git/completion/zsh/_yadm &&
  ! -e "$legacy" && ! -L "$legacy" ]]
then
  mkdir -p "${legacy%/*}"
  ln -s "$managed" "$legacy"
fi
"#;

/// Clone the dotfiles inside the guest with the deploy key from Bitwarden.
/// Safe to repeat: an existing checkout is left alone.
fn clone_dotfiles(
    vault: &dyn Vault,
    resolved: &Resolved,
    repo: &str,
    scratch: &Path,
) -> Result<()> {
    let Some(key) = vault.field(&resolved.deploy_key_item, "private_key")? else {
        eprintln!(
            "Bitwarden item '{}' has no private_key: skipping the dotfiles.",
            resolved.deploy_key_item
        );
        return Ok(());
    };
    let directory = scratch.join("dotfiles");
    DirBuilder::new().mode(0o700).create(&directory)?;
    write_private(
        &directory.join("deploy_key"),
        &format!("{}\n", key.trim_end()),
    )?;
    // The GitHub keys, under the alias the clone connects with.
    write_private(&directory.join("known_hosts"), GITHUB_KNOWN_HOSTS)?;

    eprintln!("Cloning the dotfiles…");
    let mut archive = Command::new("tar")
        .arg("-C")
        .arg(&directory)
        .args(["-cf", "-", "deploy_key", "known_hosts"])
        .stdout(Stdio::piped())
        .spawn()
        .context("cannot run tar")?;
    let archive_out = archive.stdout.take().context("tar has no output")?;
    let status = Command::new(std::env::current_exe().context("cannot find nixpp")?)
        .args(["enter", "--", "bash", "-c", DOTFILES_SCRIPT, "nixpp-setup"])
        .arg(&resolved.host)
        .arg(repo)
        .stdin(Stdio::from(archive_out))
        .status()
        .context("cannot enter the guest")?;
    let tar_status = archive.wait()?;
    ensure!(tar_status.success(), "tar failed");
    ensure!(status.success(), "cloning the dotfiles failed ({status})");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    fn argv(args: &[&str]) -> Vec<OsString> {
        args.iter().map(OsString::from).collect()
    }

    const RUNTIME: &str = "/android/data/data/dev.pschmitt.termux/files/nixpp";

    fn invocation(args: &[&str]) -> Result<Vec<OsString>> {
        native_invocation(
            &argv(args),
            Path::new("/home/nix"),
            Some(OsStr::new(RUNTIME)),
        )
    }

    #[test]
    fn guest_setup_forwards_arguments_exactly() {
        let forwarded = invocation(&[
            "nixpp",
            "setup",
            "--host",
            "H",
            "--ssh-input",
            "two words",
            "--",
            "--secrets-file",
        ])
        .unwrap();
        assert_eq!(
            forwarded,
            argv(&[
                "nixpp-setup",
                "--host",
                "H",
                "--ssh-input",
                "two words",
                "--",
                "--secrets-file"
            ])
        );
    }

    #[test]
    fn guest_setup_keeps_presentation_flags_and_drops_guest_paths() {
        let forwarded = invocation(&[
            "nixpp",
            "--runtime",
            "/x",
            "--socket=/y",
            "--json",
            "--color",
            "never",
            "setup",
            "--host",
            "H",
        ])
        .unwrap();
        assert_eq!(
            forwarded,
            argv(&["nixpp-setup", "--json", "--color", "never", "--host", "H"])
        );
        assert!(invocation(&["nixpp", "--json"]).is_err());
    }

    #[test]
    fn guest_secrets_file_is_translated_in_both_spellings() {
        let native = "/data/data/dev.pschmitt.termux/files/nixpp/root/home/nix/.s";
        for args in [
            argv(&["nixpp", "setup", "--secrets-file", ".s"]),
            argv(&["nixpp", "setup", "--secrets-file", "/home/nix/.s"]),
            argv(&["nixpp", "setup", "--secrets-file=/home/nix/.s"]),
        ] {
            let forwarded =
                native_invocation(&args, Path::new("/home/nix"), Some(OsStr::new(RUNTIME)))
                    .unwrap();
            let joined: Vec<String> = forwarded
                .iter()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect();
            assert!(joined.iter().any(|arg| arg.ends_with(native)), "{joined:?}");
            assert!(joined.iter().all(|arg| !arg.contains("/android")));
        }
    }

    #[test]
    fn native_paths_are_kept_and_android_mounts_are_unwrapped() {
        let cwd = Path::new("/home/nix");
        let home = "/data/data/dev.pschmitt.termux/files/home/.nixpp-setup-secrets";
        for input in [home.to_owned(), format!("/android{home}")] {
            assert_eq!(
                native_path(Path::new(&input), cwd, None).unwrap(),
                Path::new(home)
            );
        }
    }

    #[test]
    fn unmappable_guest_paths_are_refused() {
        let cwd = Path::new("/home/nix");
        let runtime = Some(OsStr::new(RUNTIME));
        for path in ["/tmp/x", "/etc/x", "/home/../etc/x", "../x"] {
            assert!(
                native_path(Path::new(path), cwd, runtime).is_err(),
                "{path}"
            );
        }
        assert!(native_path(Path::new("/home/nix/x"), cwd, None).is_err());
        assert!(native_path(Path::new("/home/nix/x"), cwd, Some(OsStr::new("/data/x"))).is_err());
    }

    const KEY: &str = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleExampleExampleExampleExample";

    fn args(extra: &[&str]) -> Args {
        use clap::Parser;
        #[derive(Parser)]
        struct Wrapper {
            #[command(flatten)]
            args: Args,
        }
        let mut argv = vec!["setup"];
        argv.extend_from_slice(extra);
        Wrapper::try_parse_from(argv).unwrap().args
    }

    fn config(lines: &str) -> HashMap<String, String> {
        parse_config(lines)
    }

    #[test]
    fn host_is_required_and_there_is_no_device_table() {
        use clap::Parser;
        #[derive(Parser)]
        struct Wrapper {
            #[command(flatten)]
            _args: Args,
        }
        assert!(Wrapper::try_parse_from(["setup"]).is_err());
    }

    #[test]
    fn config_lines() {
        let parsed =
            config("# comment\n\ncontroller = ctl.example:2222 \nssh_inputs=a b\nbroken\n");
        assert_eq!(parsed["controller"], "ctl.example:2222");
        assert_eq!(parsed["ssh_inputs"], "a b");
        assert_eq!(parsed.len(), 2);
    }

    #[test]
    fn options_override_the_config_and_items_are_templated() {
        let file = config(&format!(
            "user=fromconfig\ncontroller=ctl.example\ncontroller_host_key={KEY}\nssh_inputs=private other\n\
             cache_store=s3://b?profile=niks3\ncache_public_keys=k1:aa k2:bb\nemail=me@example.invalid\n"
        ));
        let resolved = resolve(&args(&["--host", "phone1"]), &file).unwrap();
        assert_eq!(resolved.user, "fromconfig");
        assert_eq!(resolved.flake, DEFAULT_FLAKE);
        assert_eq!(resolved.key_item, "fromconfig@phone1");
        assert_eq!(resolved.enrollment_item, "nixpp-enrollment-phone1");
        assert_eq!(resolved.email.as_deref(), Some("me@example.invalid"));
        assert_eq!(
            resolved.cache.as_ref().unwrap().public_keys,
            ["k1:aa", "k2:bb"]
        );
        let ResolvedOrigin::Lease {
            profiles,
            ssh_inputs,
            ttl,
            ..
        } = &resolved.origin
        else {
            panic!("expected a lease");
        };
        assert_eq!(ssh_inputs, &["private", "other"]);
        assert_eq!(ttl, "2h");
        assert_eq!(
            profiles,
            &[Profile::Hosted("linux-arm64"), Profile::Adhoc(None)]
        );

        let resolved = resolve(
            &args(&[
                "--host",
                "phone1",
                "--user",
                "someone",
                "--email",
                "x@example.invalid",
                "--ssh-input",
                "only",
                "--worker-profile",
                "standard",
                "--key-item",
                "key-{host}-{user}",
            ]),
            &file,
        )
        .unwrap();
        assert_eq!(resolved.user, "someone");
        assert_eq!(resolved.email.as_deref(), Some("x@example.invalid"));
        assert_eq!(resolved.key_item, "key-phone1-someone");
        let ResolvedOrigin::Lease {
            profiles,
            ssh_inputs,
            ..
        } = resolved.origin
        else {
            panic!("expected a lease");
        };
        assert_eq!(ssh_inputs, ["only"]);
        assert_eq!(profiles, [Profile::Adhoc(Some("standard".into()))]);
    }

    #[test]
    fn missing_or_invalid_settings_are_refused() {
        let none = HashMap::new();
        // no user, no controller
        assert!(resolve(&args(&["--host", "p"]), &none).is_err());
        assert!(resolve(&args(&["--host", "p", "--user", "u"]), &none).is_err());
        let ok = config(&format!(
            "controller=ctl.example\ncontroller_host_key={KEY}\n"
        ));
        assert!(resolve(&args(&["--host", "p", "--user", "u"]), &ok).is_ok());
        for bad in [
            vec!["--host", "p q", "--user", "u"],
            vec!["--host", "p", "--user", "Root"],
            vec!["--host", "p", "--user", "u", "--flake", "github:a/b#x"],
            vec!["--host", "p", "--user", "u", "--flake=-x"],
            vec!["--host", "p", "--user", "u", "--ssh-input", "a/b"],
            vec!["--host", "p", "--user", "u", "--worker-profile=-x"],
            vec!["--host", "p", "--user", "u", "--worker-ttl", "2 h"],
            vec!["--host", "p", "--user", "u", "--dotfiles-repo", "https://x"],
            vec!["--host", "p", "--user", "u", "--cache-store", "https://x"],
        ] {
            assert!(resolve(&args(&bad), &ok).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn published_needs_cache_and_pin() {
        let cache = config("cache_store=s3://b?profile=niks3\ncache_public_keys=k1:aa\n");
        assert!(
            resolve(
                &args(&["--host", "p", "--user", "u", "--published"]),
                &cache
            )
            .is_err()
        );
        let full = config(
            "cache_store=s3://b?profile=niks3\ncache_public_keys=k1:aa\n\
             cache_pin_url=https://cache.example/pins/system-{host}-0\ncache_region=r1\n",
        );
        let resolved =
            resolve(&args(&["--host", "p", "--user", "u", "--published"]), &full).unwrap();
        assert_eq!(
            resolved.origin,
            ResolvedOrigin::Published {
                pin_url: "https://cache.example/pins/system-p-0".to_owned()
            }
        );
        assert_eq!(resolved.cache.unwrap().region, "r1");
        // a published run needs no controller
        let http = config(
            "cache_store=s3://b?profile=niks3\ncache_public_keys=k1:aa\ncache_pin_url=http://x/{host}\n",
        );
        assert!(resolve(&args(&["--host", "p", "--user", "u", "--published"]), &http).is_err());
        assert!(
            resolve(
                &args(&["--host", "p", "--user", "u", "--published"]),
                &none_config()
            )
            .is_err()
        );
    }

    fn none_config() -> HashMap<String, String> {
        HashMap::new()
    }

    fn secrets_file(directory: &Path, content: &str, mode: u32) -> PathBuf {
        let path = directory.join("secrets");
        fs::write(&path, content).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(mode)).unwrap();
        path
    }

    #[test]
    fn secrets_file_is_read_once_and_deleted() {
        let directory = tempfile::tempdir().unwrap();
        let path = secrets_file(
            directory.path(),
            "# note\nRBW_MASTER_PASSWORD=pa=ss word\r\nRBW_TOTP=123456\nOTHER=x\n",
            0o600,
        );
        let secrets = read_secrets_file(&path).unwrap();
        assert_eq!(secrets.password.as_deref(), Some("pa=ss word"));
        assert_eq!(secrets.totp.as_deref(), Some("123456"));
        assert!(!path.exists(), "the file is deleted right after reading");
        assert!(read_secrets_file(&path).is_err());
        // values never reach the Debug output
        let shown = format!("{secrets:?}");
        assert!(!shown.contains("pa=ss") && !shown.contains("123456"));
        assert!(shown.contains("<redacted>"));
    }

    #[test]
    fn secrets_file_is_refused_unless_private_and_regular() {
        let directory = tempfile::tempdir().unwrap();
        for mode in [0o644, 0o640, 0o604, 0o700, 0o400, 0o660] {
            let path = secrets_file(directory.path(), "RBW_MASTER_PASSWORD=x\n", mode);
            assert!(read_secrets_file(&path).is_err(), "mode {mode:o}");
            assert!(path.exists(), "a refused file is left alone");
            fs::remove_file(&path).unwrap();
        }
        let target = secrets_file(directory.path(), "RBW_MASTER_PASSWORD=x\n", 0o600);
        let link = directory.path().join("link");
        std::os::unix::fs::symlink(&target, &link).unwrap();
        assert!(read_secrets_file(&link).is_err());
        assert!(target.exists());
        assert!(read_secrets_file(directory.path()).is_err());
        assert!(read_secrets_file(&directory.path().join("missing")).is_err());
    }

    #[derive(Default)]
    struct Mock {
        unlocked: bool,
        email: RefCell<Option<String>>,
        attempts: RefCell<Vec<(String, String)>>,
        accept: RefCell<Vec<bool>>,
        synced: RefCell<bool>,
    }

    impl Vault for Mock {
        fn unlocked(&self) -> bool {
            self.unlocked
        }
        fn sync(&self) -> Result<()> {
            *self.synced.borrow_mut() = true;
            Ok(())
        }
        fn email(&self) -> Option<String> {
            self.email.borrow().clone()
        }
        fn set_email(&self, email: &str) -> Result<()> {
            *self.email.borrow_mut() = Some(email.to_owned());
            Ok(())
        }
        fn unlock(&self, password: &str, totp: &str) -> Result<()> {
            self.attempts
                .borrow_mut()
                .push((password.to_owned(), totp.to_owned()));
            if self.accept.borrow_mut().remove(0) {
                Ok(())
            } else {
                bail!("rejected")
            }
        }
        fn field(&self, _item: &str, _field: &str) -> Result<Option<String>> {
            Ok(None)
        }
    }

    #[test]
    fn an_unlocked_agent_is_reused_and_synced() {
        let vault = Mock {
            unlocked: true,
            ..Mock::default()
        };
        login(&vault, None, None, &mut |_| panic!("no prompt")).unwrap();
        assert!(*vault.synced.borrow());
        assert!(vault.attempts.borrow().is_empty());
    }

    #[test]
    fn login_uses_the_secrets_then_retries_once_with_a_fresh_totp() {
        let vault = Mock {
            accept: RefCell::new(vec![false, true]),
            ..Mock::default()
        };
        let mut asked = Vec::new();
        login(
            &vault,
            Some(Secrets {
                password: Some("pw".into()),
                totp: Some("111111".into()),
            }),
            Some("me@example.invalid"),
            &mut |prompt| {
                asked.push(prompt.to_owned());
                Ok("222222".to_owned())
            },
        )
        .unwrap();
        assert_eq!(vault.email().as_deref(), Some("me@example.invalid"));
        assert_eq!(
            *vault.attempts.borrow(),
            [
                ("pw".into(), "111111".into()),
                ("pw".into(), "222222".into())
            ]
        );
        assert_eq!(asked, ["Bitwarden TOTP code (blank if none)"]);
    }

    #[test]
    fn login_prompts_without_secrets_and_gives_up_after_the_retry() {
        let vault = Mock {
            accept: RefCell::new(vec![false, false]),
            email: RefCell::new(Some("configured@example.invalid".into())),
            ..Mock::default()
        };
        let mut asked = Vec::new();
        let error = login(&vault, None, None, &mut |prompt| {
            asked.push(prompt.to_owned());
            Ok("x".to_owned())
        })
        .unwrap_err();
        assert!(
            error
                .to_string()
                .contains("failed after one fresh-TOTP retry")
        );
        assert_eq!(
            asked,
            [
                "Bitwarden password",
                "Bitwarden TOTP code (blank if none)",
                "Bitwarden TOTP code (blank if none)"
            ]
        );
        // an email already configured in rbw is not replaced
        assert_eq!(vault.email().as_deref(), Some("configured@example.invalid"));
    }

    #[test]
    fn login_needs_an_email_when_rbw_has_none() {
        let vault = Mock::default();
        let error = login(&vault, None, None, &mut |_| Ok("x".into())).unwrap_err();
        assert!(error.to_string().contains("--email"));
        assert!(vault.attempts.borrow().is_empty());
    }

    struct Items(HashMap<(String, String), String>);

    impl Vault for Items {
        fn unlocked(&self) -> bool {
            true
        }
        fn sync(&self) -> Result<()> {
            Ok(())
        }
        fn email(&self) -> Option<String> {
            None
        }
        fn set_email(&self, _: &str) -> Result<()> {
            Ok(())
        }
        fn unlock(&self, _: &str, _: &str) -> Result<()> {
            Ok(())
        }
        fn field(&self, item: &str, field: &str) -> Result<Option<String>> {
            Ok(self.0.get(&(item.to_owned(), field.to_owned())).cloned())
        }
    }

    #[test]
    fn credentials_are_staged_with_private_permissions() {
        let file = config(&format!(
            "user=u\ncontroller=ctl.example:2222\ncontroller_host_key={KEY}\n\
             cache_store=s3://b?profile=niks3\ncache_public_keys=k1:aa\n"
        ));
        let resolved = resolve(&args(&["--host", "phone1"]), &file).unwrap();
        let mut items = HashMap::new();
        for (item, field, value) in [
            ("u@phone1", "private_key", "PRIVATE"),
            ("u@phone1", "public_key", "ssh-ed25519 AAAApub u@phone1"),
            ("nixpp-enrollment-phone1", "age_key", "AGE"),
            ("nixpp Niks3 cache", "username", "AK"),
            ("nixpp Niks3 cache", "password", "SK"),
        ] {
            items.insert((item.to_owned(), field.to_owned()), value.to_owned());
        }
        let directory = tempfile::tempdir().unwrap();
        stage_credentials(&Items(items.clone()), &resolved, directory.path()).unwrap();
        for name in [
            "id_ed25519",
            "id_ed25519.pub",
            "age_key",
            "cache_credentials",
            "cache_store",
            "cache_public_keys",
            "known_hosts",
        ] {
            let mode = fs::metadata(directory.path().join(name))
                .unwrap()
                .permissions()
                .mode();
            assert_eq!(mode & 0o777, 0o600, "{name}");
        }
        assert_eq!(
            fs::read_to_string(directory.path().join("known_hosts")).unwrap(),
            format!("[ctl.example]:2222 {KEY}\n")
        );
        assert_eq!(
            fs::read_to_string(directory.path().join("cache_credentials")).unwrap(),
            "[niks3]\naws_access_key_id = AK\naws_secret_access_key = SK\n"
        );
        // optional items may be missing; the key may not
        items.remove(&("nixpp-enrollment-phone1".to_owned(), "age_key".to_owned()));
        items.remove(&("nixpp Niks3 cache".to_owned(), "password".to_owned()));
        let directory = tempfile::tempdir().unwrap();
        stage_credentials(&Items(items.clone()), &resolved, directory.path()).unwrap();
        assert!(!directory.path().join("age_key").exists());
        assert!(!directory.path().join("cache_credentials").exists());
        items.remove(&("u@phone1".to_owned(), "private_key".to_owned()));
        let directory = tempfile::tempdir().unwrap();
        assert!(stage_credentials(&Items(items), &resolved, directory.path()).is_err());
    }

    #[test]
    fn github_host_keys_cover_both_names() {
        for host in ["github.com", "ssh.github.com"] {
            assert!(
                GITHUB_KNOWN_HOSTS
                    .lines()
                    .any(|line| line.starts_with(&format!("{host} ssh-ed25519 ")))
            );
        }
    }
}
