# nixpp

Rust system management for a declarative Linux guest hosted by TermuxPP.
One codebase builds two executables: Linux/glibc for the everyday guest CLI and
Android/Bionic for bootstrap, the native controller and independent recovery.
They are not interchangeable ABI builds.

Nix builds the closures, Home Manager performs its upstream activation, and
droid-nix launches the guest. nixpp coordinates those tools; it does not emulate
NixOS, systemd or a NixOS system profile. The native substrate remains available
when the selected guest generation cannot start.

## Guest commands

```sh
nixpp list-generations
nixpp build --flake path:/config --host example
nixpp rebuild --flake path:/config --host example
nixpp prepare /nix/store/STOREHASH-termuxpp-system-example
nixpp check STOREHASH-termuxpp-system-example
nixpp switch STOREHASH-termuxpp-system-example
nixpp reapply
nixpp hm --help
nixpp rollback
nixpp recover
```

Builds disable local compilation. Add `--rworker` to opt into a disposable
worker: with a `lease` section in `/etc/nixpp/upgrade.json` (see
[Upgrading on a leased worker](#upgrading-on-a-leased-worker)) the worker is
leased through the rworker controller, else the `rworker-nix` wrapper of the
system configuration is used. Prepared candidates are not selected or activated until `switch`.
Activation journals the previous system/Home Manager pair and publishes selection
only after the activation and checks succeed. A retained failure journal blocks
another switch until explicit recovery.

`nixpp reapply` reruns activation for the active closure. It uses the native
controller's checks and recovery journal and preserves generation history.
`nixpp hm` forwards arguments directly to the installed `home-manager`.

`switch`, `reapply`, `rebuild`, `upgrade` and `fetch latest` accept `--backup[=EXT]` (default
`EXT` is `nixpp.bak`): the controller runs the Home Manager activation with
`HOME_MANAGER_BACKUP_EXT=EXT`, so an existing unmanaged file in its way is moved
to `<file>.EXT` instead of failing with "would be clobbered". An existing
`<file>.EXT` is never overwritten. `homeManagerBackupExtension` in
`/etc/nixpp/upgrade.json` makes it the default; `--no-backup` overrides that.
After a failed activation run `nixpp recover` before retrying.

The guest uses `NIXPP_SOCKET` (default `/tmp/nixpp.sock`). `--json` produces
machine-readable results without terminal escapes. Human output uses restrained
colors on terminals; redirected output and `NO_COLOR` are plain. `--color` selects
`auto`, `always` or `never`; `--quiet` suppresses progress and success messages.

## Native setup and recovery

Stage the Android build as `nixpp` beside the launcher, bootstrap seed and runtime
manifest. The executable contains the managed shell resources; it does not embed
the Linux package closure, the seed or the launcher binary.

```sh
./nixpp init --runtime /path/to/runtime --bootstrap /path/to/bootstrap-aarch64.zip \
  --manifest /path/to/manifest.json --certificate /path/to/cert.pem --user guest
./nixpp init --runtime /path/to/runtime --resume
./nixpp --runtime /path/to/runtime serve --socket /private/tmp/nixpp.sock
./nixpp --runtime /path/to/runtime controller list-generations
./nixpp --runtime /path/to/runtime controller recover
./nixpp --runtime /path/to/runtime controller rollback
```

A provisioning-first TermuxPP bootstrap installs `bin/nixpp` plus
`share/nixpp/{launcher,bootstrap-aarch64.zip,manifest.json}`. With those
trusted inputs bundled, run `nixpp init --user guest` from the native shell.
Init defaults to `$HOME/.local/share/termuxpp-nix`, finds assets relative to
the executable's native prefix, and uses `etc/tls/cert.pem`. `--bootstrap-dir`
overrides only the bundled-assets directory. Explicit input paths above remain
supported. Init downloads nothing besides the pinned packages from
cache.nixos.org. An interrupted package import uses `nixpp init --resume`, not
fresh extraction.

We build the launcher and seed ourselves
([`bootstrap-seed.nix`](../bootstrap-seed.nix), package
`nixpp-runtime-termux-seed`) from the pinned droid-nix sources (MIT): a static
musl `launcher` and a registered chroot store with static `nix`,
`busybox`, and the glibc `nixpp` closure. The same package carries
`manifest.json` ([`session-manifest.nix`](../session-manifest.nix)), which puts
guest `nixpp` on the first session's PATH. Init uses the bundled closure and
fetches missing session packages from cache.nixos.org. The seed is reproducible.

From a native session, `nixpp enter` opens the Nix guest. Use `--` to run a
command directly, without shell evaluation:

```sh
nixpp enter -- printf '%s\n' 'two words'
nixpp enter --outer
nixpp enter --outer -- ls -al
```

Inside a regular interactive guest, `--outer` runs in the native TermuxPP
environment through the terminal session's native parent. Exit an outer shell
to return to the guest. The handoff accepts only requests from that terminal's
foreground job, with all three streams attached to the terminal. It is separate
from the system-management socket. A guest entered through a recovery script
without a native parent can use the app's failsafe session instead.

### `nixpp setup`

`nixpp setup` enrolls a fresh phone into its guest system. Run it in a native
TermuxPP session (the custom bootstrap installs it as `nixpp-setup`, which
just runs `nixpp setup "$@"`) or directly in a guest session such as the
first-start seed session (see below):

```sh
nixpp setup --host PHONE [--user USER] [--flake FLAKE] [--published]
```

#### From a guest session

The guest build of nixpp cannot run setup itself (it needs the Android prefix,
the bundled rbw and the app's terminal). `nixpp setup ...` and the guest's
`nixpp-setup` (a one-line wrapper installed with the guest package) therefore
hand the whole invocation to the native side through the same terminal
handoff as `nixpp enter --outer` and run `nixpp-setup ...` there: the terminal
session's native parent, only from the terminal's foreground job, with all
three streams on the terminal (prompts, progress and the exit status come back
unchanged). Arguments after `setup` are forwarded verbatim; `--json`,
`--quiet` and `--color` given before `setup` are carried over, `--runtime` and
`--socket` (guest paths) are not. Environment variables of the guest session
(`NIXPP_SETUP_*`, `RBW_EMAIL`) do not reach the native side: use the matching
options.

`--secrets-file` is the one path argument. The guest sees the app's files
differently from the native side: the guest's `/home` is `<runtime>/root/home`
natively, the whole native file system is mounted at `/android`, and the
app's native prefix (`files/usr`) also at `/outer`. The path
is translated before the handoff:

| Given in the guest | Read natively as |
| --- | --- |
| `/android/data/data/PKG/files/home/FILE` | `/data/data/PKG/files/home/FILE` |
| `/data/data/PKG/files/home/FILE` (the path the adb helper prints; not a guest path) | unchanged |
| `/home/USER/FILE`, or relative to a working directory below `/home` | `<runtime>/root/home/USER/FILE` |
| anything else (`/tmp`, `/etc`, `..` components) | refused with an explanation |

The adb helper writes `/data/data/PKG/files/home/.nixpp-setup-secrets`
(the app's native home, not the guest home), so the same path works in both
kinds of session.

Without a native parent (a recovery shell, for example) the handoff fails with
a message naming the fallback: open the app's failsafe session and run
`"$PREFIX/bin/nixpp" native-shell`, then `nixpp-setup` there.

`--host` is required; there is no table of devices. What it does:

1. **Bitwarden.** The bundled `rbw` (built from the pschmitt fork, see
   [`rbw-android.nix`](../rbw-android.nix)) is reused when it is already
   unlocked (then it only syncs). Otherwise it logs in with, in order:
   `--secrets-file FILE` (a regular file, owned by you, mode 0600, with
   `RBW_MASTER_PASSWORD=` and `RBW_TOTP=` lines, deleted as soon as it is read;
   refused otherwise), or a prompt without echo on the terminal (password,
   then TOTP, with one retry on a fresh code). The password is fed to
   `rbw unlock --stdin` because pinentry does not work in a Termux session.
   The account email is set with `rbw config set email` only when rbw has none:
   pass `--email` (or `RBW_EMAIL`).
2. **Credentials.** From Bitwarden: the phone's SSH key (`{user}@{host}`,
   fields `private_key` and `public_key`), the optional `age_key` of
   `nixpp-enrollment-{host}`, the binary cache's access key and secret
   (`nixpp Niks3 cache`, fields `username` and `password`, written as the AWS
   profile `niks3`), and the dotfiles deploy key (`yadm-init-deploy-key`,
   field `private_key`). The item names are options (`--key-item`,
   `--enrollment-item`, `--cache-item`, `--deploy-key-item`).
3. **Build.** By default the latest `--flake` target is built on a freshly
   leased rworker; `--published` installs the system CI pinned in the cache for
   this host instead (read with SigV4, then verified and copied exactly like
   `nixpp upgrade`).
4. **Activation.** The same resumable activation as `nixpp bootstrap`: the
   system is copied (from the signed cache when it is there, else from the
   worker), the initial Home Manager generation is activated and the native
   controller switches. Retry the command to continue an interruption.
5. **Dotfiles.** With a dotfiles repository configured, the deploy key is
   used inside the guest to `yadm clone` it (skipped when a checkout exists).

#### Leasing a worker

The phone speaks the rworker controller's plain SSH protocol with its own key
(`rworker up --ssh-key ... --owner {user}@{host}`; `rworker down` afterwards,
also on failure). The controller and its SSH host key are not part of the
sources: pass `--rworker-controller [USER@]HOST[:PORT]` and `--rworker-host-key
'ssh-ed25519 AAAA...'` (or `NIXPP_SETUP_CONTROLLER`,
`NIXPP_SETUP_CONTROLLER_HOST_KEY`), or bake them into the bootstrap zip with
`build-aarch64-bootstrap.py --nixpp-setup-config FILE` (see below). Both the
controller and the worker host key it returns are pinned; nothing is trusted on
first use.

Profiles are tried in order (`--worker-profile`, repeatable). The default is
`gh-aarch64` (a native ARM GitHub runner: the system is aarch64, so builds are
native, reached through the controller's loopback relay, with `--queue-timeout`
before the next profile is tried), then the controller's default OpenStack
profile (x86_64: it relies on cache hits, `cache_store`). `--worker-ttl`
bounds the lease.

#### Upgrading on a leased worker

`nixpp upgrade --rworker` (and `rebuild`/`build --rworker`) reuse this lease
code in the guest when `/etc/nixpp/upgrade.json` has a `lease` section. The
guest leases a worker with the phone's key, builds `termuxppConfigurations.{host}.system`
of the configured flake there, tries the signed cache for the result and else
copies it from the worker, releases the lease and continues with the usual
prepare and switch. The build runs as an rworker job named `nixpp-…` with the
topic `nixpp upgrade {host}` (`nixpp setup {host}` during enrollment), and
`nix build -L` writes its logs to the job as well as to the phone: follow it
from a workstation with `rworker logs -f --topic nixpp`.
`upgrade` resolves the flake with `--refresh`: it installs
the latest revision or fails. Nothing falls back to an older, cached or
published system, and a failed lease or build is a non-zero exit. The lease
evaluates on the worker, so `--remote-eval` adds nothing. Without a `lease`
section the wrapper route is unchanged.

```json
{
  "flake": "github:example/config/main",
  "host": "phone",
  "cache": {"store": "s3://...", "pin_reader": "...", "public_keys": ["..."],
            "credentials_file": "/home/user/.config/..."},
  "lease": {
    "controller": "[USER@]HOST[:PORT]",
    "controller_host_key": "ssh-ed25519 AAAA...",
    "owner": "{user}@{host}",
    "key": "/home/user/.ssh/id_ed25519",
    "profiles": ["gh-aarch64"],
    "ttl": "1h",
    "queue_timeout": 420,
    "ssh_inputs": ["private-input"]
  }
}
```

`controller`, `controller_host_key` and `owner` are required; `key` defaults
to `~/.ssh/id_ed25519` (the public half is the same path with `.pub`),
`profiles` to `gh-aarch64`, `ttl` to `1h`, `queue_timeout` to 420 seconds.
The cache settings are the top-level `cache` (its `credentials_file` is read
and uploaded to the runner for the build). The request is the one `setup`
makes (its topic is `nixpp setup {host}`), which the controller's account
accepts.

#### Private flake inputs without tokens

No access token is put on the worker. The phone's ssh-agent (which holds only
the phone key) is forwarded to the worker. For every `--ssh-input NAME` the
worker first resolves the flake to a revision, reads its `flake.lock`, and
builds with `--override-input NAME git+ssh://git@github.com/OWNER/REPO?rev=REV`
at the revision the lock names, so the source is the one the lock records.
GitHub's SSH host keys are pinned on the worker. Name only inputs that are
private on GitHub; GitHub must accept the phone's key for them.

GitHub runners (`gh-aarch64`) are reached through the controller's relay and
get the same agent forwarding as OpenStack workers: the runner's sshd allows
agent forwarding, so the phone's private key never leaves the phone, also for
flakes with private inputs. The forwarded agent exists only while the build's
SSH session runs.

#### Setup config

Defaults that must not live in the public sources are read from
`$PREFIX/etc/nixpp/setup.conf` (`--config FILE`, `NIXPP_SETUP_CONFIG`) as
`KEY=VALUE` lines; options and environment variables win. Keys: `user`,
`email`, `controller`, `controller_host_key`, `worker_profiles`, `ssh_inputs`,
`cache_store`, `cache_public_keys`, `cache_pin_url` (with `{host}`),
`cache_region`, `dotfiles_repo` (an `ssh://` URL through ssh.github.com).
The bootstrap builder installs the file with `--nixpp-setup-config`.

### Low-level `nixpp bootstrap`

`nixpp bootstrap` is the primitive underneath: it enrolls from a private
credentials directory and a build host you operate yourself.

```sh
nixpp bootstrap --host PHONE --user USER --flake FLAKE --credentials PRIVATE_DIR
```

The private directory contains `id_ed25519`, `id_ed25519.pub`, `ssh_config`,
`known_hosts` and `build_host`, with an optional `age_key`. The build host is
an SSH alias with access to the flake inputs. Pin its key in `known_hosts`.
The initial generation is copied directly from this trusted worker.
The age identity, when supplied, is installed as `~/.config/sops/age/keys.txt`
for profiles that declare SOPS secrets. Credential files stay in the guest's
private home.

Enrollment sets up the requested user beside the current guest. It builds
the selected system with Nix and phone compilation disabled, activates the
initial Home Manager generation, then switches through the native controller.
The previous guest is retained at the runtime path with a `.before-enrollment`
suffix. Open a new TermuxPP session after enrollment to enter the managed
guest. Retry the same command to continue an interrupted enrollment.

The launcher carries
[`launcher-foreign-trap.patch`](../launcher-foreign-trap.patch). Android 15's
app seccomp filter traps `openat2`. Upstream's SIGSYS handler translated and
reissued its own reissued syscall again until the stack overflowed, so the
first `nix copy` of a fresh runtime died with SIGSEGV or SIGSYS inside the app.
It did not fail under `adb run-as`, which has no app filter. A trap raised by the
launcher's exempt stub now fails with `ENOSYS`, and Nix falls back to `openat`.
Test init inside the TermuxPP app, not only through `run-as`. The
`nixpp-runtime-termux` build compiles in the seed's SHA-256, and init refuses
any other seed. A nixpp built without the Nix package has no pinned seed and
refuses to prepare a runtime.

This initializes the base runtime only: it does not activate a host system,
fabricate a Home Manager baseline, replace the native shell or enable guest
login. System activation still uses the journaled controller and its gates.
No vault, SSH keys, cache credentials or private configuration are embedded.

Run native commands outside the guest. The socket requires an owned directory
that is not writable by other users, and accepts only clients with the same UID.
Direct controller operations use the same mutation locks as the service and do
not need a working socket. Do not run the former Python service concurrently.

The optional Android API bridge is separate from this system-management port; arbitrary native
commands and a general Termux package bridge are not exposed by the controller.

## Validation

All compilation and test suites run on a remote worker:

```sh
cargo fmt --check
cargo clippy --all-targets --all-features
cargo test --all-features
python3 -I ../tests/test_nixpp_cli.py /path/to/compiled/nixpp
```

The Nix package outputs are `nixpp`, `nixpp-runtime-termux` and
`nixpp-runtime-termux-seed`. The system assembly
test also exercises the packaged CLI. See [the parity contract](PARITY.md) for the
preserved state, protocol and recovery guarantees and cut-over criteria. A
successful host build alone does not qualify Android activation or recovery.

## Shell completion

The Linux package installs completion scripts for Bash, Zsh and Fish. The
managed TermuxPP Zsh profile picks up the script automatically on a new shell.

Run `nixpp completions bash`, `nixpp completions zsh` or
`nixpp completions fish` to write the script to stdout. This also works from
the native shell and does not require a running guest. Put the Zsh script in
a directory on `fpath`, with the name `_nixpp`, before running `compinit`.
