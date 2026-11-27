---
page_ref: /docs/apps/termux/aarch64-bootstrap.html
---

# Custom AArch64 Bootstrap

TermuxPP embeds a bootstrap archive per ABI. `app/build.gradle` downloads the upstream archives
and checks them against hardcoded SHA-256 hashes. For `aarch64` there are two ways to replace it:
pass your own archive and its SHA-256 to Gradle (see [Gradle](#gradle)).

- **The slim outer environment** (the default for TermuxPP's own phones): only TermuxPP's own
  files, no upstream Termux packages, no dpkg and no APT. It is the Nix derivation
  `termuxpp-outer-bootstrap`. Everything else lives in the Nix guest.
- **An upstream-based bootstrap**: the upstream Termux bootstrap plus extra packages, built by
  `scripts/build-aarch64-bootstrap.py`. This keeps dpkg and APT. See
  [Upstream-based bootstrap](#upstream-based-bootstrap).

## The slim outer environment

`termuxpp-outer-bootstrap` (`pkgs/nixpp/outer-bootstrap.nix`) writes `bootstrap-aarch64.zip` and
its `.sha256` in the format `TermuxInstaller` reads. It holds:

| Path | Content |
| --- | --- |
| `bin/nixpp` | the Android/Bionic nixpp runtime (`nixpp-runtime-termux`) |
| `bin/nixpp-setup` | `#!/system/bin/sh` wrapper for `nixpp setup` |
| `bin/rbw`, `bin/rbw-agent` | `rbw-termux`, for the Bitwarden login of `nixpp setup` |
| `share/nixpp/{launcher,manifest.json,bootstrap-aarch64.zip}` | the seed: the guest's launcher and first Nix store |
| `etc/tls/cert.pem` | CA bundle |
| `etc/nixpp/setup.conf` | optional, mode 0600: private defaults of `nixpp setup` |
| `SYMLINKS.txt` | `bin/sh` → `/system/bin/sh` |

There is no bash in the prefix: native scripts run under Android's `/system/bin/sh`, with
`/system/bin` on `PATH` for Android tools such as `pm`. Without `bin/apt` or dpkg,
`TermuxInstaller` skips its APT helper hook. The archive is reproducible (sorted entries and one
fixed timestamp).

Nothing in the prefix needs a reinstall later. Every guest generation ships the current native
pieces and nixpp's daemon installs them after a switch:

- `native/nixpp` and `native/launcher` (the launcher only after a smoke test, run on its own and
  under the current launcher);
- `bootstrap/{busybox,nix}`;
- the files listed in `native/prefix.json` (nixos-config `system.nativePrefix`: rbw, the seed,
  `nixpp-setup`, `setup.conf`). A prefix file is replaced only if it is absent, identical, or
  unchanged since nixpp last installed it. The first copy from the bootstrap is kept as
  `<file>.nixpp.bak`, and files changed by hand are left alone. The record is
  `$PREFIX/var/lib/nixpp/prefix-install.json`.

There is no nightly `apt upgrade`; a generation switch is the only update path.

Build it on an x86_64 Linux worker (it cross-builds the Android parts), with the private
`setup.conf` from nixos-config's `termuxpp-setup-conf` for an enrollment build:

```sh
zip=$(nix build --accept-flake-config --no-link --print-out-paths --impure --expr '
  let tp = builtins.getFlake "github:pschmitt/termuxpp";
      nc = builtins.getFlake "github:pschmitt/nixos-config";
  in ((tp.lib.nixpp.packagesFor tp.inputs.nixpkgs.legacyPackages.x86_64-linux).termuxpp-outer-bootstrap.override {
    setupConfig = nc.packages.x86_64-linux.termuxpp-setup-conf; })')

TERMUX_BOOTSTRAP_AARCH64_ZIP=$zip/bootstrap-aarch64.zip \
TERMUX_BOOTSTRAP_AARCH64_SHA256=$(cat $zip/bootstrap-aarch64.zip.sha256) \
nix develop --accept-flake-config -c ./gradlew --no-daemon :app:assembleDebug
```

Without `.override` the archive has no `setup.conf`; `nixpp setup` then needs its settings as
options or environment variables. An archive or APK with `setup.conf` embeds private
configuration: keep it mode 0600 and local, never in a public release. The archive is excluded
from the flake's aarch64 package set because it is built on x86_64.

On first start the app extracts the prefix and opens the seed guest (`(nix) nix:~$`). Enroll from
there with `nixpp setup --host HOST` (or `nixpp-setup`); see
[`nixpp setup`](../../pkgs/nixpp/source/README.md#nixpp-setup).

## Upstream-based bootstrap

The archive can also be built from the upstream bootstrap plus a few extra packages (`openssh`,
`nixpp`, ...) with `scripts/build-aarch64-bootstrap.py`, and handed to Gradle together with its
SHA-256. This keeps dpkg and APT in the native prefix.

Nothing here compiles packages. Packages come from prebuilt `aarch64` `.deb` files (for example
the Termux repository, which uses the same `com.termux` prefix as the bootstrap) or from a prepared
package prefix. Python 3 and its standard library are all that is needed.

### What the archive contains

The result has exactly the format `TermuxInstaller` reads: paths relative to `$PREFIX`, a
`SYMLINKS.txt` with `target←path` lines (target kept verbatim, relative or absolute), and the dpkg
database. For every added, upgraded or replaced package `var/lib/dpkg/status`,
`info/<pkg>.list`, `.md5sums`, `.conffiles` and maintainer scripts are written in the form the
upstream bootstrap uses, so `dpkg -L`, `dpkg -s` and later `apt upgrade` see them as installed.
The text rewriting of `/data/data/com.termux` that the installer does at first run applies to
the added files as well.

The upstream bootstrap already ships `curl`, `ca-certificates`, `gzip` and `xz-utils`. The builder
fails unless these, `openssh` and (with `--nixpp`) `nixpp` are installed in the result.
Override that with `--require-package` (repeatable).

### Quick start

```sh
# 1. Download and verify the pinned upstream bootstrap.
curl -fL 'https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip' -o base.zip
printf '%s  base.zip\n' ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3 | sha256sum -c -

# 2. Resolve extra packages, preserving packages already in the base.
scripts/build-aarch64-bootstrap.py fetch \
    --base-zip base.zip --package openssh --out-dir debs

# 3. Build. --nixpp is an AArch64 Android/Bionic executable using only Android system libraries:
#    nix build .#nixpp-runtime-termux (bin/nixpp) and .#nixpp-runtime-termux-seed
#    (share/nixpp), on an x86_64 or aarch64 Linux worker. --rbw is a directory with
#    bin/rbw and bin/rbw-agent: nix build .#rbw-termux (the pschmitt fork, built from
#    source with the same Android toolchain). It replaces the upstream Termux rbw deb.
scripts/build-aarch64-bootstrap.py build \
    --deb-dir debs --lock debs/debs.lock.json \
    --nixpp /path/to/nixpp --nixpp-version 0.5.0 \
    --nixpp-assets /path/to/seed/share/nixpp \
    --rbw /path/to/rbw-termux --rbw-version 2.48.2 \
    --nixpp-setup-config /path/to/private/setup.conf \
    -o out/bootstrap-aarch64.zip

# 4. Build the app with it on a remote worker.
TERMUX_BOOTSTRAP_AARCH64_ZIP=$PWD/out/bootstrap-aarch64.zip \
TERMUX_BOOTSTRAP_AARCH64_SHA256=$(cut -d' ' -f1 out/bootstrap-aarch64.zip.sha256) \
./gradlew assembleDebug
```

The `--nixpp` bootstrap includes `nixpp-setup`, a launcher for `nixpp setup`. Run it from a
native TermuxPP session as `nixpp-setup --host HOST --user USER` (with `--secrets-file` or at the
prompt): it reads the enrollment credentials from Bitwarden with the bundled `rbw`, builds the
system on a leased rworker (or installs the CI-published one with `--published`), activates it and
clones the dotfiles. See [`nixpp setup`](../../pkgs/nixpp/source/README.md#nixpp-setup). From an
app session that already opened the Nix guest (the first-start seed session), run
`nixpp setup ...` or `nixpp-setup ...` directly: the guest hands the invocation to the native
side, and a `--secrets-file` guest path is translated (the adb helper's
`/data/data/PKG/files/home/...` path works unchanged).

`--nixpp-setup-config` takes a `KEY=VALUE` file that the builder installs as
`etc/nixpp/setup.conf` (mode 0600) in the `nixpp` package: the rworker controller and its host key,
the binary cache, the private flake inputs and similar defaults of `nixpp setup`. Keep that file
out of public repositories and give it only to private builds. Generate it with Nix rather than
keeping it by hand, from the private flake that also defines the phones (the keys and their format
are in [`nixpp setup`](../../pkgs/nixpp/source/README.md#nixpp-setup)); for example a flake output
`termuxpp-setup-conf` passed as `--nixpp-setup-config "$(nix build --no-link --print-out-paths .#termuxpp-setup-conf)"`.

If `--base-zip` is omitted, `build` downloads the pinned upstream archive (the same URL and hash
as `app/build.gradle`) into `~/.cache/termuxpp-bootstrap`. A custom `--base-zip` needs
`--base-sha256`.

Later builds can be reproduced from the lock file: `fetch --lock-in debs.lock.json --base-zip ...
--out-dir debs` downloads exactly those packages and verifies every hash, and `build --lock`
refuses debs that do not match. The output is byte-for-byte reproducible for identical inputs
(sorted entries, fixed timestamps; `--source-date-epoch` or `SOURCE_DATE_EPOCH` sets the date of new
entries).

### Inputs of `build`

| Option | Meaning |
| --- | --- |
| `--deb FILE`, `--deb-dir DIR` | `aarch64`/`all` `.deb` files. Dependencies are installed first. |
| `--lock FILE` | With `--deb-dir`, use only the locked debs and verify their SHA-256. |
| `--prefix DIR` | Prepared package prefix laid out like `$PREFIX` (`bin/`, `lib/`, `etc/`, ...). |
| `--rbw DIR` | Directory with Android/Bionic AArch64 `bin/rbw` and `bin/rbw-agent`, installed as dpkg package `rbw` (replacing a Termux `rbw`). |
| `--nixpp-setup-config FILE` | Defaults for `nixpp setup`, installed as `etc/nixpp/setup.conf`. |
| `--nixpp FILE` | AArch64 ELF installed as `bin/nixpp` and registered as dpkg package `nixpp`. |
| `--upgrade` | Allow a deb to replace an older version of a package bundled in the base. |
| `--hold-package NAME` | Mark an installed package held against APT upgrades (repeatable). |
| `--force-overwrite` | Allow overwriting files of another package (removed from its dpkg lists). |
| `--no-check-deps` | Skip the dpkg dependency check of the result. |

A prepared prefix may carry its own dpkg metadata: a `var/lib/dpkg/status` is merged stanza by
stanza (packages it names replace those of the base), and `var/lib/dpkg/info/*` files are copied.
A `SYMLINKS.txt` in it is merged too, and real symlinks become `SYMLINKS.txt` entries. Without
dpkg metadata the files are simply added and are not owned by any package.

Checks that make `build` stop without writing the archive: wrong architecture in a deb or an ELF
that is not AArch64, files outside the prefix or paths with `..`, conflicts with files of other
packages, md5sums that do not match, `.list` entries missing from the archive, unsatisfied
dependencies (including version constraints), missing required packages. `verify ARCHIVE`
runs the same checks on any bootstrap archive.

## Gradle

`downloadBootstraps` accepts an explicit archive for `aarch64` only:

| Environment variable | Gradle property |
| --- | --- |
| `TERMUX_BOOTSTRAP_AARCH64_ZIP` (path, or `file:`/`https:` URL) | `-PtermuxBootstrapAarch64Zip` |
| `TERMUX_BOOTSTRAP_AARCH64_SHA256` (64 hex digits) | `-PtermuxBootstrapAarch64Sha256` |

Both must be set; setting only one, a malformed digest or a checksum mismatch fails the build, and
the archive is verified before it is placed in `app/src/main/cpp`. Without them the hardcoded
hashes apply exactly as before, as they always do for `arm`, `i686` and `x86_64`. The override
applies to whichever `TERMUX_PACKAGE_VARIANT` is selected; the archive must match that variant.
A universal build still downloads the other three ABIs, so for device builds use the split
`arm64-v8a` APK.

## Prefix redirection of stock paths

`libtermux-pkg-redirect.so` is preloaded in fork shells and maps stock `/data/data/com.termux` and
`/data/user/0/com.termux` paths to the fork data dir (`TERMUX_APP__DATA_DIR`). That includes
shebang interpreters and the `LD_PRELOAD` of the environment passed to `exec*`/`posix_spawn*`,
because the Android linker reads `LD_PRELOAD` before any path can be redirected. Packages from the
Termux repository that launch with a stock absolute preload (for example neovim preloading
`libluajit.so`) therefore work unchanged, also after `apt upgrade`. Entries are rewritten only when
they start with a stock prefix; other entries, their order and the `:`/space separators are kept.
Without `TERMUX_APP__DATA_DIR` (stock Termux) nothing is rewritten. `scripts/test-pkg-redirect.sh`
covers both cases; run it on a disposable worker.

## Android helpers

TermuxPP no longer builds Debian packages of the `termux-am` and `termux-api`
helpers. Its Rust `tpp` API commands and the `am` launcher come from the nixpp
guest (`pkgs/nixpp/android-api.nix`). The native API bridge sources are under
`pkgs/tpp-api-bridge/`, and the `:termux-am` Gradle project remains in the
repository; neither is packaged as a `.deb` here. Stock `termux-am` and
`termux-api` packages address stock Termux identities (see
[the differences](termuxpp-differences.md)), so do not add them to this
bootstrap expecting the TermuxPP identity.

## Limitations of the upstream-based bootstrap

- Packages are not cross-built here. Use Termux repository debs, or build them elsewhere and pass
  them as debs or as a prepared prefix.
- `fetch` trusts HTTPS and the repository index; it does not verify the repository's GPG
  signature. Review and keep `debs.lock.json` for reproducible rebuilds. The Termux repository only
  keeps current versions, so archive the debs themselves for long-term reproducibility.
- Extra packages are baked into the bootstrap and registered in dpkg. They are not installed by
  a first-run `dpkg -i` step. Maintainer scripts are preserved but are not executed by the builder;
  package-specific initialization must be checked on the device. In particular, generate SSH host
  keys on the device with `ssh-keygen -A` before starting `sshd`. No SSH host keys, user credentials,
  or enabled SSH daemon are included in the archive.
- `nixpp` is recorded as a minimal dpkg package named `nixpp` with the version you give.
- Only xz, gzip and (Python 3.14+) zstd compressed debs are read.
