---
page_ref: /docs/apps/termux/tpp-cli.html
---

# tpp command line

`tpp` is one executable for controlling Android and the TermuxPP app from a
shell. It replaces the upstream `termux-*` API scripts.

- [Where it comes from](#where-it-comes-from)
- [Usage](#usage)
- [Commands](#commands)
- [JSON output](#json-output)
- [Plugins](#plugins)
- [Differences from termux-api](#differences-from-termux-api)

## Where it comes from

- **Native shell.** The app installs `bin/tpp` into its prefix at app and
  session start, with Bash, Zsh, and Fish completions. An app
  update updates the CLI too, so there is no package to install. API commands
  still call `am` from the prefix, so keep the `termux-am` package installed.
- **Nix guest.** The nixpp guest builds the same `tpp` and converts guest
  paths (`/android/...`, `/home`, `/nix`) to Android paths.

Source: [`pkgs/tpp-cli`](../../pkgs/tpp-cli/), with the API backends in
[`pkgs/tpp-api`](../../pkgs/tpp-api/) and the build notes in
[TERMUXPP.md](../../pkgs/tpp-api-bridge/TERMUXPP.md).

## Usage

```text
tpp [--json] COMMAND [ACTION] [ARGS...]
tpp help [COMMAND [ACTION]]
```

Every command and action accepts `-h`/`--help`. Help has no side effects, so
it is safe on commands such as `camera photo` or `sms send`. You can shorten a
command or action to any unique prefix (`tpp bat`). Output is colored on a
terminal and plain when piped, with `NO_COLOR` or with `TERM=dumb`.

```sh
tpp battery
tpp clipboard get > shot.png               # Text, or the image bytes
tpp clipboard get --text                   # Text only
echo hello | tpp clipboard set
tpp notification send -t Build -c "Finished"
tpp location get
tpp torch on
tpp volume set music 8
tpp ui settings --search "font size"
tpp ui palette toggle
ls -al | tpp ui picker
tpp session new --name logs -- tail -f build.log
```

When a command needs an Android permission, the app asks for it and the
command fails with an `API_ERROR`. Run it again after you grant it.

## Commands

Run `tpp help` for the list as installed, and `tpp help COMMAND` for its
actions and options.

**App and terminal**

| Command | Actions | Details |
| --- | --- | --- |
| `session` | `new`, `list`, `focus`, `rename`, `move`, `close`, `kill`, `switcher`, ... | [Session commands](terminal-settings.md#session-commands) |
| `ui` | `more`, `settings`, `session-switcher`, `sidebar` | [Opening the app interface](terminal-settings.md#opening-the-app-interface) |
| `settings` | `reload` | Reload `config.yaml`, fonts, and colors. |
| `tmux` | `attach`, `switcher` | [Remote tmux integration](termuxpp-differences.md#remote-tmux-integration) |
| `api` | `start`, `stop` | Start or stop the API keep-alive service. |

**Android**

| Area | Commands |
| --- | --- |
| Device | `battery`, `brightness`, `torch`, `vibrate`, `volume`, `wake-lock`, `wallpaper`, `sensor`, `fingerprint`, `keystore`, `infrared`, `usb`, `nfc` |
| Clipboard and sharing | `clipboard`, `share`, `download`, `storage`, `saf` |
| Notifications and UI | `notification`, `toast`, `dialog`, `job-scheduler` |
| Media | `audio`, `camera`, `media`, `tts`, `speech-to-text` |
| Phone | `telephony`, `sms`, `call-log`, `contacts` |
| Network and location | `wifi`, `location` |

## The app's tpp from the guest

In the Nix guest, `tpp --outer COMMAND ...` runs the native `tpp` that the
installed app ships (`bin/tpp` of the native prefix) instead of the guest's
build, with the same `am` and path conversion. The app's build is the one an
app update changes, so this is the first step towards a single `tpp`. Shell
completion offers `--outer` and completes the words after it like any `tpp`
command line.

The native prefix itself is bound at `/outer` in the guest (the app's
`files/usr`, also reachable through `/android`). Its binaries are Android
executables: run them through `/system/bin/linker64`, as `tpp --outer` does.

## JSON output

`--json` can go before the command or after it (`tpp --json battery`,
`tpp battery --json`). It prints the result as JSON instead of text:

- Structured results are printed as JSON. Without `--json` they are printed
  as readable text.
- Plain-text output is wrapped as `{"output": TEXT}`.
- For binary output, such as an image from `clipboard get` or a recording,
  `--json` is refused.
- Errors are printed as JSON objects with a non-zero exit status.

## Plugins

Any executable named `[a-z][a-z0-9-]*` in `$PREFIX/libexec/tpp/plugins` runs
as `tpp NAME`, and its name is listed under "Plugins" in `tpp help`. Set
`TPP_PLUGIN_DIR` to a colon-separated list of directories to change where tpp
looks, or set it empty to turn plugins off. Built-in commands take precedence
over plugins with the same name.

## Differences from termux-api

- Commands are grouped as `tpp GROUP ACTION` instead of `termux-GROUP-ACTION`
  scripts, and they address `dev.pschmitt.termux`, not `com.termux`.
- `clipboard get` also returns images. See
  [Reading clipboard images](termuxpp-differences.md#reading-clipboard-images-with-tpp).
- Missing permissions return `API_ERROR`, and `notification list` fails
  instead of hanging when notification access is missing. See
  [Upstream and TermuxPP differences](termuxpp-differences.md).
- Stock `termux-api` tools do not work with TermuxPP, and `tpp` does not work
  with stock Termux.
