---
page_ref: /docs/apps/termux/termuxpp-differences.html
---

# TermuxPP and Upstream Termux

This is a behavior comparison between this TermuxPP tree and the current
[`termux/termux-app` upstream source](https://github.com/termux/termux-app/blob/master/app/build.gradle).
It records compatibility choices and intentional fork differences, not every
source-code change. Recheck both implementations when updating this page.

## Differences

| Area | Upstream Termux | TermuxPP | Compatibility or impact |
| --- | --- | --- | --- |
| Terminal rendering | Upstream currently lacks DECSET 2026 synchronized output; [PR #5441](https://github.com/termux/termux-app/pull/5441) proposes support. | TermuxPP supports mode queries and synchronized frames, coalesces screen notifications, and releases a stalled frame after 100 ms. Reset and process exit flush immediately. | Adapted from upstream PR #5441 to the fork's existing mode handling. Sources: [`TerminalEmulator`](../../terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java) and [`TerminalSession`](../../terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java). |
| Android identity and companions | The core uses the `com.termux` application ID and shared user ID; API, Tasker, Boot, Styling, Widget, and Float are separate apps. | The core application ID and shared user ID are `dev.pschmitt.termux`; the Java namespace remains `com.termux`. API, Tasker, Boot, Styling, Widget, and Float are bundled into the core APK. | Intentional fork identity and packaging. Existing standalone companion packages can remain installed while the core APK is updated; the API share provider has its own `.api.sharedfiles` authority to avoid colliding with the old API provider. |
| `RUN_COMMAND` permission | The core declares `com.termux.permission.RUN_COMMAND`. | The core service requires `dev.pschmitt.termux.permission.RUN_COMMAND`. A `com.termux.permission.RUN_COMMAND` alias is also declared for Tasker/Locale hosts that only request the stock name. | Compatibility alias, with a co-install limitation: Android rejects the duplicate permission declaration if stock Termux and TermuxPP core are installed together. The alias does not replace the fork-specific service permission. |
| Prefix paths | The app data and prefix use the stock `com.termux` identity. | The prefix is under the TermuxPP app data directory. Fork shell environments preload `libtermux-pkg-redirect.so`, which remaps selected hard-coded stock app paths and `LD_PRELOAD` entries for upstream package binaries. | Compatibility layer for stock-path assumptions in packages. It does not change Android package identity, permissions, or API receiver identity. |
| Bootstrap archive | The app downloads the pinned upstream bootstrap archive for each supported ABI. | The same upstream archives remain the default. A build may supply a SHA-256-verified custom AArch64 archive; the override does not affect the other ABIs. | Opt-in TermuxPP build capability. Published APKs use the upstream archive unless the build explicitly supplies the custom inputs. See [Custom AArch64 Bootstrap](aarch64-bootstrap.md). |
| Full-Nix guest | The default session runs the native `bin/login` shell of the Termux prefix. | When the prefix provides the full-Nix `nixpp` with its launcher, bootstrap seed and session manifest (`$PREFIX/share/nixpp`), the first-start bootstrap overlay also runs the bootstrap second stage and `nixpp --json init`, showing each setup step and package in a live log while the on-screen keyboard is hidden. When the guest is ready, its home supplies the app properties, fonts and colors, with native files as fallbacks. The in-app Properties editor writes to that same guest home. The custom bootstrap also installs `nixpp-setup`, which downloads and starts yadm-init interactively for device enrollment. New default sessions then run `nixpp login`, which finishes an interrupted setup and enters the Nix guest. `nix-session=false` keeps the native shell; `nix-user` names the guest user of a new runtime (default `nix`). The failsafe session always starts `/system/bin/sh`. | TermuxPP-only and inactive without these bootstrap contents, so upstream-bootstrap builds keep the stock behaviour. A failed setup is not fatal: the session reports it and continues in the native shell. The guest is started by the in-process seccomp launcher, not proot. Sources: [`TermuxNix`](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/nix/TermuxNix.java), [`TermuxNixSetup`](../../app/src/main/java/com/termux/app/TermuxNixSetup.java), [`TermuxInstaller`](../../app/src/main/java/com/termux/app/TermuxInstaller.java) and [`TermuxSession`](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/shell/command/runner/terminal/TermuxSession.java); nixpp, its launcher and seed: [`pkgs/nixpp`](../../pkgs/nixpp). |
| Guest signal stack | Upstream Termux has no managed Nix guest. | Guest `tpp` and its session aliases preload the nixpp signal-stack adapter to reserve enough space for Android syscall translation. | Compatibility workaround for Rust's smaller alternate signal stack; it does not affect the native Android executable. Sources: [`android-api.nix`](../../pkgs/nixpp/android-api.nix) and [`signal-stack.c`](../../pkgs/nixpp/signal-stack.c). |
| Nix controller lifecycle | Upstream Termux has no Nix guest controller. | Startup and package replacement publish the current Android library path and start the enrolled nixpp controller. | The matching native nixpp finishes its current request before reopening its socket with libraries from the current APK. Source: [`NixppRuntimeRecovery`](../../app/src/main/java/com/termux/app/NixppRuntimeRecovery.java). |
| Termux:API | API commands address the upstream app and its socket. | The bundled API components run inside `dev.pschmitt.termux`; native tools and the API client target the core receiver, socket, and prefix. The share provider authority is `dev.pschmitt.termux.api.sharedfiles`. | Intentional runtime identity change. The nixpp guest builds this repository's native API bridge and Rust `tpp` executable (not the stock or droid-nix `termux-api` scripts), so `tpp <command> -h` prints detailed help without side effects and guest paths are converted to Android paths. The build validates every command's help and its backend link. Stock `termux-api` tools are not interchangeable. |
| TermuxAm | The stock helper identifies its caller as `com.termux`. | The helper defaults its caller identity to `dev.pschmitt.termux`. Its Java namespace remains `com.termux.termuxam`, because the APK is loaded by `app_process`, not installed as a companion app. | Intentional runtime identity change. Filesystem path redirection cannot change the Android caller identity. |
| APT helper packages | The stock `termux-am` and `termux-api` packages address stock Termux identities. | TermuxPP does not build Debian packages of these helpers. The nixpp guest provides the `tpp` API commands and the `am` launcher for the TermuxPP identity; `tpp-setup-storage` runs the Rust `tpp` executable and sends its storage-permission request through that guest `am`, not the outer prefix's. | Intentional fork difference. Stock `termux-am` and `termux-api` packages remain stock-identity software and are not interchangeable with the guest tools. See [API helper tools](../../pkgs/tpp-api-bridge/TERMUXPP.md), [TermuxAm](../../android/components/termux-am/TERMUXPP.md) and [`android-api.nix`](../../pkgs/nixpp/android-api.nix). |
| Android API commands | Upstream `termux-api` installs one `termux-*` script per command (`termux-battery-status`, `termux-wifi-scaninfo`, ...) in `bin`. | The API tools install one native executable, `tpp` (a Rust program, the crate [`tpp-cli`](../../pkgs/tpp-cli), with the session control and the API backends of [`tpp-api`](../../pkgs/tpp-api) linked in; see [Terminal session CLI](#terminal-session-cli) -- no shell, awk or table file is involved in dispatching or rendering) (`tpp battery`, `tpp wifi scan`, `tpp vibrate`, ...), with bash, zsh and fish completions. The command table (names, descriptions, output class, argument hints and option words) is compiled into the binary, in [`commands.rs`](../../pkgs/tpp-cli/src/commands.rs); every built-in API command runs inside the binary (`tpp_api::has`), talking to the app through the same TermuxAm socket protocol as the C client; no command script is installed or looked for, and there is no fallback to one. `tpp-api/tests/oracle` keeps a copy of every former shell script, and the Rust backends are tested against it on arguments, standard input, output, error output and exit status (the only place the scripts still exist). The Rust backends deliberately differ from the scripts in a few places: option values that contain blanks reach the app as one argument instead of being word-split, `echo` is bash's, messages that bash or util-linux would print (`getopts`, `getopt`, `realpath`, `touch`, `$((...))`) are reproduced by the command and may be worded differently in rare cases, a failing connection to the app exits nonzero instead of hanging, and the `usb` callback runs without the exported `TERMUX_CALLBACK` shell function; each module lists its own deviations under `Deviations:`. Two behaviors that depend on the process rather than on the arguments follow the scripts: the standard input is shared between the API call and the `usb` callback as it was with the C client (the call forwards it to the app while it lasts, and the callback gets what is read after the app's answer, including what the call had not read yet, through a pipe instead of the terminal itself; see [`stdin_split.rs`](../../pkgs/tpp-api/src/stdin_split.rs)), and `tpp sensor` handles SIGINT and SIGTERM like the script's trap (`Caught interrupt.. Finishing...`, then the `cleanup` call when sensors were being listened to, then exit status 128 plus the signal number; a signal that reaches the script alone waited for the stream to end, here it ends the stream at once). While a command holds the interrupt guard ([`sys.rs`](../../pkgs/tpp-api/src/sys.rs)), the native transport polls for the signal instead of blocking in `accept` and `recvmsg`. The transport is tested against stand-in plugins over real abstract unix sockets ([`native_loopback.rs`](../../pkgs/tpp-api/tests/native_loopback.rs): payload, standard input forwarding, `SCM_RIGHTS` descriptor passing and the lone `@` message, 1 MiB binary streams, abstract socket naming and release, failing or missing `am`) and the signal handling in a test binary of its own ([`interrupt.rs`](../../pkgs/tpp-api/tests/interrupt.rs)). Outside the Android app, the commands reach the app through `am broadcast`; the guest wrapper points them at TermuxAm and at the guest-to-Android path conversion with `TPP_API_AM` and `TPP_API_PATH_CMD`. A test checks that the table and the commands of `tpp-api` (`tpp_api::IMPLS`) name the same set, and another that the table and the oracle scripts do. No `termux-*` scripts are installed. Completion goes through a hidden `tpp --complete WORD...`: it suggests commands, actions, the options of each command (and their fixed values, such as `tpp location get --provider gps`) from the built-in option table, `--json` only where the command supports it and only once, and asks a plugin with `PLUGIN __complete WORD...`. A command without a row in the option table only completes `--help` and `--json`. Every command accepts `-h`/`--help`; `--json` prints JSON (colored on a terminal, plain when piped) and refuses binary output (`tpp saf read`, `tpp keystore sign`, `tpp usb open`; the command table can mark a command raw only for some arguments) and non-UTF-8 text instead of corrupting it, but never refuses `--help`. Sensor responses and human-readable location updates are rendered incrementally; with `--json`, continuous sensor readings are a sequence of JSON documents rather than one array, while location updates remain a single JSON array. `tpp usb open COMMAND` runs the callback with the real standard output, so its output is streamed byte for byte (NUL bytes and trailing newlines included, unlike upstream's `termux-usb`, which buffers it in a shell variable), and its exit status is the callback's; the client's own failure messages (`No such device.`, `Permission denied.`, ...) still end with status 1, and so does an answer of the app that carries neither a device nor a message (the callback never ran; upstream reports success). The output is saved in a descriptor the command allocates itself (10 or higher), so descriptors the caller passes down, such as 4, reach the callback untouched, and the callback itself gets only the standard descriptors and the device. Other commands are added as executable plugins from `TPP_PLUGIN_DIR` (default `PREFIX/libexec/tpp/plugins`), which receive their arguments, including `--json`, unchanged. `tpp list --names` prints just the words that select each command, for scripts. | Intentional fork difference: the command names are not upstream-compatible, so scripts written for `termux-*` must be updated. The native `libexec/termux-api` client, `termux-callback` and the `termux-api` package name are unchanged; they are the platform bridge for programs and scripts that still use the old interface, and `tpp` does not use them. The shell dispatcher and its `awk` renderer are gone (`TPP_AWK` has no effect; no `awk` is needed), so rendering is stricter than the awk version was: it validates the whole document before printing and falls back to the unmodified text only for non-JSON output. The `tpp` crates are licensed GPL-3.0-only (the rest of the API package keeps its license). See [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs), [`render.rs`](../../pkgs/tpp-cli/src/render.rs), [`complete.rs`](../../pkgs/tpp-cli/src/complete.rs) and [`commands.rs`](../../pkgs/tpp-cli/src/commands.rs). |
| Native `tpp` installation | Upstream installs `termux-api` as a Debian package; its files change only through `apt`. | The APK carries the native `tpp` CLI (`bin/tpp`, `libexec/tpp`, `libexec/termux-callback`, the completions and the C `termux-api` client) and installs it into the native prefix at app and session start, so updating the app updates the CLI. The client ships as `libtermux-api-broadcast.so` (Android only extracts `lib*.so` files from an APK) and `libexec/termux-api-broadcast` is a symlink to it. The installer writes only paths that are absent or that it installed before and nobody changed (a SHA-256 record in `var/lib/tpp/native-install.json`), replaces files atomically, and leaves everything else alone, listing it in the app log: files of a Debian package, files you edited, foreign files and directories. It never runs `dpkg` or `apt` and never edits the dpkg database. Stale `termux-api` entries (installed according to dpkg, files gone) are only reported; the stale `termux-am` entry is to be kept, because `bin/am` is still what the client runs. Other native binaries (the Rust session CLI) plug in as `lib<name>.so` entries of the same manifest. | Intentional fork difference. The APK no longer ships `bin/am` or the helper APK, so the native client needs a working `PREFIX/bin/am` that is already installed. Nothing removes the old `termux-api` and `termux-am` packages automatically: remove them with `apt` yourself once nothing needs them. Sources: [`NativeCliInstaller`](../../app/src/main/java/com/termux/app/nativecli/NativeCliInstaller.java), [`DpkgDatabase`](../../app/src/main/java/com/termux/app/nativecli/DpkgDatabase.java), [`NativeManifest`](../../app/src/main/java/com/termux/app/nativecli/NativeManifest.java) and [`tpp-native.gradle`](../../app/tpp-native.gradle). |
| Default API actions | Upstream exposes separate command scripts. | A `tpp` group with an explicit `list` action defaults to that action when invoked without arguments; otherwise it defaults to `get` if present. USB and sensor operations use named actions such as `tpp usb list` and `tpp sensor read`. | This applies to groups in the built-in command table, not plugins or standalone commands. Source: [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs). |
| API permission requests | Upstream reports missing runtime permissions in an `error` field before requesting them. | TermuxPP reports them as `API_ERROR`, so structured `tpp` commands can report a failure instead of treating the response as successful data. The current invocation does not resume after the permission dialog. | This changes the API response field for missing permissions; callers using the underlying bridge directly must account for it. Sources: [`TermuxApiPermissionActivity`](../../android/components/termux-api/app/src/main/java/dev/pschmitt/termux/api/activities/TermuxApiPermissionActivity.java), [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs). |
| Location requests | Upstream requests foreground location permission before starting the location service. | TermuxPP does not reject every request lacking background location permission; Android may still restrict delivery when the app is not in the foreground. A one-shot request that receives no location within 30 seconds returns an API error. | Foreground-only grants can expire, and removing the blanket check does not bypass Android's background restrictions. Source: [`LocationAPI`](../../android/components/termux-api/app/src/main/java/dev/pschmitt/termux/api/apis/LocationAPI.java). |
| Empty API responses | Some upstream API commands return no output. | A successful empty captured response displays `No output` in human mode and `null` with `--json`; an empty location updates array displays `No results` in human mode. | This does not infer whether a request succeeded beyond its exit status; text and binary streams remain unchanged. Source: [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs). |
| Silent API actions | Several upstream API commands return no output on success. | `tpp vibrate`, `toast`, `torch`, notification send/remove/channel, `brightness`, clipboard set, Wi-Fi enable/disable, telephony call, media scan and player play/pause/stop, SMS send, infrared transmit, TTS speak, download, share, and wallpaper print `Request sent` when the API returns successfully without output; `--json` returns a status object instead. | This acknowledges the request, not completion of an asynchronous Android action. Errors and nonempty responses remain unchanged. Source: [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs). |
| Notification listing | Notification access is required to list active notifications. | If notification access is missing, `tpp notification list` returns an error instead of leaving the API client waiting. If the listener is disconnected, the app requests a rebind and waits briefly before returning an error. | Intentional runtime improvement; access must still be granted in Android settings. Source: [`NotificationListAPI`](../../android/components/termux-api/app/src/main/java/dev/pschmitt/termux/api/apis/NotificationListAPI.java). |
| Kill intent | No equivalent; the app can only be exited through its notification or force-stopped. | `RunCommandService` accepts `dev.pschmitt.termux.KILL` (same `RUN_COMMAND` permission and `allow-external-apps` policy). It SIGKILLs all shells and then kills the app process. Boolean extra `dev.pschmitt.termux.KILL_RESTART` schedules a relaunch of the main activity through `AlarmManager`. Tasker hosts cannot hold the fork's `RUN_COMMAND` permission, so the built-in Tasker plug-in has a "Kill TermuxPP" action (with a restart checkbox) that sends the kill from the plug-in's shared UID. | TermuxPP-only addition. The relaunch is best effort: it relied on the app's "display over other apps" grant on the test device, and Android may otherwise block the background activity start. Source: [`RunCommandService`](../../app/src/main/java/com/termux/app/RunCommandService.java), [`TermuxService#actionKill`](../../app/src/main/java/com/termux/app/TermuxService.java). |
| ADB shell | No equivalent TermuxPP endpoint. | Debugging settings explicitly enable an app-private loopback listener on `5522` for enrollment and mTLS PTY, command and management connections. Token-authenticated enrollment requires on-device fingerprint-only approval of each concrete client certificate. Subsequent enrollment preserves the server identity. Both the GUI and authenticated CLI list and revoke individual certificate authorizations; disabling access deletes the token, allowlist, and device TLS material. | The host CLI supports `--mode auto|mtls|run-as`: auto prefers Android `run-as` for debuggable installations and selects mTLS only when Android reports the app is not debuggable. The opt-in mTLS endpoint works without SSH or `run-as`. The host verifies the server certificate and loopback IP identity. The device requires an exact approved leaf-certificate pin, certificate validity/clientAuth usage, and proof of key possession through TLS. The token is required only for enrollment; approved certificate sessions do not prompt for it. CA membership alone never authorizes a client. |
| Tasker and Locale | The plug-in protocol is the stock Termux Tasker/Locale protocol. | The plug-in is bundled into the core APK, keeps the protocol action names, and runs with the core app identity and shared UID. Its external-app policy uses the same properties file as the main app, including the managed guest home. | Protocol compatibility is retained. Tasker must be granted `com.termux.permission.RUN_COMMAND`; existing tasks may need to select the bundled TermuxPP plug-in again because Android stored the former standalone package identity. See [Tasker integration](integrations.md#tasker-and-locale). |
| Updates | Upstream distributes its app through its own release channels. | Settings > Updates shows the installed build, update status, and the next action in one card. It installs the TermuxPP APK through Shizuku after confirmation. The APK contains API, Tasker, Boot, Styling, Widget, and Float. | TermuxPP-only updater. It updates one app and does not alter APT package updates. See [Self-update over Shizuku](self-update.md). |

The public CLI spells out actions such as `tpp dialog list`, `tpp job-scheduler list`, `tpp job-scheduler cancel ID`, `tpp audio stop`, and `tpp saf list DIRECTORY`. Additional actions include `tpp sms conversations`, `tpp media play`, and `tpp location watch`. `tpp list --json` returns command descriptions as JSON, and `--names` limits the result to command names. Invalid volume, NFC and dialog arguments fail with a nonzero exit status. Help and completion use descriptive options such as `--provider`, `--limit` and `--title`. Help uses terminal colors and respects `NO_COLOR`. Commands that wait for Android, including dialog and location requests, report what they are waiting for on stderr in human mode. `--json` keeps progress off the output. If Android denies notification listener access, `tpp notification list` points Nix guest users to `tpp permissions grant notification-access`.

## Bootstrap tools and garbage collection

The managed Nix runtime registers permanent store roots for its static BusyBox
and Nix. BusyBox supplies the guest's `/bin/sh` and `/usr/bin/env`, and static
Nix can run in bootstrap mode. Runtime startup also registers these roots for
existing installs. Before pruning generations, `nixpp gc` tries to restore any
missing bootstrap store paths from the public cache and checks both guest
entry points. [Bootstrap preparation](../../pkgs/nixpp/source/src/bootstrap.rs)
and [garbage collection](../../pkgs/nixpp/source/src/gc.rs) implement this
TermuxPP runtime behavior.

## Session sidebar

Upstream opens the rename dialog when a session is long-pressed. TermuxPP opens
session actions instead, including rename, reorder and close; holding the session
number picks the row up: it follows the finger, the other rows make room as it
passes them, the list scrolls near its top and bottom edges, and a cancelled
gesture puts the row back. The rename dialog starts with the custom session name,
falling back to its terminal title when no custom name is set. Sources: [upstream session list](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java)
and [TermuxPP session list](../../app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java).

## Terminal More menu

Upstream uses Android's context menu for terminal actions. TermuxPP uses a
rounded bottom sheet with a compact session card, an Autofill / Open URL / Share
shortcut card, and grouped icon-led rows. Open URL is disabled when the terminal
contains no URLs. Autofill remains conditional on Android support. Keep screen on
and Wake lock have an On/Off state pill: each stays visible while On and moves
under **More** while Off. Reset and Kill are also under **More**.
Settings remains visible in its own group at the bottom. Tapping Kill retains its
confirmation, and holding it retains the immediate-kill action. Share and
Autofill choices retain their existing preview cards. The presentation is an
intentional fork difference; action dispatch and authorization are unchanged.

Sources: [upstream context menu](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxActivity.java),
[`TerminalContextMenu`](../../app/src/main/java/com/termux/app/terminal/TerminalContextMenu.java),
and [`TermuxContextMenuSheet`](../../app/src/main/java/com/termux/app/terminal/TermuxContextMenuSheet.java).

## Using the ADB shell

The CLI supports three transports selected with `--mode`:

- `auto` (default): probe Android `run-as`, use it for a debuggable installation,
  otherwise use mTLS. Only an explicit "package not debuggable" response selects
  mTLS; ADB failures and shell-launch failures are reported without fallback.
- `run-as`: require a debuggable APK. No token, certificate enrollment, or enabled
  in-app ADB shell endpoint is needed. Android's authorized ADB connection and
  `run-as` checks control access. The CLI initializes HOME, PREFIX, PATH,
  LD_LIBRARY_PATH and fork prefix redirection before starting the selected login shell.
  The bootstrap must already be installed. Native libraries are resolved from
  the current APK location, not a path cached across app upgrades.
- `mtls`: explicitly use the authenticated endpoint, including on debug builds.

For example, `python3 pkgs/tpp-adb-shell/tpp-adb-shell.py --serial SERIAL --mode run-as`.
Provisioning, listing authorizations, and revocation always use mTLS in auto mode;
combining these operations with explicit `--mode run-as` is an argument error.
Interactive shells require a terminal. To run an ad-hoc command without a PTY,
append `-- COMMAND ARG...`; stdin, stdout and stderr work with pipes and files,
and both transports return the remote command's exit status. For example:

```sh
tpp-adb-shell --serial SERIAL --shell zsh -- ls -al
tpp-adb-shell --serial SERIAL -- printf '%s\n' 'two words' '$(literal)'
tpp-adb-shell --serial SERIAL -- sh -c 'printf error >&2; exit 7'
```

Arguments are passed literally, not joined into an interpreted command. Use an
explicit shell command such as `sh -c '...'` for remote pipelines or redirection;
unquoted local shell syntax is still interpreted on the host. Commands run from
TermuxPP's HOME through the selected login shell, using its configured PATH.
The mTLS command protocol requires the updated app. Requests are limited to
64 KiB and 4096 arguments; empty or option-like executable names and NULs are
rejected. Command text and output are not logged by the service.

Both transports resolve the configured shell symlink in order:
`~/.config/termuxpp/shell`, `~/.config/termux/shell`, then `~/.termux/shell`.
Without a configured shell, they fall back to `$PREFIX/bin/bash`, then
`$PREFIX/bin/sh`. A broken or non-executable configured shell is an error, not
an excuse to silently start a different shell. Use `--shell zsh` (resolved under
`$PREFIX/bin`) or `--shell /absolute/path/to/shell` to override it for one session.
The executable receives login-shell startup, and `SHELL` names the resolved
executable. The option is not a shell command and cannot include arguments.
The mTLS override requires an updated app.

### mTLS enrollment and access

1. In TermuxPP, open **Settings > Debugging** and enable **ADB shell access**. Access is off by default. The app generates a random 256-bit token and shows it once; copy it without sharing it.
2. On the host, run `python3 pkgs/tpp-adb-shell/tpp-adb-shell.py --serial SERIAL --provision --name WORKSTATION`. The helper prompts for the token without echoing it, creates a 90-day client certificate, and prints its SHA-256 fingerprint. Open the approval notification on the phone, compare that fingerprint, and authenticate using an enrolled fingerprint. PIN and face authentication are not accepted; unavailable authentication, cancellation, backgrounding, or a two-minute timeout denies enrollment. The first approved enrollment installs a loopback-only server identity, encrypting the server private key to a device-generated RSA provisioning key; later enrollments cannot replace it. The helper retains no server private key and stores client credentials in a mode-`0700` directory with mode-`0600` files.
3. Run `python3 pkgs/tpp-adb-shell/tpp-adb-shell.py --serial SERIAL --mode mtls` for an interactive mTLS shell. The helper forwards loopback port `5522` once, verifies the pinned server certificate and `127.0.0.1` identity, and presents the approved client certificate without requesting the enrollment token. Update the host helper and the app together to use the single listener. Existing approved certificates remain usable without re-enrollment. Neither token nor private-key material is placed in command arguments or output.
4. Review authorizations under **Settings > Debugging > ADB shell authorizations**, or run `python3 pkgs/tpp-adb-shell/tpp-adb-shell.py --serial SERIAL --list-authorizations`. Revoke a client in the GUI or use `--revoke FULL_LOWERCASE_SHA256_FINGERPRINT` from an authorized CLI. Revocation disconnects affected active clients; GUI revocation conservatively disconnects all clients. Local credential files are not deleted by remote revocation. Turning access off stops both listeners, clears the allowlist and token, and deletes the device TLS material. Re-enabling requires fresh enrollment.

The authorization manager is a native settings page, not a management dialog.
It shows the access state, loopback connection information, enrollment guidance,
and approved clients. Selecting a client opens its name and full SHA-256
fingerprint, with a confirmed revocation action. These pages never display the
enrollment token. Enrollment approval remains a separate fingerprint-only flow.

Enabled access is restored when TermuxPP starts, after an app update, and after
boot when Android permits starting the foreground service. Recovery never enables
access that was disabled, rotates the token, or approves a new client. Opening the
updated app retries recovery if Android blocked a background start. Listener bind
failures stop the whole endpoint instead of leaving only one listener alive.
Unavailable listeners produce a CLI diagnostic rather than a raw TLS EOF. Do not
disable/re-enable access just to restart it: disabling deliberately revokes all
approvals and deletes its TLS identity.

The host requires `adb`, Python 3, and OpenSSL. Credentials are stored separately for each ADB serial (or device alias); use `--credential-dir` to select a directory explicitly when switching transports. The helper creates ephemeral local ADB forwards and removes only those it created. Certificates are generated locally; no private key is written to the repository. For certificate renewal, enroll into a new `--credential-dir` and then revoke the old fingerprint. Existing client credentials are never silently overwritten. At most 32 certificates are approved, with up to four concurrent TLS connections. Certificates enforce wall-clock validity; correct the host/device clock rather than disabling verification. The one-year server certificate currently requires disabling and re-enabling access, followed by re-enrollment, to renew it.

### Device aliases

`--serial` accepts a raw ADB serial (unchanged) or an alias from a private runtime JSON file,
`$XDG_CONFIG_HOME/termuxpp/adb-devices.json` (override with `--devices-config` or
`TERMUXPP_ADB_DEVICES`). The file must be a regular file owned by you (or root) and not
writable by others; it is never part of this repository.

```json
{
  "version": 1,
  "devices": {
    "phone": {
      "serials": ["USB_SERIAL", "192.0.2.10:5555"],
      "serial_prefixes": ["adb-USB_SERIAL-"],
      "metadata": {"model": ["MODEL"]},
      "credential_dir": "/optional/absolute/dir",
      "ssh": {"port": 2222, "start_argv": ["EXECUTABLE", "ARG"]}
    }
  }
}
```

An alias needs at least one of `serials`, `serial_prefixes` or `metadata` (`model`, `device`,
`product` values from `adb devices -l`; all exact matches). Candidates are matched against
`adb devices -l` each time, and exactly one must be in the `device` state. Several ready
candidates (for example USB and Wi-Fi at once), none, or only unauthorized/offline ones are errors;
nothing is guessed, and no network scanning or `adb connect` is performed. Resolution happens once
per invocation and the resolved serial is used for every later ADB call. Unknown keys are rejected.
`tpp-adb-shell --list-aliases` prints the alias names (used by completions).

Alias credentials live in a stable `adb-shell/aliases/NAME` directory, or in `credential_dir`
when configured. If that directory has no credentials yet but exactly one legacy per-serial
directory (of the resolved serial or of an exact serial listed by the alias) does, that directory is
reused in place; several candidates are an error until `credential_dir` picks one, so identities are
never merged. Credentials are never copied, overwritten or enrolled automatically. Enrollment stays an explicit `--provision`. `--credential-dir`
still overrides everything.

### SSH over ADB

`pkgs/tpp-adb-shell/tpp-adb-ssh-proxy.py` is an OpenSSH `ProxyCommand` that tunnels to a TermuxPP SSH
daemon through `adb shell -T toybox nc 127.0.0.1 PORT` (default port `2222`, from alias `ssh.port`
or `--port`); no ADB port forward or network route is needed.

```
Host phone
  ProxyCommand tpp-adb-ssh-proxy --serial phone
```

The helper resolves the alias once and uses that serial for everything. It first probes the
device-side port with `toybox nc` (stdin detached, so the SSH stream is not consumed; bounded by
`--probe-timeout`). Only if the listener is down, an alias `ssh.start_argv` is run through the
authenticated mTLS command endpoint via the CLI (existing approved credentials only, output sent to
stderr, total wall-clock limit `--start-timeout`), then the port is re-probed once a second for up to
`--ready-timeout` before exec'ing `adb ... nc`. A failing, timed-out or non-zero start command is
not fatal (a concurrent starter may have won, or a daemon may hold the command's pipes open): the
listener is re-probed regardless, nothing is ever stopped or restarted, and the start error is only
reported if the port never comes up. Without `start_argv` (or with `--no-start`) a down
listener is an error: TermuxPP has no built-in daemon starter, so supply the command that starts
your daemon (for example a termux-services `sv up` call) in the private configuration. An
inconclusive probe (adb failure, timeout) never starts anything. Only an explicit
"Connection refused" counts as down (`toybox nc` exits 0 after connecting); any other probe
failure is an error and never starts anything.

### Shell completions

The CLI ships Bash, Zsh, and Fish integrations in [`scripts/completions`](../../scripts/completions).
They complete options, transport values, connected/authorized ADB device serials, configured aliases,
and credential directories. After `--`, they offer local command and filename
completion, not the phone's command list or filesystem. They register both `tpp-adb-shell` and
`tpp-adb-shell.py` (invoke the executable directly, rather than through
`python3`, for command-name completion). Load them in an interactive shell:

```bash
# Bash
source <(python3 pkgs/tpp-adb-shell/tpp-adb-shell.py --completions bash)
# Zsh, after compinit
source <(python3 pkgs/tpp-adb-shell/tpp-adb-shell.py --completions zsh)
# Fish
python3 pkgs/tpp-adb-shell/tpp-adb-shell.py --completions fish | source
```

Keep the `completions` directory and `termuxpp_adb_devices.py` alongside the Python scripts when installing them.
OpenSSL is needed only for mTLS enrollment, not for `run-as`.

**Validation status:** the run-as transport requires real-device acceptance testing;
mocked tests do not prove Android's native-library access or PTY behavior.
Real-device mTLS acceptance on a non-debuggable build passed on 2026-11-16:
incorrect-token rejection, fingerprint-approved enrollment, authorization listing,
a shell command over mTLS, revocation, and denial of the revoked client.
On 2026-11-17, an in-place non-debuggable update restored the enabled listener
and retained the existing client identity. Repeated token-free calls, literal
arguments, a Zsh override, piped stdin, separate stderr, exit status 7, and a
1 MiB full-duplex transfer passed on a real device. Auto-mode probes and APK
lookup do not inherit command stdin. A successful APK build or mocked unit test
alone does not prove those behaviors.
The checked-in development signing key is public and must not be treated as a
production signing identity.

## Source references

- ADB shell access: [`AdbShellService`](../../app/src/main/java/com/termux/app/AdbShellService.java), [`AdbShellExec`](../../app/src/main/java/com/termux/app/AdbShellExec.java), [`AdbShellRecovery`](../../app/src/main/java/com/termux/app/AdbShellRecovery.java), [`authorization settings`](../../app/src/main/java/com/termux/app/fragments/settings/termux/AdbShellAuthorizationsPreferencesFragment.java), [`PtyProcess`](../../terminal-emulator/src/main/java/com/termux/terminal/PtyProcess.java), and [`tpp-adb-shell.py`](../../pkgs/tpp-adb-shell/tpp-adb-shell.py).

- Core package identity, bootstrap selection, and optional archive verification: [`app/build.gradle`](../../app/build.gradle), [`app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml), and [`docs/en/aarch64-bootstrap.md`](aarch64-bootstrap.md).
- Shell path redirection: [`TermuxShellEnvironment.java`](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/shell/command/environment/TermuxShellEnvironment.java) and [`termux-pkg-redirect.c`](../../app/src/main/cpp/termux-pkg-redirect.c).
- API and Tasker identities: [`termux-api/app/build.gradle`](../../android/components/termux-api/app/build.gradle), [`termux-api-package/TERMUXPP.md`](../../pkgs/tpp-api-bridge/TERMUXPP.md), and [`termux-tasker/TERMUXPP.md`](integrations.md#tasker-and-locale).
- TermuxAm caller identity: [`termux-am/TERMUXPP.md`](../../android/components/termux-am/TERMUXPP.md); guest helper provisioning: [`pkgs/nixpp/android-api.nix`](../../pkgs/nixpp/android-api.nix).
- Upstream identity reference: [`termux/termux-app` app build](https://github.com/termux/termux-app/blob/master/app/build.gradle) and [manifest](https://github.com/termux/termux-app/blob/master/app/src/main/AndroidManifest.xml).

## Properties settings and configuration filename

TermuxPP adds **Settings > TermuxPP > Properties**, with controls for every supported
app property: boolean choices, enumerated choices (including gesture and volume-key
actions and extra-key styles), and guided text dialogs for numeric values, colors,
paths and key sequences. Text dialogs explain formats, ranges and defaults, use
appropriate keyboards, and keep invalid input open for correction. Extra keys have
a full-width live keyboard preview using the terminal's own extra-key renderer,
including the configured key style, capitalization, active colors, corner radius
and row height. Tap a preview key to edit it without sending terminal input.
Compact row controls add, remove or reorder rows and keys; choose common keys or
literal text; and configure labels, macros and swipe-up actions without writing
JSON. An optional **Advanced JSON** editor preserves support for complex layouts. Existing custom key attributes are retained. Layout changes remain a draft
until **Save**, and **Cancel** leaves the file unchanged.

These controls edit the properties file itself, not a competing Android preference
store. Clear a text field or select **App default** to remove an override; the layout
editor has **Restore default layout** followed by **Save**. Settings such as
transcript size may require a new session or an app restart.

The canonical file is **`~/.config/termuxpp/termuxpp.properties`**. Reads use the
first existing file in this order, without merging:

1. `~/.config/termuxpp/termuxpp.properties`
2. `~/.config/termuxpp/termux.properties` (previous fork filename)
3. `~/.config/termux/termux.properties`
4. `~/.termux/termux.properties`

On the first GUI change, the selected legacy file is copied into the canonical
file before applying the change. The legacy original is retained. Comments and
unknown properties are preserved; updates use an atomic write. Once the canonical
file exists, it takes precedence even when an override is reset. Shell edits and
`termux-reload-settings` continue to work. The adapted first-run profile script
creates the new canonical filename only when none of the supported files exists.

This is an intentional fork difference. Upstream retains its `termux.properties`
filename and legacy configuration locations. Sources: [upstream constants](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java),
[fork constants](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/TermuxConstants.java),
[properties GUI](../../app/src/main/java/com/termux/app/fragments/settings/termux/PropertiesPreferencesFragment.java),
[file editor](../../app/src/main/java/com/termux/app/settings/TermuxPropertiesEditor.java),
and [helper adaptation rules](../../app/src/main/resources/termuxpp/helper-rewrites.tsv).

## Scope

The TermuxPP core APK contains the API, Tasker, Boot, Styling, Widget, and Float
components under one application ID and shared UID. The `termux-am` helper APK
is an internal runtime artifact loaded with `app_process`, not an installable
companion. Stock Termux packages, APKs, and Android helpers remain a separate
family. The path redirect and unchanged Tasker protocol preserve specific
compatibility points; neither makes the two app identities interchangeable.

## Nix guest services settings

When the managed Nix guest is available, **Settings > TermuxPP > Services** lists
its declared services and their enabled and running state. The page can start,
stop, restart and view bounded logs for a service. Actions call the guest's `svc`
interface with argument vectors; service names are validated before use. If the
guest has not been provisioned or is unavailable, the page reports that state and
offers a refresh. Sources: [services settings](../../app/src/main/java/com/termux/app/fragments/settings/termux/ServicesPreferencesFragment.java)
and [preferences entry](../../app/src/main/res/xml/termux_preferences.xml).

## Terminal feedback position

TermuxPP can place terminal feedback toasts, including session-switch
indicators, in any of nine positions through `terminal-toast-position`.
The default is top center.

## Reloading settings

The `tpp-session reload-settings` command reloads properties from disk and
refreshes terminal styling in the app.

## Terminal session CLI

TermuxPP ships `tpp-session` to list, focus, rename, reorder, close,
and kill app terminal sessions. It uses stable session IDs and a local
socket restricted to the app's Android UID. Every command accepts `--json` (also as `tpp --json session ...`): `list` prints an array and the other commands a JSON object that echoes the accepted request. See [Session commands](terminal-settings.md#session-commands).

The command is Rust, in [`tpp-session`](../../pkgs/tpp-session), a library without third-party
dependencies that the single `tpp` binary links in: `tpp session ...` and `tpp-session ...` (a link to
`tpp`, which runs the session group when its program name is `tpp-session`) call the same
[`cli::run`](../../pkgs/tpp-session/src/cli.rs). It replaces a Java class started through `app_process` and
a Python script for the Nix guest, so the native prefix needs no Python, JVM or other package, and a
call is one small native process. The app installs `tpp`, the `tpp-session` link and the completions
through the [native CLI installer](../../app/src/main/java/com/termux/app/nativecli/NativeCliInstaller.java)
([manifest](../../app/tpp-native.gradle)); [`TermuxService`](../../app/src/main/java/com/termux/app/TermuxService.java)
first removes the launcher, script and completion files that earlier versions wrote, because the installer
leaves paths it did not create alone.

The app's side is unchanged: one JSON array line to the abstract socket `dev.pschmitt.termux.sessions`
([`SessionCommandServer`](../../app/src/main/java/com/termux/app/SessionCommandServer.java)). `TPP_SESSION_SOCKET`
names another abstract socket, which the tests use. Commands, options, abbreviations, exit statuses (0, 1, 2),
usage errors, the JSON layout and the table behave as the Python implementation did; the differences are
that help text is wrapped at 80 columns whatever the terminal width, that arguments that are not UTF-8
are shown with U+FFFD instead of being rejected, and that a reply that is not a JSON object is an
error message instead of a traceback. The completion scripts are part of the binary
(`tpp-session completions SHELL` prints them), so the output no longer depends on files in the prefix.

## Command actions for taps and extra keys

The TermuxPP Properties editor can assign `run-command` to single, double or triple taps and to
the extra-keys row swipes. It can also run commands from individual extra-key JSON objects using a
`command` field. Each action opens a new visible terminal session and runs the configured text as
one shell `-c` argument, preserving the current session. `gesture-command-context` selects the
normal `nix-session` behavior, always enters the managed Nix guest, or uses the outer Termux prefix.
Command strings are redacted from property and execution logs. Sources: [gesture dispatcher](../../app/src/main/java/com/termux/app/terminal/GestureActionDispatcher.java),
[extra-key parser](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/extrakeys/ExtraKeyButton.java),
[properties editor](../../app/src/main/java/com/termux/app/fragments/settings/termux/PropertiesPreferencesFragment.java),
and [gesture documentation](terminal-settings.md#tap-and-swipe-actions).

Configured tap and volume-button actions briefly pulse an outline inside the
terminal. The extra-keys swipe gives feedback by sliding the key row. Both
respect Android's animation setting. [Gesture feedback implementation](../../app/src/main/java/com/termux/app/terminal/GestureFeedback.java).

Upstream Termux opens the toolbar's text input by dragging the extra keys row
from right to left. In TermuxPP both swipe directions over the extra keys row
are configurable actions, and the right-to-left swipe switches to the next
session by default. Opening the text input is the `toggle-text-input` action,
which any gesture can run. Set `extra-keys-swipe-right-action = toggle-text-input`
for the upstream behavior. Swipes follow the finger like list swipe actions
(Gmail, Todoist), and each direction can also have a long swipe; the strip the row
uncovers shows which action releasing runs. Sources: [swipe pager](../../app/src/main/java/com/termux/app/terminal/io/ExtraKeysSwipeViewPager.java),
[swipe detector](../../app/src/main/java/com/termux/app/terminal/io/ExtraKeysSwipeDetector.java)
and [extra keys swipe documentation](terminal-settings.md#extra-keys-swipe).

## Wake lock

Upstream Termux's wake lock also holds a high-performance Wi-Fi lock and is kept until it is
released. In TermuxPP the Wi-Fi lock is opt-in (`wake-lock-wifi`), `wake-lock-timeout` can release
a forgotten wake lock by itself, the notification shows for how long it has been held, and the
terminal menu can toggle it. `termux-wake-lock` and `termux-wake-unlock` work as upstream. Sources:
[service](../../app/src/main/java/com/termux/app/TermuxService.java) and
[wake lock documentation](terminal-settings.md#wake-lock).

## Recursive Settings search

Settings search indexes the reachable preference screens and the dynamically generated TermuxPP
properties. A result shows its settings path; selecting it opens the owning screen, scrolls to the
preference and briefly highlights that row. Search indexes preference labels and help text, never
configured property values. Sources: [search index](../../app/src/main/java/com/termux/app/settings/SettingsSearchIndex.java)
and [Settings navigation](../../app/src/main/java/com/termux/app/activities/SettingsActivity.java).

Set `create-session-on-single-session-switch=true` in `termuxpp.properties` to
create and open a new terminal when previous or next session is invoked with
only one terminal open. This applies to keyboard shortcuts and configured
gestures, including the extra-keys swipe. The default is to keep the current session.

Each `shortcut.create-session`, `shortcut.next-session`, `shortcut.previous-session` and
`shortcut.rename-session` property accepts `disabled` (case and surrounding spaces ignored) to
switch that shortcut off without a parse error. Sources:
[parser](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/settings/properties/TermuxSharedProperties.java)
and [validator](../../app/src/main/java/com/termux/app/settings/PropertyInputSpec.java).

The ADB shell listener optionally restricts its availability to configured
Wi-Fi SSIDs. See [ADB shell Wi-Fi restriction](integrations.md#adb-shell-wi-fi-restriction).
