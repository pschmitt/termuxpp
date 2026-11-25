//! Lease a disposable rworker for `nixpp setup` and build the guest system on it.
//!
//! The phone talks to the rworker controller over plain SSH with the key it
//! just fetched from Bitwarden (the same `up` / `down` requests the rworker
//! client sends), pins the host key the controller returns, forwards its
//! ssh-agent to the worker and lets Nix fetch private GitHub flake inputs over
//! `git+ssh` at the revision the target flake's lock file names. No access token
//! and no private key ever leaves the phone: OpenStack workers and GitHub
//! runners alike only see the forwarded agent.

use crate::bootstrap::shell_quote;
use crate::{fsutil, store};
use anyhow::{Context, Result, bail, ensure};
use serde_json::Value;
use std::fs::{self, DirBuilder};
use std::io::Write;
use std::os::unix::fs::DirBuilderExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

/// Account of an OpenStack worker; hosted (GitHub) leases use [`HOSTED_USER`].
const ADHOC_USER: &str = "agent";
const HOSTED_USER: &str = "rworker-build";
/// Where the worker keeps the pinned GitHub host keys and cache credentials.
const WORKER_DIR: &str = ".cache/nixpp-setup";
/// The daemon a GitHub runner lease serves its own store with (installed
/// by the rworker job agent).
const HOSTED_DAEMON: &str = "/home/rworker-build/.local/bin/rworker-nix-daemon";
/// Selects that store for Nix commands run in a session on the lease. The
/// build user owns it, so it is trusted and the cache options apply; the
/// runner's system Nix would ignore them.
const HOSTED_NIX_CONFIG: &str = "export NIX_CONFIG=\"build-users-group =\nsandbox = true\nstore = $HOME/.local/share/nix/rworker\"\n";

/// The rworker controller: where leases are requested.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Controller {
    user: Option<String>,
    host: String,
    port: Option<u16>,
    /// `<type> <base64>`; the connection fails for any other host key.
    host_key: String,
}

impl Controller {
    /// `[USER@]HOST[:PORT]` and the host's public key (`ssh-ed25519 AAAA...`).
    pub fn new(spec: &str, host_key: &str) -> Result<Self> {
        let valid = |value: &str| {
            !value.is_empty()
                && !value.starts_with('-')
                && value
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
        };
        let (user, rest) = match spec.split_once('@') {
            Some((user, rest)) => (Some(user), rest),
            None => (None, spec),
        };
        let (host, port) = match rest.rsplit_once(':') {
            Some((host, port)) => (
                host,
                Some(port.parse::<u16>().context("invalid controller port")?),
            ),
            None => (rest, None),
        };
        ensure!(
            valid(host) && user.is_none_or(valid),
            "invalid rworker controller; expected [USER@]HOST[:PORT]"
        );
        let mut words = host_key.split_whitespace();
        let (kind, key) = (words.next(), words.next());
        // A trailing comment is allowed, anything else is not a key.
        ensure!(
            kind.is_some_and(|kind| kind.starts_with("ssh-") || kind.starts_with("ecdsa-"))
                && key.is_some_and(|key| {
                    key.bytes()
                        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'+' | b'/' | b'='))
                }),
            "the rworker controller host key must be '<type> <base64>'"
        );
        Ok(Self {
            user: user.map(str::to_owned),
            host: host.to_owned(),
            port,
            host_key: format!("{} {}", kind.unwrap_or_default(), key.unwrap_or_default()),
        })
    }

    fn destination(&self) -> String {
        match &self.user {
            Some(user) => format!("{user}@{}", self.host),
            None => self.host.clone(),
        }
    }

    /// The `known_hosts` entry that pins the controller.
    pub fn known_hosts_line(&self) -> String {
        match self.port {
            Some(port) if port != 22 => format!("[{}]:{port} {}", self.host, self.host_key),
            _ => format!("{} {}", self.host, self.host_key),
        }
    }
}

/// Key and `known_hosts` file as one side (the native prefix or the guest)
/// sees them.
#[derive(Debug, Clone)]
pub struct Side {
    pub key: PathBuf,
    pub known_hosts: PathBuf,
}

impl Side {
    /// Options of every connection: only our key, only pinned host keys.
    fn pairs(&self) -> Vec<(String, String)> {
        [
            ("BatchMode", "yes".to_owned()),
            ("StrictHostKeyChecking", "yes".to_owned()),
            ("IdentitiesOnly", "yes".to_owned()),
            ("ConnectTimeout", "30".to_owned()),
            ("IdentityFile", self.key.display().to_string()),
            ("UserKnownHostsFile", self.known_hosts.display().to_string()),
        ]
        .map(|(key, value)| (key.to_owned(), value))
        .to_vec()
    }

    fn options(&self) -> Vec<String> {
        let mut options = vec!["-F".to_owned(), "/dev/null".to_owned()];
        for (key, value) in self.pairs() {
            options.extend(["-o".to_owned(), format!("{key}={value}")]);
        }
        options
    }

    fn proxy(&self, controller: &Controller, target: &str) -> String {
        let mut words = vec!["ssh".to_owned(), "-q".to_owned()];
        words.extend(self.options());
        if let Some(port) = controller.port {
            words.extend(["-p".to_owned(), port.to_string()]);
        }
        words.extend([
            "-W".to_owned(),
            target.to_owned(),
            "--".to_owned(),
            controller.destination(),
        ]);
        words
            .iter()
            .map(|word| shell_quote(word).replace('%', "%%"))
            .collect::<Vec<_>>()
            .join(" ")
    }

    /// `ssh` arguments to run `remote` (a shell command line) on the controller.
    fn controller_args(&self, controller: &Controller, remote: &str) -> Vec<String> {
        let mut args = self.options();
        if let Some(port) = controller.port {
            args.extend(["-p".to_owned(), port.to_string()]);
        }
        args.extend(["--".to_owned(), controller.destination(), remote.to_owned()]);
        args
    }
}

/// How the lease is released.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Release {
    Adhoc(String),
    Hosted(String),
}

/// How to reach the worker.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Address {
    Direct(String),
    /// The controller's loopback relay port (GitHub leases).
    Relay(u16),
}

/// A leased worker with its pinned host key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Worker {
    name: String,
    user: &'static str,
    address: Address,
    host_key: String,
    release: Release,
    /// Reach a direct worker through the controller instead of straight.
    via_controller: bool,
}

/// What kind of worker to ask for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Profile {
    /// The controller's default (OpenStack) profile, or the named one.
    Adhoc(Option<String>),
    /// A GitHub runner, by rworker's runner label.
    Hosted(&'static str),
}

impl Profile {
    /// `gh-aarch64` and `gh-x86_64` lease GitHub runners; everything else is
    /// an OpenStack profile of the controller.
    pub fn parse(name: &str) -> Result<Self> {
        ensure!(
            !name.is_empty()
                && !name.starts_with('-')
                && name
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-')),
            "invalid worker profile"
        );
        Ok(match name {
            "gh-aarch64" => Self::Hosted("linux-arm64"),
            "gh-x86_64" => Self::Hosted("linux-x64"),
            "default" => Self::Adhoc(None),
            other => Self::Adhoc(Some(other.to_owned())),
        })
    }
}

/// What `up` needs to know about us.
#[derive(Debug, Clone)]
pub struct Request {
    pub public_key: String,
    pub owner: String,
    pub topic: String,
    pub ttl: String,
}

fn words(items: &[&str]) -> String {
    items
        .iter()
        .map(|item| shell_quote(item))
        .collect::<Vec<_>>()
        .join(" ")
}

fn adhoc_up(request: &Request, profile: Option<&str>) -> String {
    let topic = format!("topic={}", request.topic);
    let mut items = vec![
        "rworker",
        "up",
        "--ssh-key",
        &request.public_key,
        "--owner",
        &request.owner,
        "--meta",
        "agent=nixpp-setup",
        "--meta",
        &topic,
        "--ttl",
        &request.ttl,
    ];
    if let Some(profile) = profile {
        items.extend(["--profile", profile]);
    }
    words(&items)
}

fn hosted_up(request: &Request, runner: &str) -> String {
    words(&[
        "rworker",
        "github-up",
        runner,
        &request.ttl,
        &request.public_key,
        &request.owner,
        &request.topic,
    ])
}

fn release_command(release: &Release, owner: &str) -> String {
    match release {
        Release::Adhoc(name) => words(&["rworker", "down", name, "--owner", owner]),
        Release::Hosted(id) => words(&["rworker", "github-down", id, owner]),
    }
}

fn field<'a>(value: &'a Value, name: &str) -> Result<&'a str> {
    value
        .get(name)
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .with_context(|| format!("the controller reply has no {name}"))
}

fn valid_worker_name(name: &str) -> bool {
    store::is_host_name(name)
}

fn valid_address(address: &str) -> bool {
    !address.starts_with('-')
        && !address.is_empty()
        && address
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b':'))
}

fn parse_host_key(key: &str) -> Result<String> {
    // Validated like the controller's key; the name is the alias.
    let mut parts = key.split_whitespace();
    match (parts.next(), parts.next()) {
        (Some(kind), Some(data))
            if (kind.starts_with("ssh-") || kind.starts_with("ecdsa-"))
                && data
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'+' | b'/' | b'=')) =>
        {
            Ok(format!("{kind} {data}"))
        }
        _ => bail!("the controller returned an invalid worker host key"),
    }
}

/// The reply of `rworker up`: `name`, `ip`, `host_key`.
fn parse_adhoc(reply: &str) -> Result<Worker> {
    let value: Value = serde_json::from_str(reply.trim()).context("invalid `up` reply")?;
    let name = field(&value, "name")?;
    let address = field(&value, "ip")?;
    ensure!(
        valid_worker_name(name) && valid_address(address),
        "the controller returned an invalid worker name or address"
    );
    Ok(Worker {
        name: name.to_owned(),
        user: ADHOC_USER,
        address: Address::Direct(address.to_owned()),
        host_key: parse_host_key(field(&value, "host_key")?)?,
        release: Release::Adhoc(name.to_owned()),
        via_controller: false,
    })
}

/// The first reply of `github-up`: the lease id and the worker's name suffix.
fn parse_hosted_started(reply: &str) -> Result<(String, String)> {
    let value: Value = serde_json::from_str(reply.trim()).context("invalid `github-up` reply")?;
    let id = field(&value, "id")?;
    let suffix = value
        .get("worker_suffix")
        .and_then(Value::as_str)
        .unwrap_or(id);
    ensure!(
        store::is_host_name(id) && store::is_host_name(suffix),
        "the controller returned an invalid lease"
    );
    Ok((id.to_owned(), format!("gha-{suffix}")))
}

enum Hosted {
    Waiting,
    Ended(String),
    Ready(Worker),
}

fn parse_hosted_status(reply: &str, id: &str, name: &str) -> Result<Hosted> {
    let value: Value =
        serde_json::from_str(reply.trim()).context("invalid `github-status` reply")?;
    Ok(match value.get("state").and_then(Value::as_str) {
        Some("ready") => {
            let endpoint = value.get("endpoint").unwrap_or(&Value::Null);
            let port = endpoint
                .get("port")
                .and_then(Value::as_u64)
                .and_then(|port| u16::try_from(port).ok())
                .filter(|port| *port >= 1024)
                .context("the runner has no relay port")?;
            Hosted::Ready(Worker {
                name: name.to_owned(),
                user: HOSTED_USER,
                address: Address::Relay(port),
                host_key: parse_host_key(field(endpoint, "host_key")?)?,
                release: Release::Hosted(id.to_owned()),
                via_controller: true,
            })
        }
        Some("ended" | "cancelling" | "dispatch_unknown") => Hosted::Ended(
            value
                .get("state")
                .and_then(Value::as_str)
                .unwrap_or("ended")
                .to_owned(),
        ),
        _ => Hosted::Waiting,
    })
}

impl Worker {
    /// `known_hosts` entry that pins this worker under its name.
    pub fn known_hosts_line(&self) -> String {
        format!("{} {}", self.name, self.host_key)
    }

    /// What is particular to this worker: its pinned alias and the route.
    fn pairs(&self, side: &Side, controller: &Controller) -> Vec<(String, String)> {
        let mut pairs = vec![("HostKeyAlias".to_owned(), self.name.clone())];
        match &self.address {
            Address::Direct(address) => {
                pairs.push(("Hostname".to_owned(), address.clone()));
                if self.via_controller {
                    let proxy = side.proxy(controller, &format!("{address}:22"));
                    pairs.push(("ProxyCommand".to_owned(), proxy));
                }
            }
            Address::Relay(port) => {
                pairs.push(("Hostname".to_owned(), "127.0.0.1".to_owned()));
                pairs.push(("Port".to_owned(), port.to_string()));
                let proxy = side.proxy(controller, &format!("127.0.0.1:{port}"));
                pairs.push(("ProxyCommand".to_owned(), proxy));
            }
        }
        pairs
    }

    /// `ssh` options for this worker (destination: [`Worker::destination`]).
    pub fn ssh_options(&self, side: &Side, controller: &Controller) -> Vec<String> {
        let mut options = side.options();
        for (key, value) in self.pairs(side, controller) {
            options.extend(["-o".to_owned(), format!("{key}={value}")]);
        }
        options
    }

    /// A GitHub runner: reached through the controller's relay.
    pub fn is_hosted(&self) -> bool {
        matches!(self.release, Release::Hosted(_))
    }

    /// `user@name`: the alias carries the real address.
    pub fn destination(&self) -> String {
        format!("{}@{}", self.user, self.name)
    }

    /// The Nix store URI of the worker. A GitHub runner keeps the lease's
    /// store in the build user's home and serves it with its own daemon.
    pub fn store_uri(&self) -> String {
        if self.is_hosted() {
            format!(
                "ssh-ng://{}?remote-program={HOSTED_DAEMON}",
                self.destination()
            )
        } else {
            format!("ssh-ng://{}", self.destination())
        }
    }

    /// An ssh_config for `side` that reaches only this worker, for tools that
    /// cannot take a ProxyCommand on their command line (`nix copy`).
    pub fn ssh_config(&self, side: &Side, controller: &Controller) -> String {
        let mut config = format!("Host {}\n", self.name);
        for (key, value) in side.pairs().into_iter().chain(self.pairs(side, controller)) {
            config.push_str(&format!("  {key} {value}\n"));
        }
        config
    }
}

/// A lease that is released when dropped, also after a failure.
pub struct Lease {
    controller: Controller,
    side: Side,
    owner: String,
    worker: Worker,
    released: bool,
}

impl Lease {
    pub fn worker(&self) -> &Worker {
        &self.worker
    }

    /// Delete the worker. Idempotent.
    pub fn release(&mut self) {
        if std::mem::replace(&mut self.released, true) {
            return;
        }
        let command = release_command(&self.worker.release, &self.owner);
        eprintln!("Releasing worker {}…", self.worker.name);
        let status = Command::new("ssh")
            .args(self.side.controller_args(&self.controller, &command))
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .status();
        if !status.is_ok_and(|status| status.success()) {
            eprintln!(
                "Could not release worker {}; the controller removes it when its lease expires.",
                self.worker.name
            );
        }
    }
}

impl Drop for Lease {
    fn drop(&mut self) {
        self.release();
    }
}

fn controller_output(side: &Side, controller: &Controller, remote: &str) -> Result<String> {
    let output = Command::new("ssh")
        .args(side.controller_args(controller, remote))
        .stdin(Stdio::null())
        .stderr(Stdio::inherit())
        .output()
        .context("unable to start ssh")?;
    ensure!(
        output.status.success(),
        "the rworker controller refused the request ({})",
        output.status
    );
    Ok(String::from_utf8(output.stdout)?)
}

/// Lease a worker, trying the profiles in order until one is ready.
pub fn lease(
    controller: &Controller,
    side: &Side,
    request: &Request,
    profiles: &[Profile],
    queue_timeout: Duration,
) -> Result<Lease> {
    ensure!(!profiles.is_empty(), "no worker profile to lease");
    let mut failures = Vec::new();
    for profile in profiles {
        eprintln!("Leasing a build worker ({profile:?})…");
        let result = match profile {
            Profile::Adhoc(name) => {
                controller_output(side, controller, &adhoc_up(request, name.as_deref()))
                    .and_then(|reply| parse_adhoc(&reply))
            }
            Profile::Hosted(runner) => hosted(side, controller, request, runner, queue_timeout),
        };
        match result {
            Ok(worker) => {
                return Ok(Lease {
                    controller: controller.clone(),
                    side: side.clone(),
                    owner: request.owner.clone(),
                    worker,
                    released: false,
                });
            }
            Err(error) => {
                eprintln!("Could not lease a worker: {error:#}");
                failures.push(format!("{profile:?}: {error:#}"));
            }
        }
    }
    bail!("no build worker available ({})", failures.join("; "))
}

fn hosted(
    side: &Side,
    controller: &Controller,
    request: &Request,
    runner: &str,
    queue_timeout: Duration,
) -> Result<Worker> {
    let (id, name) = parse_hosted_started(&controller_output(
        side,
        controller,
        &hosted_up(request, runner),
    )?)?;
    let release = Release::Hosted(id.clone());
    let started = Instant::now();
    let result = (|| loop {
        ensure!(
            started.elapsed() < queue_timeout,
            "timed out waiting for a runner"
        );
        let reply = controller_output(side, controller, &words(&["rworker", "github-status", &id]));
        match reply.and_then(|reply| parse_hosted_status(&reply, &id, &name)) {
            Ok(Hosted::Ready(worker)) => return Ok(worker),
            Ok(Hosted::Ended(state)) => bail!("the runner lease ended ({state})"),
            Ok(Hosted::Waiting) | Err(_) => std::thread::sleep(Duration::from_secs(3)),
        }
    })();
    if result.is_err() {
        // Do not leave a queued runner behind.
        let _ = Command::new("ssh")
            .args(side.controller_args(controller, &release_command(&release, &request.owner)))
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .status();
    }
    result
}

/// A private ssh-agent holding only the enrollment key.
pub struct Agent {
    child: Child,
    socket: PathBuf,
}

impl Agent {
    pub fn start(directory: &Path, key: &Path) -> Result<Self> {
        let socket = directory.join("agent.sock");
        let child = Command::new("ssh-agent")
            .args(["-D", "-a"])
            .arg(&socket)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .spawn()
            .context("unable to start ssh-agent")?;
        let agent = Self { child, socket };
        for _ in 0..50 {
            if agent.socket.exists() {
                break;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        let status = Command::new("ssh-add")
            .arg(key)
            .env("SSH_AUTH_SOCK", &agent.socket)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .status()
            .context("unable to run ssh-add")?;
        ensure!(status.success(), "ssh-add rejected the enrollment key");
        Ok(agent)
    }

    pub fn socket(&self) -> &Path {
        &self.socket
    }
}

impl Drop for Agent {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = fs::remove_file(&self.socket);
    }
}

/// The root inputs of a flake lock file that are fetched from GitHub, as
/// `(name, owner, repo, rev)`.
fn locked_github_inputs(lock: &Value) -> Vec<(String, String, String, String)> {
    let nodes = &lock["nodes"];
    let root = nodes["root"]["inputs"].as_object();
    root.into_iter()
        .flatten()
        .filter_map(|(name, node)| {
            // A list is a `follows` path: nothing to fetch.
            let locked = &nodes[node.as_str()?]["locked"];
            (locked["type"] == "github").then_some((
                name.clone(),
                locked["owner"].as_str()?.to_owned(),
                locked["repo"].as_str()?.to_owned(),
                locked["rev"].as_str()?.to_owned(),
            ))
        })
        .collect()
}

fn is_rev(value: &str) -> bool {
    value.len() == 40
        && value
            .bytes()
            .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

fn is_repo_part(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
}

/// `--override-input NAME git+ssh://...?rev=REV` for each named input, at the
/// revision the lock file records: the same source tree as the GitHub tarball
/// the lock names, but fetched with the forwarded SSH key instead of a token.
pub fn ssh_input_overrides(lock: &str, names: &[String]) -> Result<Vec<(String, String)>> {
    let lock: Value = serde_json::from_str(lock).context("invalid flake.lock")?;
    let inputs = locked_github_inputs(&lock);
    names
        .iter()
        .map(|name| {
            let (_, owner, repo, rev) = inputs
                .iter()
                .find(|(input, ..)| input == name)
                .with_context(|| format!("the flake has no locked GitHub input {name}"))?;
            ensure!(
                is_repo_part(owner) && is_repo_part(repo) && is_rev(rev),
                "invalid lock entry for input {name}"
            );
            Ok((
                name.clone(),
                format!("git+ssh://git@github.com/{owner}/{repo}?rev={rev}"),
            ))
        })
        .collect()
}

/// The flake reference of the revision `nix flake prefetch --json` resolved,
/// so the lock file read and the system built are the same revision.
pub fn pinned_flake(prefetch: &str, flake: &str) -> Result<(String, PathBuf)> {
    let value: Value = serde_json::from_str(prefetch).context("invalid prefetch reply")?;
    let path = value["storePath"]
        .as_str()
        .filter(|path| store::is_store_root(path))
        .context("prefetch returned no store path")?;
    let locked = &value["locked"];
    let pinned = match (
        locked["type"].as_str(),
        locked["owner"].as_str(),
        locked["repo"].as_str(),
        locked["rev"].as_str(),
    ) {
        (Some("github"), Some(owner), Some(repo), Some(rev))
            if is_repo_part(owner) && is_repo_part(repo) && is_rev(rev) =>
        {
            format!("github:{owner}/{repo}/{rev}")
        }
        _ => flake.to_owned(),
    };
    Ok((pinned, PathBuf::from(path)))
}

/// What to build and how Nix may reach the cache and the private inputs.
#[derive(Debug, Clone)]
pub struct Build {
    pub flake: String,
    pub target_attribute: String,
    pub ssh_inputs: Vec<String>,
    pub cache: Option<CacheOptions>,
    /// GitHub host keys for `git+ssh`.
    pub github_known_hosts: String,
    /// Resolve the flake afresh instead of from Nix's cache of the last hour
    /// (`nixpp upgrade`: the latest revision or nothing).
    pub refresh: bool,
}

/// A read-only binary cache the worker may substitute from.
#[derive(Debug, Clone)]
pub struct CacheOptions {
    pub store: String,
    pub public_keys: String,
    /// AWS shared credentials file content (profile named in the store URI).
    pub credentials: String,
}

/// The shell script run on the worker, after the files it names are in place.
pub fn build_script(
    flake: &str,
    attribute: &str,
    overrides: &[(String, String)],
    cache: Option<&CacheOptions>,
    hosted: bool,
    refresh: bool,
) -> String {
    let mut script = String::from("set -eu\n");
    if hosted {
        script.push_str(HOSTED_NIX_CONFIG);
    }
    script.push_str(
        "export GIT_SSH_COMMAND=\"ssh -oBatchMode=yes -oStrictHostKeyChecking=yes \
         -oUserKnownHostsFile=$HOME/.cache/nixpp-setup/github_known_hosts\"\n",
    );
    let mut command = vec![
        "nix".to_owned(),
        "build".to_owned(),
        "--no-link".to_owned(),
        "--print-out-paths".to_owned(),
    ];
    if refresh {
        command.push("--refresh".to_owned());
    }
    if let Some(cache) = cache {
        script.push_str("export AWS_SHARED_CREDENTIALS_FILE=\"$HOME/.cache/nixpp-setup/aws\"\n");
        command.extend(
            [
                "--option",
                "extra-substituters",
                &cache.store,
                "--option",
                "extra-trusted-public-keys",
                &cache.public_keys,
            ]
            .map(str::to_owned),
        );
    }
    for (name, url) in overrides {
        command.extend(["--override-input".to_owned(), name.clone(), url.clone()]);
    }
    command.extend(["--".to_owned(), format!("{flake}#{attribute}")]);
    // Nothing is left to clean up: replace the shell.
    script.push_str("exec ");
    script.push_str(
        &command
            .iter()
            .map(|word| shell_quote(word))
            .collect::<Vec<_>>()
            .join(" "),
    );
    script.push('\n');
    script
}

/// The `ssh` command for `script` on the worker; with an agent socket the
/// agent is forwarded (`-A`), so the key never leaves the phone.
fn worker_command(lease: &Lease, agent_socket: Option<&Path>, script: &str) -> Command {
    let mut command = Command::new("ssh");
    command.args(lease.worker.ssh_options(&lease.side, &lease.controller));
    if let Some(socket) = agent_socket {
        command.arg("-A").env("SSH_AUTH_SOCK", socket);
    }
    command.args(["--", &lease.worker.destination(), script]);
    command
}

fn run_on_worker(
    lease: &Lease,
    agent: Option<&Agent>,
    script: &str,
    stdin: Option<&str>,
    capture: bool,
) -> Result<String> {
    let mut command = worker_command(lease, agent.map(Agent::socket), script);
    command
        .stdin(if stdin.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stderr(Stdio::inherit());
    if capture {
        command.stdout(Stdio::piped());
    } else {
        command.stdout(Stdio::null());
    }
    let mut child = command.spawn().context("unable to start ssh")?;
    if let (Some(input), Some(mut pipe)) = (stdin, child.stdin.take()) {
        pipe.write_all(input.as_bytes())?;
    }
    let output = child.wait_with_output()?;
    ensure!(
        output.status.success(),
        "the build worker command failed ({})",
        output.status
    );
    Ok(String::from_utf8(output.stdout)?)
}

fn put_file(lease: &Lease, name: &str, content: &str) -> Result<()> {
    let script =
        format!("umask 077; mkdir -p \"$HOME/{WORKER_DIR}\"; cat > \"$HOME/{WORKER_DIR}/{name}\"");
    run_on_worker(lease, None, &script, Some(content), false).map(drop)
}

/// Build the target on the leased worker and return its store path.
pub fn build(lease: &Lease, agent: Option<&Agent>, spec: &Build) -> Result<String> {
    let mut flake = spec.flake.clone();
    let mut overrides = Vec::new();
    if !spec.ssh_inputs.is_empty() {
        eprintln!("Resolving the flake lock on the worker…");
        let store = if lease.worker.is_hosted() {
            HOSTED_NIX_CONFIG
        } else {
            ""
        };
        let prefetch = run_on_worker(
            lease,
            None,
            &format!(
                "{store}exec nix flake prefetch --json {}-- {}",
                if spec.refresh { "--refresh " } else { "" },
                shell_quote(&spec.flake)
            ),
            None,
            true,
        )?;
        let (pinned, path) = pinned_flake(&prefetch, &spec.flake)?;
        // Through Nix: a runner's store lives under the build user's home.
        let lock = run_on_worker(
            lease,
            None,
            &format!(
                "{store}exec nix store cat {}/flake.lock",
                shell_quote(&fsutil::path_str(&path)?)
            ),
            None,
            true,
        )?;
        overrides = ssh_input_overrides(&lock, &spec.ssh_inputs)?;
        flake = pinned;
        put_file(lease, "github_known_hosts", &spec.github_known_hosts)?;
    }
    if let Some(cache) = &spec.cache {
        put_file(lease, "aws", &cache.credentials)?;
    }
    eprintln!("Building the guest configuration on {}…", lease.worker.name);
    let script = build_script(
        &flake,
        &spec.target_attribute,
        &overrides,
        spec.cache.as_ref(),
        lease.worker.is_hosted(),
        spec.refresh,
    );
    let output = run_on_worker(lease, agent, &script, None, true)?;
    let generation = output
        .lines()
        .rev()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .unwrap_or_default();
    ensure!(
        store::is_store_root(generation),
        "Nix returned an invalid generation"
    );
    Ok(generation.to_owned())
}

/// A private temporary directory removed on drop.
pub struct Scratch(PathBuf);

impl Scratch {
    pub fn new(prefix: &str) -> Result<Self> {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |time| time.subsec_nanos());
        let path = std::env::temp_dir().join(format!("{prefix}.{}.{nanos:x}", std::process::id()));
        DirBuilder::new().mode(0o700).create(&path)?;
        Ok(Self(path))
    }

    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: &str = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleExampleExampleExampleExample";

    fn controller() -> Controller {
        Controller::new("jump@controller.example:2222", KEY).unwrap()
    }

    fn side(name: &str) -> Side {
        Side {
            key: PathBuf::from(format!("/{name}/id")),
            known_hosts: PathBuf::from(format!("/{name}/known_hosts")),
        }
    }

    fn request() -> Request {
        Request {
            public_key: "ssh-ed25519 AAAAkey phone".to_owned(),
            owner: "user@host".to_owned(),
            topic: "enroll host".to_owned(),
            ttl: "2h".to_owned(),
        }
    }

    #[test]
    fn controller_spec_and_pinned_line() {
        let controller = controller();
        assert_eq!(controller.destination(), "jump@controller.example");
        assert_eq!(
            controller.known_hosts_line(),
            format!("[controller.example]:2222 {KEY}")
        );
        let plain = Controller::new("controller.example", &format!("{KEY} comment")).unwrap();
        assert_eq!(
            plain.known_hosts_line(),
            format!("controller.example {KEY}")
        );
        for bad in [
            "",
            "-oProxyCommand=x",
            "a b",
            "u@",
            "host:port",
            "host:99999",
        ] {
            assert!(Controller::new(bad, KEY).is_err(), "{bad}");
        }
        for bad in ["", "ssh-ed25519", "rsa AAAA", "ssh-ed25519 AA AA;x"] {
            let key = if bad == "ssh-ed25519 AA AA;x" {
                "ssh-ed25519 AA;x"
            } else {
                bad
            };
            assert!(Controller::new("controller.example", key).is_err(), "{key}");
        }
    }

    #[test]
    fn controller_requests() {
        let side = side("native");
        let args = side.controller_args(&controller(), "rworker down w --owner o");
        assert_eq!(args[..2], ["-F", "/dev/null"]);
        assert!(args.contains(&"StrictHostKeyChecking=yes".to_owned()));
        assert!(args.contains(&"UserKnownHostsFile=/native/known_hosts".to_owned()));
        assert!(args.windows(2).any(|pair| pair == ["-p", "2222"]));
        assert_eq!(
            args[args.len() - 3..],
            ["--", "jump@controller.example", "rworker down w --owner o"]
        );
        let up = adhoc_up(&request(), Some("standard"));
        assert_eq!(
            up,
            "rworker up --ssh-key 'ssh-ed25519 AAAAkey phone' --owner user@host \
             --meta agent=nixpp-setup --meta 'topic=enroll host' --ttl 2h --profile standard"
        );
        assert!(!adhoc_up(&request(), None).contains("--profile"));
        assert_eq!(
            hosted_up(&request(), "linux-arm64"),
            "rworker github-up linux-arm64 2h 'ssh-ed25519 AAAAkey phone' user@host 'enroll host'"
        );
        assert_eq!(
            release_command(&Release::Adhoc("adhoc-1".into()), "user@host"),
            "rworker down adhoc-1 --owner user@host"
        );
        assert_eq!(
            release_command(&Release::Hosted("abc".into()), "user@host"),
            "rworker github-down abc user@host"
        );
    }

    #[test]
    fn profiles() {
        assert_eq!(
            Profile::parse("gh-aarch64").unwrap(),
            Profile::Hosted("linux-arm64")
        );
        assert_eq!(
            Profile::parse("gh-x86_64").unwrap(),
            Profile::Hosted("linux-x64")
        );
        assert_eq!(Profile::parse("default").unwrap(), Profile::Adhoc(None));
        assert_eq!(
            Profile::parse("large-builder").unwrap(),
            Profile::Adhoc(Some("large-builder".into()))
        );
        assert!(Profile::parse("-x").is_err());
        assert!(Profile::parse("a b").is_err());
    }

    #[test]
    fn adhoc_reply_pins_the_returned_host_key() {
        let reply = format!(
            r#"{{"name":"adhoc-6d1b4f57","ip":"100.64.1.2","host_key":"{KEY} root@x","expires_at":1}}"#
        );
        let worker = parse_adhoc(&reply).unwrap();
        assert_eq!(worker.known_hosts_line(), format!("adhoc-6d1b4f57 {KEY}"));
        assert_eq!(worker.destination(), "agent@adhoc-6d1b4f57");
        let options = worker.ssh_options(&side("native"), &controller());
        assert!(options.contains(&"HostKeyAlias=adhoc-6d1b4f57".to_owned()));
        assert!(options.contains(&"Hostname=100.64.1.2".to_owned()));
        assert!(
            !options
                .iter()
                .any(|option| option.starts_with("ProxyCommand"))
        );
        for bad in [
            r#"{"name":"w","ip":"-oProxyCommand=x","host_key":"ssh-ed25519 AAAA"}"#,
            r#"{"name":"w x","ip":"1.2.3.4","host_key":"ssh-ed25519 AAAA"}"#,
            r#"{"name":"w","ip":"1.2.3.4","host_key":"nonsense"}"#,
            r#"{"name":"w","ip":"1.2.3.4"}"#,
            "not json",
        ] {
            assert!(parse_adhoc(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn hosted_lease_goes_through_the_controller_relay() {
        let (id, name) =
            parse_hosted_started(r#"{"id":"0123456789abcdef","worker_suffix":"01234567"}"#)
                .unwrap();
        assert_eq!(
            (id.as_str(), name.as_str()),
            ("0123456789abcdef", "gha-01234567")
        );
        assert!(matches!(
            parse_hosted_status(r#"{"state":"queued"}"#, &id, &name).unwrap(),
            Hosted::Waiting
        ));
        assert!(matches!(
            parse_hosted_status(r#"{"state":"ended"}"#, &id, &name).unwrap(),
            Hosted::Ended(_)
        ));
        let ready =
            format!(r#"{{"state":"ready","endpoint":{{"port":40123,"host_key":"{KEY}"}}}}"#);
        let Hosted::Ready(worker) = parse_hosted_status(&ready, &id, &name).unwrap() else {
            panic!("expected a ready worker");
        };
        assert_eq!(worker.destination(), "rworker-build@gha-01234567");
        let options = worker.ssh_options(&side("native"), &controller());
        assert!(options.contains(&"Hostname=127.0.0.1".to_owned()));
        assert!(options.contains(&"Port=40123".to_owned()));
        let proxy = options
            .iter()
            .find_map(|option| option.strip_prefix("ProxyCommand="))
            .unwrap();
        assert!(proxy.contains("-W 127.0.0.1:40123"));
        assert!(proxy.contains("jump@controller.example"));
        assert!(proxy.contains("-p 2222"));
        // Nix gets the relay through an ssh config of the guest's own paths.
        let guest = side("guest");
        let config = worker.ssh_config(&guest, &controller());
        assert!(config.starts_with("Host gha-01234567\n"));
        assert!(config.contains("  ProxyCommand ssh -q -F /dev/null"));
        assert!(config.contains("  HostKeyAlias gha-01234567\n"));
        assert!(config.contains("  IdentityFile /guest/id\n"));
        assert!(config.contains("UserKnownHostsFile=/guest/known_hosts"));
        // The runner serves the lease's own store with the job agent's daemon.
        assert_eq!(
            worker.store_uri(),
            "ssh-ng://rworker-build@gha-01234567?remote-program=/home/rworker-build/.local/bin/rworker-nix-daemon"
        );
        let ports = parse_hosted_status(
            r#"{"state":"ready","endpoint":{"port":22,"host_key":"ssh-ed25519 AAAA"}}"#,
            &id,
            &name,
        );
        assert!(ports.is_err(), "privileged relay ports are refused");
    }

    #[test]
    fn hosted_builds_forward_the_agent_and_upload_no_key() {
        let ready =
            format!(r#"{{"state":"ready","endpoint":{{"port":40123,"host_key":"{KEY}"}}}}"#);
        let Hosted::Ready(worker) =
            parse_hosted_status(&ready, "0123456789abcdef", "gha-01234567").unwrap()
        else {
            panic!("expected a ready worker");
        };
        assert!(worker.is_hosted());
        let lease = Lease {
            controller: controller(),
            side: side("native"),
            owner: "user@host".to_owned(),
            worker,
            released: true,
        };
        let command = worker_command(&lease, Some(Path::new("/run/agent.sock")), "true");
        let args: Vec<_> = command
            .get_args()
            .map(|arg| arg.to_string_lossy())
            .collect();
        assert!(args.iter().any(|arg| arg == "-A"));
        assert!(!args.iter().any(|arg| arg.contains("ForwardAgent=no")));
        assert_eq!(
            command
                .get_envs()
                .find(|(name, _)| *name == "SSH_AUTH_SOCK")
                .and_then(|(_, value)| value),
            Some(std::ffi::OsStr::new("/run/agent.sock"))
        );
        let none = worker_command(&lease, None, "true");
        assert!(!none.get_args().any(|arg| arg == "-A"));
        let script = build_script("f", "a", &[], None, true, false);
        assert!(!script.contains("nixpp-setup/key"));
        assert!(!script.contains("-oIdentitiesOnly"));
    }

    const LOCK: &str = r#"{
      "nodes": {
        "root": {"inputs": {"private": "private_2", "public": "public", "alias": ["public"], "tree": "tree"}},
        "private_2": {"locked": {"type": "github", "owner": "someone", "repo": "secrets",
          "rev": "0123456789abcdef0123456789abcdef01234567", "narHash": "sha256-x"}},
        "public": {"locked": {"type": "github", "owner": "o", "repo": "r",
          "rev": "fedcba9876543210fedcba9876543210fedcba98"}},
        "tree": {"locked": {"type": "tarball", "url": "https://example.invalid/x.tar.gz"}}
      },
      "root": "root", "version": 7
    }"#;

    #[test]
    fn ssh_input_override_uses_the_locked_revision() {
        let overrides = ssh_input_overrides(LOCK, &["private".to_owned()]).unwrap();
        assert_eq!(
            overrides,
            [(
                "private".to_owned(),
                "git+ssh://git@github.com/someone/secrets?rev=0123456789abcdef0123456789abcdef01234567"
                    .to_owned()
            )]
        );
        // follows entries, missing names and non-GitHub inputs are refused
        for name in ["alias", "missing", "tree"] {
            assert!(
                ssh_input_overrides(LOCK, &[name.to_owned()]).is_err(),
                "{name}"
            );
        }
        let injected = LOCK.replace("someone", "x/../y");
        assert!(ssh_input_overrides(&injected, &["private".to_owned()]).is_err());
        let short = LOCK.replace("0123456789abcdef0123456789abcdef01234567", "abc");
        assert!(ssh_input_overrides(&short, &["private".to_owned()]).is_err());
    }

    #[test]
    fn prefetch_pins_the_revision() {
        let reply = r#"{"storePath":"/nix/store/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-source",
          "locked":{"type":"github","owner":"o","repo":"r","rev":"fedcba9876543210fedcba9876543210fedcba98"}}"#;
        let (flake, path) = pinned_flake(reply, "github:o/r/main").unwrap();
        assert_eq!(flake, "github:o/r/fedcba9876543210fedcba9876543210fedcba98");
        assert_eq!(
            path,
            PathBuf::from("/nix/store/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-source")
        );
        let other = r#"{"storePath":"/nix/store/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-source","locked":{"type":"git"}}"#;
        assert_eq!(
            pinned_flake(other, "git+https://e/x").unwrap().0,
            "git+https://e/x"
        );
        assert!(pinned_flake(r#"{"storePath":"/tmp/x"}"#, "f").is_err());
    }

    #[test]
    fn build_script_has_overrides_and_cache_options() {
        let cache = CacheOptions {
            store: "s3://bucket?region=r&profile=niks3".to_owned(),
            public_keys: "key-1:abc key-2:def".to_owned(),
            credentials: "[niks3]\n".to_owned(),
        };
        let script = build_script(
            "github:o/r/rev",
            "termuxppConfigurations.host.system",
            &[(
                "private".to_owned(),
                "git+ssh://git@github.com/a/b?rev=c".to_owned(),
            )],
            Some(&cache),
            false,
            false,
        );
        assert!(script.starts_with("set -eu\nexport GIT_SSH_COMMAND=\""));
        assert!(!script.contains("--refresh"));
        assert!(script.contains("\nexec 'nix' ") || script.contains("\nexec nix "));
        assert!(!script.contains("IdentitiesOnly"));
        assert!(!script.contains(" -i "), "the agent supplies the key");
        assert!(!script.contains("trap "));
        assert!(script.contains("-oStrictHostKeyChecking=yes"));
        assert!(script.contains("AWS_SHARED_CREDENTIALS_FILE=\"$HOME/.cache/nixpp-setup/aws\""));
        assert!(
            script.contains("--option extra-substituters 's3://bucket?region=r&profile=niks3'")
        );
        assert!(script.contains("--option extra-trusted-public-keys 'key-1:abc key-2:def'"));
        assert!(script.contains("--override-input private 'git+ssh://git@github.com/a/b?rev=c'"));
        assert!(script.ends_with("-- 'github:o/r/rev#termuxppConfigurations.host.system'\n"));
        assert!(
            !script.contains("[niks3]"),
            "credentials never enter the command line"
        );
        let plain = build_script("f", "a", &[], None, false, false);
        assert!(!plain.contains("AWS_SHARED"));
        assert!(!plain.contains("--override-input"));
        assert!(!plain.contains("NIX_CONFIG"));
        let hosted = build_script("f", "a", &[], Some(&cache), true, false);
        assert!(hosted.starts_with(&format!("set -eu\n{HOSTED_NIX_CONFIG}")));
        assert!(hosted.contains("store = $HOME/.local/share/nix/rworker"));
        let latest = build_script("f", "a", &[], None, false, true);
        assert!(latest.contains("--refresh"));
    }
}
