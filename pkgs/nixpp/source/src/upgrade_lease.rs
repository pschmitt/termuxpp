//! `nixpp upgrade --rworker` through a leased worker.
//!
//! A phone is on neither of the meshes the OpenStack workers live on. It can
//! reach the rworker controller, though, and the controller's loopback relay
//! reaches a native GitHub runner: the route `nixpp setup` already uses
//! ([`crate::lease`]). The guest uses it the same way. It leases a runner with
//! its own SSH key, builds the latest system in the lease's own store and
//! copies the result back. There is no other source for the system: a failed
//! lease or build ends the upgrade with an error, never with an older or
//! cached system.

use crate::error::{Error, Result};
use crate::guest::{Builder, UpgradeCache, UpgradeLease};
use crate::lease::{self, Agent, Build, CacheOptions, Controller, Profile, Request, Scratch, Side};
use crate::{fsutil, setup, store};
use anyhow::Context;
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

/// Native ARM64 builds; an OpenStack worker is x86_64 and the phone cannot
/// reach it.
const DEFAULT_PROFILE: &str = "gh-aarch64";
const DEFAULT_TTL: &str = "1h";
const DEFAULT_QUEUE_TIMEOUT: u64 = 420;
/// The lease topic; the controller's gate (its forced command) accepts
/// `nixpp setup <host>` and `nixpp upgrade <host>`.
const TOPIC_PREFIX: &str = "nixpp upgrade";

/// What to build and with which settings.
pub struct Job<'a> {
    pub flake: &'a str,
    pub host: &'a str,
    pub lease: &'a UpgradeLease,
    pub cache: Option<&'a UpgradeCache>,
    /// Resolve the flake afresh (the latest revision).
    pub refresh: bool,
}

/// The validated settings of the `lease` section.
#[derive(Debug, PartialEq, Eq)]
pub struct Plan {
    controller: Controller,
    profiles: Vec<Profile>,
    ttl: String,
    queue_timeout: Duration,
    owner: String,
    ssh_inputs: Vec<String>,
    key: PathBuf,
}

impl Plan {
    pub fn resolve(lease: &UpgradeLease, home: Option<&Path>) -> Result<Self> {
        let invalid = |message: &str| Error::invalid(format!("invalid lease settings: {message}"));
        let controller = Controller::new(&lease.controller, &lease.controller_host_key)
            .map_err(|error| invalid(&format!("{error:#}")))?;
        let names = if lease.profiles.is_empty() {
            vec![DEFAULT_PROFILE.to_owned()]
        } else {
            lease.profiles.clone()
        };
        let profiles = names
            .iter()
            .map(|name| Profile::parse(name))
            .collect::<anyhow::Result<Vec<_>>>()
            .map_err(|error| invalid(&format!("{error:#}")))?;
        let ttl = lease.ttl.clone().unwrap_or_else(|| DEFAULT_TTL.to_owned());
        if ttl.is_empty() || !ttl.bytes().all(|b| b.is_ascii_alphanumeric()) {
            return Err(invalid("worker lifetime"));
        }
        let owner = lease.owner.clone();
        if owner.is_empty()
            || owner.starts_with('-')
            || !owner
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'@' | b'.' | b'_' | b'+' | b'-'))
        {
            return Err(invalid("owner"));
        }
        if !lease
            .ssh_inputs
            .iter()
            .all(|name| setup::valid_input_name(name))
        {
            return Err(invalid("flake input name"));
        }
        let key = match (&lease.key, home) {
            (Some(key), _) => key.clone(),
            (None, Some(home)) => home.join(".ssh/id_ed25519"),
            (None, None) => return Err(invalid("no key configured and no HOME")),
        };
        if !key.is_absolute() {
            return Err(invalid("the key path must be absolute"));
        }
        Ok(Self {
            controller,
            profiles,
            ttl,
            queue_timeout: Duration::from_secs(
                lease
                    .queue_timeout
                    .unwrap_or(DEFAULT_QUEUE_TIMEOUT)
                    .clamp(30, 3600),
            ),
            owner,
            ssh_inputs: lease.ssh_inputs.clone(),
            key,
        })
    }

    fn public_key_file(&self) -> PathBuf {
        let mut name: OsString = self.key.clone().into_os_string();
        name.push(".pub");
        PathBuf::from(name)
    }
}

/// `nix copy` of `generation` from the signed cache, which requires the
/// cache's signature (the same check as the published-system upgrade).
fn cache_copy_command(cache: &UpgradeCache, generation: &str) -> Result<Vec<String>> {
    let keys = cache.public_keys.join(" ");
    let mut command = Vec::new();
    if let Some(file) = cache.credentials_file.as_deref() {
        command.extend([
            "env".to_owned(),
            format!("AWS_SHARED_CREDENTIALS_FILE={}", fsutil::path_str(file)?),
        ]);
    }
    command.extend(
        [
            "nix",
            "--refresh",
            "--option",
            "trusted-public-keys",
            &keys,
            "copy",
            "--from",
            &cache.store,
            "--option",
            "require-sigs",
            "true",
            generation,
        ]
        .map(str::to_owned),
    );
    Ok(command)
}

/// `nix copy` of `generation` from the lease's own store over its ssh_config.
fn lease_copy_command(ssh_config: &Path, store_uri: &str, generation: &str) -> Result<Vec<String>> {
    Ok([
        "env".to_owned(),
        format!("NIX_SSHOPTS=-F {}", fsutil::path_str(ssh_config)?),
        "nix".to_owned(),
        "copy".to_owned(),
        "--no-check-sigs".to_owned(),
        "--from".to_owned(),
        store_uri.to_owned(),
        generation.to_owned(),
    ]
    .to_vec())
}

/// Bring `generation` into the local store: from the signed cache when it
/// has it, else from the worker.
fn fetch(
    builder: &mut dyn Builder,
    cache: Option<&UpgradeCache>,
    ssh_config: &Path,
    store_uri: &str,
    generation: &str,
) -> Result<()> {
    if let Some(cache) = cache {
        eprintln!("Checking the signed cache…");
        if builder
            .output(&cache_copy_command(cache, generation)?)
            .is_ok()
        {
            return Ok(());
        }
        eprintln!("Could not use the signed cache. Copying from the worker.");
    }
    eprintln!("Copying the system from the worker…");
    builder.output(&lease_copy_command(ssh_config, store_uri, generation)?)?;
    Ok(())
}

/// Lease a worker, build the latest system on it, copy it here, release the
/// worker. Any failure is an error; the lease is released on every path.
pub fn build(job: &Job<'_>, builder: &mut dyn Builder) -> Result<PathBuf> {
    let home = std::env::var_os("HOME").map(PathBuf::from);
    let plan = Plan::resolve(job.lease, home.as_deref())?;
    run(&plan, job, builder).map_err(|error| Error::invalid(format!("{error:#}")))
}

fn run(plan: &Plan, job: &Job<'_>, builder: &mut dyn Builder) -> anyhow::Result<PathBuf> {
    let public_key = String::from_utf8(
        fsutil::read_bounded(&plan.public_key_file(), 8192)
            .context("cannot read the phone's public key")?,
    )?
    .trim()
    .to_owned();
    anyhow::ensure!(
        !public_key.is_empty() && !public_key.contains('\n'),
        "invalid public key"
    );
    let cache_options = match job.cache {
        Some(cache) => match cache.credentials_file.as_deref() {
            Some(file) => {
                let credentials = String::from_utf8(
                    fsutil::read_bounded(file, 64 * 1024)
                        .context("cannot read the cache credentials")?,
                )?;
                Some(CacheOptions {
                    store: cache.store.clone(),
                    public_keys: cache.public_keys.join(" "),
                    credentials: format!("{}\n", credentials.trim_end()),
                })
            }
            None => None,
        },
        None => None,
    };

    let scratch = Scratch::new("nixpp-upgrade")?;
    let known_hosts = scratch.path().join("known_hosts");
    let side = Side {
        key: plan.key.clone(),
        known_hosts: known_hosts.clone(),
    };
    fs::write(
        &known_hosts,
        format!("{}\n", plan.controller.known_hosts_line()),
    )?;
    let request = Request {
        public_key,
        owner: plan.owner.clone(),
        topic: format!("{TOPIC_PREFIX} {}", job.host),
        ttl: plan.ttl.clone(),
    };
    let mut lease = lease::lease(
        &plan.controller,
        &side,
        &request,
        &plan.profiles,
        plan.queue_timeout,
    )?;
    // The worker is pinned under its name, like the controller.
    let mut pinned = fs::read_to_string(&known_hosts)?;
    pinned.push_str(&format!("{}\n", lease.worker().known_hosts_line()));
    fs::write(&known_hosts, pinned)?;
    let ssh_config = scratch.path().join("ssh_config");
    fs::write(
        &ssh_config,
        lease.worker().ssh_config(&side, &plan.controller),
    )?;

    let agent = if plan.ssh_inputs.is_empty() {
        None
    } else {
        Some(Agent::start(scratch.path(), &plan.key)?)
    };
    let spec = Build {
        flake: job.flake.to_owned(),
        target_attribute: format!("termuxppConfigurations.{}.system", job.host),
        ssh_inputs: plan.ssh_inputs.clone(),
        cache: cache_options,
        github_known_hosts: setup::GITHUB_KNOWN_HOSTS.to_owned(),
        refresh: job.refresh,
    };
    let generation = lease::build(&lease, agent.as_ref(), &spec)?;
    fetch(
        builder,
        job.cache,
        &ssh_config,
        &lease.worker().store_uri(),
        &generation,
    )?;
    lease.release();
    drop(agent);
    store::guest_store_path(&generation)?;
    Ok(PathBuf::from(generation))
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: &str = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleExampleExampleExampleExample";

    fn settings() -> UpgradeLease {
        serde_json::from_str(&format!(
            r#"{{"controller":"phone-rworker@controller.example","controller_host_key":"{KEY}","owner":"user@phone"}}"#
        ))
        .unwrap()
    }

    fn cache() -> UpgradeCache {
        UpgradeCache {
            store: "s3://bucket?region=r&profile=p".to_owned(),
            pin_reader: "/pin".into(),
            public_keys: vec!["a:1".to_owned(), "b:2".to_owned()],
            credentials_file: Some("/home/u/aws".into()),
        }
    }

    const GENERATION: &str = "/nix/store/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-termuxpp-system-phone";

    struct Scripted {
        commands: Vec<Vec<String>>,
        fail_cache: bool,
    }

    impl Builder for Scripted {
        fn output(&mut self, command: &[String]) -> Result<String> {
            self.commands.push(command.to_vec());
            if self.fail_cache && command.iter().any(|word| word == "require-sigs") {
                return Err(Error::invalid("not in the cache"));
            }
            Ok(String::new())
        }
    }

    #[test]
    fn defaults_lease_a_native_arm_runner_with_the_phones_key() {
        let plan = Plan::resolve(&settings(), Some(Path::new("/home/u"))).unwrap();
        assert_eq!(plan.profiles, [Profile::Hosted("linux-arm64")]);
        assert_eq!(plan.ttl, "1h");
        assert_eq!(plan.queue_timeout, Duration::from_secs(420));
        assert_eq!(plan.owner, "user@phone");
        assert_eq!(plan.key, Path::new("/home/u/.ssh/id_ed25519"));
        assert_eq!(
            plan.public_key_file(),
            Path::new("/home/u/.ssh/id_ed25519.pub")
        );
        assert_eq!(
            plan.controller.known_hosts_line(),
            format!("controller.example {KEY}")
        );
        assert!(plan.ssh_inputs.is_empty());
    }

    #[test]
    fn explicit_settings_override_the_defaults() {
        let mut lease = settings();
        lease.key = Some("/keys/id".into());
        lease.profiles = vec!["gh-aarch64".to_owned(), "default".to_owned()];
        lease.ttl = Some("90m".to_owned());
        lease.queue_timeout = Some(5);
        lease.ssh_inputs = vec!["private".to_owned()];
        let plan = Plan::resolve(&lease, None).unwrap();
        assert_eq!(plan.key, Path::new("/keys/id"));
        assert_eq!(plan.profiles.len(), 2);
        assert_eq!(plan.ttl, "90m");
        // Clamped: a runner needs time to start.
        assert_eq!(plan.queue_timeout, Duration::from_secs(30));
        assert_eq!(plan.ssh_inputs, ["private"]);
    }

    #[test]
    fn invalid_settings_are_rejected_before_anything_runs() {
        let bad: [fn(&mut UpgradeLease); 8] = [
            |l| l.controller = "-oProxyCommand=x".to_owned(),
            |l| l.controller_host_key = "not a key;".to_owned(),
            |l| l.owner = "user phone".to_owned(),
            |l| l.owner = String::new(),
            |l| l.ttl = Some("1h;reboot".to_owned()),
            |l| l.ssh_inputs = vec!["a b".to_owned()],
            |l| l.profiles = vec!["-x".to_owned()],
            |l| l.key = Some("relative/key".into()),
        ];
        for change in bad {
            let mut lease = settings();
            change(&mut lease);
            assert!(Plan::resolve(&lease, Some(Path::new("/home/u"))).is_err());
        }
        assert!(Plan::resolve(&settings(), None).is_err());
    }

    #[test]
    fn the_cache_is_tried_first_and_must_be_signed() {
        let mut builder = Scripted {
            commands: Vec::new(),
            fail_cache: false,
        };
        fetch(
            &mut builder,
            Some(&cache()),
            Path::new("/scratch/ssh_config"),
            "ssh-ng://u@w",
            GENERATION,
        )
        .unwrap();
        assert_eq!(
            builder.commands,
            [[
                "env",
                "AWS_SHARED_CREDENTIALS_FILE=/home/u/aws",
                "nix",
                "--refresh",
                "--option",
                "trusted-public-keys",
                "a:1 b:2",
                "copy",
                "--from",
                "s3://bucket?region=r&profile=p",
                "--option",
                "require-sigs",
                "true",
                GENERATION,
            ]]
        );
    }

    #[test]
    fn a_missing_cache_entry_falls_back_to_the_worker_store() {
        let mut builder = Scripted {
            commands: Vec::new(),
            fail_cache: true,
        };
        fetch(
            &mut builder,
            Some(&cache()),
            Path::new("/scratch/ssh_config"),
            "ssh-ng://rworker-build@gha-1?remote-program=/d",
            GENERATION,
        )
        .unwrap();
        assert_eq!(builder.commands.len(), 2);
        assert_eq!(
            builder.commands[1],
            [
                "env",
                "NIX_SSHOPTS=-F /scratch/ssh_config",
                "nix",
                "copy",
                "--no-check-sigs",
                "--from",
                "ssh-ng://rworker-build@gha-1?remote-program=/d",
                GENERATION,
            ]
        );
        let mut builder = Scripted {
            commands: Vec::new(),
            fail_cache: false,
        };
        fetch(
            &mut builder,
            None,
            Path::new("/s/c"),
            "ssh-ng://u@w",
            GENERATION,
        )
        .unwrap();
        assert_eq!(builder.commands.len(), 1);
        assert_eq!(builder.commands[0][2], "nix");
    }

    #[test]
    fn a_failed_lease_is_an_error_and_never_runs_the_old_wrapper() {
        let mut lease = settings();
        lease.key = Some("/nonexistent/id_ed25519".into());
        let mut builder = Scripted {
            commands: Vec::new(),
            fail_cache: false,
        };
        let error = build(
            &Job {
                flake: "github:example/config/main",
                host: "phone",
                lease: &lease,
                cache: None,
                refresh: true,
            },
            &mut builder,
        )
        .unwrap_err();
        assert!(error.to_string().contains("public key"), "{error}");
        assert!(builder.commands.is_empty());
    }
}
