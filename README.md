<p align="center">
  <img src="art/termuxpp-logo.svg" width="128" alt="TermuxPP app launcher icon" />
</p>
<h1 align="center">TermuxPP</h1>
<p align="center">
  An Android terminal and Linux environment forked from Termux, with built-in
  Android integrations and fork-aware command-line helpers.
</p>

## Features

- **Terminal and Linux environment.** Run Termux packages in the Android
  app sandbox, or enter a managed Nix guest with the custom bootstrap.
- **Built-in integrations.** One APK includes API, Tasker, Boot, Float,
  Styling, and Widget. See [Built-in integrations](docs/en/integrations.md).
- **Terminal customization.** Assign tap and swipe actions, edit extra keys,
  and set fonts and colors. See [Terminal settings](docs/en/terminal-settings.md).
- **Fork-aware helpers.** TermuxAm and the API tools target TermuxPP while
  keeping their APT package names.
- **On-device updates.** Check and install the TermuxPP APK through Shizuku.
  See [Self-update over Shizuku](docs/en/self-update.md).

## Install

Install the [TermuxPP APK][core-releases] for your device. For an in-place
update, use a build signed by the same certificate as your installed app.

## Quick start

Open TermuxPP. In the native terminal, use `pkg` to install command-line tools.

```sh
pkg update                 # Refresh package metadata
pkg install git            # Install Git
git --version              # Check that Git is available
```

See the [Termux package management guide][package-management] for APT usage.

## Documentation

| Topic | Description |
| --- | --- |
| [Terminal settings](docs/en/terminal-settings.md) | Properties, fonts, colors, touch gestures, extra keys, and links. |
| [Built-in integrations](docs/en/integrations.md) | Use API, Tasker, Boot, Float, Styling, and Widget. Migrate standalone installations. |
| [Self-update over Shizuku](docs/en/self-update.md) | Configure and use the in-app APK updater. |
| [Custom AArch64 bootstrap](docs/en/aarch64-bootstrap.md) | Build a verified bootstrap. |
| [Upstream and TermuxPP differences](docs/en/termuxpp-differences.md) | App identity, compatibility, the Nix guest, services, and ADB access. |
| [API CLI helper](pkgs/tpp-api-bridge/TERMUXPP.md) | Build the native API command-line tools. |
| [TermuxAm helper](android/components/termux-am/TERMUXPP.md) | Build the Android command helper. |
| [nixpp](pkgs/nixpp/source/README.md) | The Nix guest runtime and system manager, and its Nix packages. |

## License

The core is [GPL-3.0-only](LICENSE.md). See the linked library licenses for
third-party code and license exceptions.

[core-releases]: https://github.com/pschmitt/termuxpp/releases
[package-management]: https://github.com/termux/termux-packages/wiki/Package-Management
