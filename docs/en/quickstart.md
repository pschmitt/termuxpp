---
page_ref: /docs/apps/termux/quickstart.html
---

# Quick start

From a fresh install to a working, customized terminal in a few minutes.
Each step links to the full reference.

## 1. Install

Download the APK for your device from the
[`latest` release](https://github.com/pschmitt/termuxpp/releases/tag/latest)
(`arm64-v8a` on almost every current phone, `universal` if unsure) and install it.

Coming from upstream Termux:

- TermuxPP is a separate app (`dev.pschmitt.termux`) with its own home
  directory. Copy over anything you want to keep.
- Uninstall stock Termux first. Android refuses to install both, because both
  declare `com.termux.permission.RUN_COMMAND`.
- API, Tasker, Boot, Styling, and Widget are built in. You no longer need the
  separate companion apps. See [Built-in integrations](integrations.md).
- An existing `~/.termux/termux.properties` is converted once to
  `~/.config/termuxpp/config.yaml`. See [Settings file](terminal-settings.md#settings-file).

## 2. Install packages

The native terminal is a Termux environment, so `pkg` works as usual:

```sh
pkg update
pkg install git openssh
```

See the [Termux package management guide](https://github.com/termux/termux-packages/wiki/Package-Management).

## 3. Talk to Android with `tpp`

The app installs the `tpp` command into the native prefix and updates it with
the app. It replaces the `termux-*` API scripts:

```sh
tpp battery                  # Battery status
tpp clipboard get            # Clipboard text, or the image bytes
echo hi | tpp clipboard set
tpp notification send -t "Build" -c "done"
tpp session new --name logs -- tail -f build.log
tpp --json location get      # Any command can print JSON
tpp help                     # Every command, and -h on each one
```

Android asks for a permission the first time a command needs it. Run the
command again after you grant it. See [tpp command line](tpp-cli.md).

## 4. Make it yours

- **Settings → Properties** edits every option and writes
  `~/.config/termuxpp/config.yaml`. After you edit the file by hand, run
  `tpp settings reload`.
- Lay out the extra keys in the visual editor. You can also assign taps,
  swipes, and volume buttons to actions or shell commands. See
  [Terminal settings](terminal-settings.md).
- Swipe from the left edge for the session sidebar. Remote tmux windows can
  appear there too. See [tmux plugin](tmux-plugin.md).

## 5. Keep it updated

Install [Shizuku](https://shizuku.rikka.app/), then use **Settings → Updates →
Check for updates**. Updates must be signed with the same certificate as the
installed app. See [Self-update over Shizuku](self-update.md).

## Optional: the Nix guest

Builds that use the [custom AArch64 bootstrap](aarch64-bootstrap.md) also
include `nixpp`, a declarative Nix guest system that runs next to the native
environment. The release APKs use the upstream bootstrap and do not include it.
With such a build, the first start sets up the guest, and new sessions open in
it. `nixpp enter --outer` drops back to the native shell. See
[nixpp](../../pkgs/nixpp/source/README.md) and
[the Nix guest differences](termuxpp-differences.md).
