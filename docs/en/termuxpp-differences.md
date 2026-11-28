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
| Android identity and companions | The core uses the `com.termux` application ID and shared user ID; API, Tasker, Boot, Styling, Widget, and Float are separate apps. | The core application ID and shared user ID are `dev.pschmitt.termux`; the Java namespace remains `com.termux`. API, Tasker, Boot, Styling, and Widget are bundled into the core APK. | Intentional fork identity and packaging. Existing standalone companion packages can remain installed while the core APK is updated; the API share provider has its own `.api.sharedfiles` authority to avoid colliding with the old API provider. |
| `RUN_COMMAND` permission | The core declares `com.termux.permission.RUN_COMMAND`. | The core service requires `dev.pschmitt.termux.permission.RUN_COMMAND`. A `com.termux.permission.RUN_COMMAND` alias is also declared for Tasker/Locale hosts that only request the stock name. | Compatibility alias, with a co-install limitation: Android rejects the duplicate permission declaration if stock Termux and TermuxPP core are installed together. The alias does not replace the fork-specific service permission. |
| Prefix paths | The app data and prefix use the stock `com.termux` identity. | The prefix is under the TermuxPP app data directory. Fork shell environments preload `libtermux-pkg-redirect.so`, which remaps selected hard-coded stock app paths and `LD_PRELOAD` entries for upstream package binaries. | Compatibility layer for stock-path assumptions in packages. It does not change Android package identity, permissions, or API receiver identity. |
| Terminal appearance | [Termux:Styling](https://github.com/termux/termux-styling) is a separate add-on that installs a selected preset as a config file. | The core Settings page previews fonts and palettes. Presets and built-in defaults override file-based styling without editing the files. The configuration-files choice honors guest and legacy paths, including font variants. Palette changes refresh all open terminal sessions. | Bundled presets and integrated Settings are intentional fork behavior. Custom files stay compatible. See [Terminal appearance](terminal-settings.md#terminal-appearance) and [appearance loading](../../app/src/main/java/com/termux/app/terminal/TerminalAppearance.java). |
| Run at boot | [Termux:Boot](https://github.com/termux/termux-boot) is a separate add-on using `~/.termux/boot/`. | Settings → Services lists scripts selected by the bundled receiver from the native TermuxPP home. Android delivers boot broadcasts after the main app has been opened once. | The receiver retains legacy directories and prefers `~/.config/termuxpp/boot/` for duplicates. See [Built-in integrations](integrations.md#boot-styling-and-widget) and [Boot receiver](../../android/components/termux-boot/app/src/main/java/com/termux/boot/BootReceiver.java). |
| Bootstrap archive | The app downloads the pinned upstream bootstrap archive for each supported ABI. | The same upstream archives remain the default. A build may supply a SHA-256-verified custom AArch64 archive; the override does not affect the other ABIs. TermuxPP's own phones use the slim outer archive `termuxpp-outer-bootstrap`: only nixpp, rbw, the seed and `nixpp-setup`, with no upstream packages, dpkg or APT; guest generations keep those files current. | Opt-in TermuxPP build capability. Published APKs use the upstream archive unless the build explicitly supplies the custom inputs. See [Custom AArch64 Bootstrap](aarch64-bootstrap.md) and [`outer-bootstrap.nix`](../../pkgs/nixpp/outer-bootstrap.nix). |
| Nix guest (nixpp) | The default session runs the native `bin/login` shell of the Termux prefix. | When the prefix provides `nixpp` with its launcher, bootstrap seed and session manifest (`$PREFIX/share/nixpp`), the first-start bootstrap overlay also runs the bootstrap second stage and `nixpp --json init`, showing each setup step and package in a live log while the on-screen keyboard is hidden. When the guest is ready, its home supplies app properties and file-based fonts and colors, with native files as fallbacks. The in-app Properties editor writes to that same guest home. The custom bootstrap also installs `nixpp-setup` (`nixpp setup`) and an `rbw` built from the pschmitt fork: it reads the enrollment credentials from Bitwarden, builds the system on a freshly leased rworker (or installs the CI-published one with `--published`), activates it and clones the dotfiles, with no external script. It also works from a guest session, where the invocation is handed to the native side like `nixpp enter --outer` ([`setup.rs`](../../pkgs/nixpp/source/src/setup.rs)), and the session's package seeding no longer prints Nix's "path is not valid" probe errors. Private GitHub flake inputs reach the worker over SSH through the forwarded phone key, not through tokens. `nixpp upgrade --rworker` reuses that lease in the guest when `/etc/nixpp/upgrade.json` has a `lease` section: it builds the latest system on a leased native ARM runner with the phone's key (never an older or cached system; the signed cache is only a faster way to fetch the exact path; the runner uploads what it built to that cache with a short-lived niks3 token the controller issues for the phone's own lease, so the phone fetches from the cache instead of through the controller relay, and a missing token, failed upload or cache miss falls back to copying from the worker, after a silent `nix path-info` probe) and otherwise keeps the `rworker-nix` wrapper ([`upgrade_lease.rs`](../../pkgs/nixpp/source/src/upgrade_lease.rs)). New default sessions then run `nixpp login`, which finishes an interrupted setup and enters the Nix guest. `nix-session=false` keeps the native shell; `nix-user` names the guest user of a new runtime (default `nix`). The failsafe session always starts `/system/bin/sh`. | TermuxPP-only and inactive without these bootstrap contents, so upstream-bootstrap builds keep the stock behaviour. A failed setup is not fatal: the session reports it and continues in the native shell. The guest is started by the in-process seccomp launcher, not proot. A system generation can ship the native nixpp and the launcher (`native/nixpp`, `native/launcher`); after a switch the native daemon installs them, so existing installs receive runtime fixes without a fresh bootstrap. A new launcher replaces the runtime's only after it has run the bootstrap busybox, also when exec'd under the current launcher. The guest's bootstrap busybox and Nix (`bootstrap/busybox`, `bootstrap/nix`: `/bin/sh`, `/usr/bin/env` and the session's fallback `PATH`) move the same way once both ran ([`native_update.rs`](../../pkgs/nixpp/source/src/native_update.rs)). TermuxPP's own files in the native prefix (`rbw`, the seed under `share/nixpp`, `etc/nixpp/setup.conf`) follow `native/prefix.json` of the generation: the daemon replaces a file only when it is absent, identical, or unchanged since it installed it (record in `var/lib/nixpp/prefix-install.json`), keeps the bootstrap's first copy as `<file>.nixpp.bak`, and leaves files changed since then alone; upstream Termux packages stay with `apt` ([`prefix_update.rs`](../../pkgs/nixpp/source/src/prefix_update.rs)). Sources: [`TermuxNix`](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/nix/TermuxNix.java), [`TermuxNixSetup`](../../app/src/main/java/com/termux/app/TermuxNixSetup.java), [`TermuxInstaller`](../../app/src/main/java/com/termux/app/TermuxInstaller.java) and [`TermuxSession`](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/shell/command/runner/terminal/TermuxSession.java); nixpp, its launcher and seed: [`pkgs/nixpp`](../../pkgs/nixpp). |
| Nix generation storage | Upstream Termux has no managed Nix guest. | `nixpp df` shows each retained system generation’s closure size, the size exclusive to that generation, and a total that counts shared store paths once. Sizes use uncompressed NAR bytes. `nixpp --json df` reports exact byte counts. | Exclusive size is measured against the other retained system generations. Other GC roots may still retain those paths. Source: [nixpp](../../pkgs/nixpp/source/src/guest.rs). |
| Nix controller lifecycle | Upstream Termux has no Nix guest controller. | Startup and package replacement publish the current Android library path and start the enrolled nixpp controller. | The matching native nixpp finishes its current request before reopening its socket with libraries from the current APK. Source: [`NixppRuntimeRecovery`](../../app/src/main/java/com/termux/app/NixppRuntimeRecovery.java). |
| Termux:API | API commands address the upstream app and its socket. | The bundled API components run inside `dev.pschmitt.termux`; native tools and the API client target the core receiver, socket, and prefix. The share provider authority is `dev.pschmitt.termux.api.sharedfiles`. | Intentional runtime identity change. The nixpp guest builds this repository's native API bridge and Rust `tpp` executable (not the stock or droid-nix `termux-api` scripts), so `tpp <command> -h` prints detailed help without side effects and guest paths are converted to Android paths. The build validates every command's help and its backend link. Stock `termux-api` tools are not interchangeable. |
| TermuxAm | The stock helper identifies its caller as `com.termux`. | The helper defaults its caller identity to `dev.pschmitt.termux`. Its Java namespace remains `com.termux.termuxam`, because the APK is loaded by `app_process`, not installed as a companion app. | Intentional runtime identity change. Filesystem path redirection cannot change the Android caller identity. |
| APT helper packages | The stock `termux-am` and `termux-api` packages address stock Termux identities. | TermuxPP does not build Debian packages of these helpers. The nixpp guest provides the `tpp` API commands and the `am` launcher for the TermuxPP identity; `tpp-setup-storage` runs the Rust `tpp` executable and sends its storage-permission request through that guest `am`, not the outer prefix's. | Intentional fork difference. Stock `termux-am` and `termux-api` packages remain stock-identity software and are not interchangeable with the guest tools. See [API helper tools](../../pkgs/tpp-api-bridge/TERMUXPP.md), [TermuxAm](../../android/components/termux-am/TERMUXPP.md) and [`android-api.nix`](../../pkgs/nixpp/android-api.nix). |
| Android API commands | Upstream `termux-api` installs one `termux-*` script per command (`termux-battery-status`, `termux-wifi-scaninfo`, ...) in `bin`. | The API tools install one native executable, `tpp` (a Rust program, the crate [`tpp-cli`](../../pkgs/tpp-cli), with the session control and the API backends of [`tpp-api`](../../pkgs/tpp-api) linked in; see [Terminal session CLI](#terminal-session-cli) -- no shell, awk or table file is involved in dispatching or rendering) (`tpp battery`, `tpp wifi scan`, `tpp vibrate`, ...), with bash, zsh and fish completions. The command table (names, descriptions, output class, argument hints and option words) is compiled into the binary, in [`commands.rs`](../../pkgs/tpp-cli/src/commands.rs); every built-in API command runs inside the binary (`tpp_api::has`), talking to the app through the same TermuxAm socket protocol as the C client; no command script is installed or looked for, and there is no fallback to one. `tpp-api/tests/oracle` keeps a copy of every former shell script, and the Rust backends are tested against it on arguments, standard input, output, error output and exit status (the only place the scripts still exist). The Rust backends deliberately differ from the scripts in a few places: option values that contain blanks reach the app as one argument instead of being word-split, `echo` is bash's, messages that bash or util-linux would print (`getopts`, `getopt`, `realpath`, `touch`, `$((...))`) are reproduced by the command and may be worded differently in rare cases, a failing connection to the app exits nonzero instead of hanging, and the `usb` callback runs without the exported `TERMUX_CALLBACK` shell function; each module lists its own deviations under `Deviations:`. Two behaviors that depend on the process rather than on the arguments follow the scripts: the standard input is shared between the API call and the `usb` callback as it was with the C client (the call forwards it to the app while it lasts, and the callback gets what is read after the app's answer, including what the call had not read yet, through a pipe instead of the terminal itself; see [`stdin_split.rs`](../../pkgs/tpp-api/src/stdin_split.rs)), and `tpp sensor` handles SIGINT and SIGTERM like the script's trap (`Caught interrupt.. Finishing...`, then the `cleanup` call when sensors were being listened to, then exit status 128 plus the signal number; a signal that reaches the script alone waited for the stream to end, here it ends the stream at once). While a command holds the interrupt guard ([`sys.rs`](../../pkgs/tpp-api/src/sys.rs)), the native transport polls for the signal instead of blocking in `accept` and `recvmsg`. The transport is tested against stand-in plugins over real abstract unix sockets ([`native_loopback.rs`](../../pkgs/tpp-api/tests/native_loopback.rs): payload, standard input forwarding, `SCM_RIGHTS` descriptor passing and the lone `@` message, 1 MiB binary streams, abstract socket naming and release, failing or missing `am`) and the signal handling in a test binary of its own ([`interrupt.rs`](../../pkgs/tpp-api/tests/interrupt.rs)). Outside the Android app, the commands reach the app through `am broadcast`; the guest wrapper points them at TermuxAm and at the guest-to-Android path conversion with `TPP_API_AM` and `TPP_API_PATH_CMD`. A test checks that the table and the commands of `tpp-api` (`tpp_api::IMPLS`) name the same set, and another that the table and the oracle scripts do. No `termux-*` scripts are installed. Completion goes through a hidden `tpp --complete WORD...`: it suggests commands, actions, the options of each command (and their fixed values, such as `tpp location get --provider gps`) from the built-in option table, `--json` only where the command supports it and only once, and asks a plugin with `PLUGIN __complete WORD...`. A command without a row in the option table only completes `--help` and `--json`. Every command accepts `-h`/`--help`; `--json` prints JSON (colored on a terminal, plain when piped) and refuses binary output (`tpp saf read`, `tpp keystore sign`, `tpp usb open`; the command table can mark a command raw only for some arguments) and non-UTF-8 text instead of corrupting it, but never refuses `--help`. Sensor responses and human-readable location updates are rendered incrementally; with `--json`, continuous sensor readings are a sequence of JSON documents rather than one array, while location updates remain a single JSON array. `tpp usb open COMMAND` runs the callback with the real standard output, so its output is streamed byte for byte (NUL bytes and trailing newlines included, unlike upstream's `termux-usb`, which buffers it in a shell variable), and its exit status is the callback's; the client's own failure messages (`No such device.`, `Permission denied.`, ...) still end with status 1, and so does an answer of the app that carries neither a device nor a message (the callback never ran; upstream reports success). The output is saved in a descriptor the command allocates itself (10 or higher), so descriptors the caller passes down, such as 4, reach the callback untouched, and the callback itself gets only the standard descriptors and the device. `tpp battery FIELD` (for example `tpp battery plugged`, which prints `PLUGGED_AC`) prints one value of the status unquoted, and fails when the field does not exist; upstream `termux-battery-status` takes no operand. Other commands are added as executable plugins from `TPP_PLUGIN_DIR` (default `PREFIX/libexec/tpp/plugins`), which receive their arguments, including `--json`, unchanged. | Intentional fork difference: the command names are not upstream-compatible, so scripts written for `termux-*` must be updated. The native `libexec/termux-api` client, `termux-callback` and the `termux-api` package name are unchanged; they are the platform bridge for programs and scripts that still use the old interface, and `tpp` does not use them. The shell dispatcher and its `awk` renderer are gone (`TPP_AWK` has no effect; no `awk` is needed), so rendering is stricter than the awk version was: it validates the whole document before printing and falls back to the unmodified text only for non-JSON output. The `tpp` crates are licensed GPL-3.0-only (the rest of the API package keeps its license). See [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs), [`render.rs`](../../pkgs/tpp-cli/src/render.rs), [`complete.rs`](../../pkgs/tpp-cli/src/complete.rs) and [`commands.rs`](../../pkgs/tpp-cli/src/commands.rs). |
| Native `tpp` installation | Upstream installs `termux-api` as a Debian package; its files change only through `apt`. | The APK carries the native `tpp` CLI (`bin/tpp`, `libexec/tpp`, `libexec/termux-callback`, the completions and the C `termux-api` client) and installs it into the native prefix at app and session start, so updating the app updates the CLI. The client ships as `libtermux-api-broadcast.so` (Android only extracts `lib*.so` files from an APK) and `libexec/termux-api-broadcast` is a symlink to it. The installer writes only paths that are absent or that it installed before and nobody changed (a SHA-256 record in `var/lib/tpp/native-install.json`), replaces files atomically, and leaves everything else alone, listing it in the app log: files of a Debian package, files you edited, foreign files and directories. It never runs `dpkg` or `apt` and never edits the dpkg database. Stale `termux-api` entries (installed according to dpkg, files gone) are only reported; the stale `termux-am` entry is to be kept, because `bin/am` is still what the client runs. Other native binaries (the Rust session CLI) plug in as `lib<name>.so` entries of the same manifest. | Intentional fork difference. The APK no longer ships `bin/am` or the helper APK, so the native client needs a working `PREFIX/bin/am` that is already installed. Nothing removes the old `termux-api` and `termux-am` packages automatically: remove them with `apt` yourself once nothing needs them. Sources: [`NativeCliInstaller`](../../app/src/main/java/com/termux/app/nativecli/NativeCliInstaller.java), [`DpkgDatabase`](../../app/src/main/java/com/termux/app/nativecli/DpkgDatabase.java), [`NativeManifest`](../../app/src/main/java/com/termux/app/nativecli/NativeManifest.java) and [`tpp-native.gradle`](../../app/tpp-native.gradle). |
| Nix guest `tpp` build mismatch | Upstream has no managed Nix guest CLI. | The APK and guest builds fingerprint the shared Rust sources. Guest `tpp` prints a warning to stderr when it can read the APK fingerprint and it differs from the guest fingerprint. Command stdout and exit status still come from the command. | The APK installs its fingerprint under `$PREFIX/share/tpp/source-version`. Older APKs without that file and shell completion run silently. See [APK staging](../../app/tpp-native.gradle) and the [guest wrapper](../../pkgs/nixpp/android-api.nix). |
| Default API actions | Upstream exposes separate command scripts. | A `tpp` group with an explicit `list` action defaults to that action when invoked without arguments; otherwise it defaults to `get` if present. USB and sensor operations use named actions such as `tpp usb list` and `tpp sensor read`. | This applies to groups in the built-in command table, not plugins or standalone commands. Source: [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs). |
| API permission requests | Upstream reports missing runtime permissions in an `error` field before requesting them. | TermuxPP reports them as `API_ERROR`, so structured `tpp` commands can report a failure instead of treating the response as successful data. The current invocation does not resume after the permission dialog. | This changes the API response field for missing permissions; callers using the underlying bridge directly must account for it. Sources: [`TermuxApiPermissionActivity`](../../android/components/termux-api/app/src/main/java/dev/pschmitt/termux/api/activities/TermuxApiPermissionActivity.java), [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs). |
| Location requests | Upstream requests foreground location permission before starting the location service. | TermuxPP does not reject every request lacking background location permission; Android may still restrict delivery when the app is not in the foreground. A one-shot request that receives no location within 30 seconds returns an API error. | Foreground-only grants can expire, and removing the blanket check does not bypass Android's background restrictions. Source: [`LocationAPI`](../../android/components/termux-api/app/src/main/java/dev/pschmitt/termux/api/apis/LocationAPI.java). |
| Empty API responses | Some upstream API commands return no output. | A successful empty captured response displays `No output` in human mode and `null` with `--json`; an empty location updates array displays `No results` in human mode. | This does not infer whether a request succeeded beyond its exit status; text and binary streams remain unchanged. Source: [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs). |
| Silent API actions | Several upstream API commands return no output on success. | `tpp vibrate`, `toast`, `torch`, notification send/remove/channel, `brightness`, clipboard set, Wi-Fi enable/disable, telephony call, media scan and player play/pause/stop, SMS send, infrared transmit, TTS speak, download, share, and wallpaper print `Request sent` when the API returns successfully without output; `--json` returns a status object instead. | This acknowledges the request, not completion of an asynchronous Android action. Errors and nonempty responses remain unchanged. Source: [`cli.rs`](../../pkgs/tpp-cli/src/cli.rs). |
| Notification listing | Notification access is required to list active notifications. | If notification access is missing, `tpp notification list` returns an error instead of leaving the API client waiting. If the listener is disconnected, the app requests a rebind and waits briefly before returning an error. | Intentional runtime improvement; access must still be granted in Android settings. Source: [`NotificationListAPI`](../../android/components/termux-api/app/src/main/java/dev/pschmitt/termux/api/apis/NotificationListAPI.java). |
| Kill intent | No equivalent; the app can only be exited through its notification or force-stopped. | `RunCommandService` accepts `dev.pschmitt.termux.KILL` (same `RUN_COMMAND` permission and `allow-external-apps` policy). It SIGKILLs all shells and then kills the app process. Boolean extra `dev.pschmitt.termux.KILL_RESTART` schedules a relaunch of the main activity through `AlarmManager`. Tasker hosts cannot hold the fork's `RUN_COMMAND` permission, so the built-in Tasker plug-in has a "Kill TermuxPP" action (with a restart checkbox) that sends the kill from the plug-in's shared UID. | TermuxPP-only addition. The relaunch is best effort: it relied on the app's "display over other apps" grant on the test device, and Android may otherwise block the background activity start. Source: [`RunCommandService`](../../app/src/main/java/com/termux/app/RunCommandService.java), [`TermuxService#actionKill`](../../app/src/main/java/com/termux/app/TermuxService.java). |
| ADB shell | No equivalent TermuxPP endpoint. | Debugging settings explicitly enable an app-private loopback listener on `5522` for enrollment and mTLS PTY, command and management connections. Token-authenticated enrollment requires on-device fingerprint-only approval of each concrete client certificate. Subsequent enrollment preserves the server identity. Both the GUI and authenticated CLI list and revoke individual certificate authorizations; disabling access deletes the token, allowlist, and device TLS material. | The host CLI supports `--mode auto|mtls|run-as`: auto prefers Android `run-as` for debuggable installations and selects mTLS only when Android reports the app is not debuggable. The opt-in mTLS endpoint works without SSH or `run-as`. The host verifies the server certificate and loopback IP identity. The device requires an exact approved leaf-certificate pin, certificate validity/clientAuth usage, and proof of key possession through TLS. The token is required only for enrollment; approved certificate sessions do not prompt for it. CA membership alone never authorizes a client. |
| Tasker and Locale | The plug-in protocol is the stock Termux Tasker/Locale protocol. | The plug-in is bundled into the core APK, keeps the protocol action names, and runs with the core app identity and shared UID. Its external-app policy uses the same properties file as the main app, including the managed guest home. | Protocol compatibility is retained. Tasker must be granted `com.termux.permission.RUN_COMMAND`; existing tasks may need to select the bundled TermuxPP plug-in again because Android stored the former standalone package identity. See [Tasker integration](integrations.md#tasker-and-locale). |
| Updates | Upstream distributes its app through its own release channels. | Settings > Updates shows the installed build, update status, and the next action in one card. It installs the TermuxPP APK through Shizuku after confirmation. The APK contains API, Tasker, Boot, Styling, and Widget. | TermuxPP-only updater. It updates one app and does not alter APT package updates. See [Self-update over Shizuku](self-update.md). |

The public CLI spells out actions such as `tpp dialog list`, `tpp job-scheduler list`, `tpp job-scheduler cancel ID`, `tpp audio stop`, and `tpp saf list DIRECTORY`. Additional actions include `tpp sms conversations`, `tpp media play`, and `tpp location watch`. Invalid volume, NFC and dialog arguments fail with a nonzero exit status. Help and completion use descriptive options such as `--provider`, `--limit` and `--title`. Use `tpp help` to discover commands. A unique prefix selects a command or built-in action, for example `tpp not l` selects `tpp notification list`. Exact names take precedence and ambiguous prefixes print their matches. Help uses terminal colors and respects `NO_COLOR`. Commands that wait for Android, including dialog and location requests, report what they are waiting for on stderr in human mode. `--json` keeps progress off the output. If Android denies notification listener access, `tpp notification list` points Nix guest users to `tpp permissions grant notification-access`.

## Guest SSH handoff

The native guest service manager can keep a retiring service launcher alive
while it starts the launcher for a newly selected generation. This lets an
established guest SSH session keep its generation and logger while the new
listener accepts connections. The guest service configuration coordinates
release of the listeners. [Native service manager](../../pkgs/nixpp/source/src/services.rs)

## Termux APT packages in the Nix guest

On aarch64, the TermuxPP flake exposes package names from a pinned snapshot of
the upstream Termux main, X11, and root APT repositories. From the guest, use
`nix shell` to run tools from that snapshot.

```sh
nix shell 'github:pschmitt/termuxpp#termuxPkgs.htop' -c htop
```

The `termuxPkgs.` namespace selects packages from the Termux APT catalog.
For the shorter spelling, add a `termuxPkgs` alias. In a managed guest, point
the alias at the locked TermuxPP flake input with
`nix.registry.termuxPkgs.flake = inputs.termuxpp`. If you manage your registry
directly, add a GitHub alias with the Nix CLI.

```sh
nix registry add termuxPkgs github:pschmitt/termuxpp
nix shell 'termuxPkgs#htop' 'termuxPkgs#jq'
```

The importer unpacks the selected SHA-256-pinned `.deb` and the dependencies
APT resolved for it. It relocates ELF library search paths and launches native
executables with Android's linker from the guest. The package payload stays in
the Nix store and packages do not run APT install scripts. Bash skips its
startup files because they can assume the native prefix. Library-only packages
provide a store payload. Packages that require install scripts, native app
integration, a writable prefix, or other embedded paths may need adaptations.

To refresh the catalog, use a Linux environment with `python3-apt` and a
trusted Termux APT keyring.

```sh
python3 pkgs/termux-packages/update-catalog.py /path/to/termux-keyring.gpg pkgs/termux-packages/catalog.json
```

[Nix importer](../../pkgs/termux-packages/default.nix) ·
[Termux package repository](https://github.com/termux/termux-packages)

## Bootstrap tools and garbage collection

The managed Nix runtime registers permanent store roots for its static BusyBox
and Nix. BusyBox supplies the guest's `/bin/sh` and `/usr/bin/env`, and static
Nix can run in bootstrap mode. Runtime startup also registers these roots for
existing installs. Before pruning generations, `nixpp gc` tries to restore any
missing bootstrap store paths from the public cache and checks both guest
entry points. [Bootstrap preparation](../../pkgs/nixpp/source/src/bootstrap.rs)
and [garbage collection](../../pkgs/nixpp/source/src/gc.rs) implement this
TermuxPP runtime behavior. Bare `tpp toast` in a terminal shows how to supply a message. Text commands explain how to supply input when stdin is a terminal and read redirected or piped input to EOF. Toast rejects empty input. Narrow help stacks command descriptions beneath command names. Captured JSON failures use an error object with a message and exit code. An empty response from a read command is an error, while an empty list is a valid result. Bundled plugins can supply a neighboring `.actions` manifest for prefix matching.

For cache-backed upgrades, `nixpp upgrade` reports the verified target system
path on stderr before copying it. The dry run reports the same target, and
`--json` keeps the machine-readable result on stdout.

`nixpp reapply` reruns the active guest generation's activation and restores
managed files such as `config.yaml`. The controller preserves the
current and previous generation selections. `nixpp hm` invokes the guest's
installed Home Manager CLI. [Guest command reference](../../pkgs/nixpp/source/README.md)

## Session sidebar

Both apps use an edge drawer for switching sessions. TermuxPP presents it as a
rounded session shelf with a highlighted current row and a lighter scrim over
the terminal. The session list settles into place as the drawer opens. The
optional `open-session-switcher` gesture action opens a compact session picker.
The session picker and the tmux window picker reuse the sidebar rows: numbered
badges, the filled current session, and tmux windows in the configured sidebar
layout and style. They open at the top of the screen by default;
`terminal-picker-position` also offers the middle of the screen or of the
terminal view ([Picker position](terminal-settings.md#picker-position)).
The pickers can also open full screen, globally, per picker, or per
`tpp ui ... --fullscreen` call.
[Upstream drawer](https://github.com/termux/termux-app/blob/master/app/src/main/res/layout/activity_termux.xml) ·
[TermuxPP shelf](../../app/src/main/res/layout/activity_termux.xml)

Upstream opens the rename dialog when a session is long-pressed. TermuxPP opens
session actions instead, including rename, reorder and close; holding the session
number picks the row up: it follows the finger, the other rows make room as it
passes them, the list scrolls near its top and bottom edges, and a cancelled
gesture puts the row back. The rename dialog starts with the custom session name,
falling back to its terminal title when no custom name is set. Sources: [upstream session list](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java)
and [TermuxPP session list](../../app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java).

## Terminal More menu

Upstream uses Android's context menu for terminal actions. TermuxPP uses a
rounded bottom sheet with a compact session header, a PID beside the title, and
an absolute working directory when available. Hold the directory to copy its full
path or hold the session icon to open the kill confirmation. The sheet includes
a stopped-session badge, boxed Autofill / Open URL / Share shortcuts, and grouped
icon-led rows. Open URL is gray and disabled when the terminal contains no URLs.
Autofill remains conditional on Android support. Keep screen on
and Wake lock stay visible while on and move under **More** while off.
**More** also contains Reset and Kill. Settings is the last row. Tapping Kill retains its
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
and row height. The layout editor is WYSIWYG: tap a preview key to select it
without sending terminal input, and long-press and drag it to move it, also into
another row. A toolbar moves, inserts, duplicates and deletes the selected key, and
a handle beside each row inserts, duplicates, reorders, resizes or removes rows.
Empty rows and spacers stay visible so they can be selected. An inline inspector
edits every key attribute and updates the preview while you type: the tap action
(key, macro, shell command or spacer, with a palette of special keys), label, text
and background colors (swatches or hex), corner label (automatic, custom text or off) and spacing, and the swipe-up,
long-press, double-tap and left, right and down fling gestures, a long-press menu, and app
actions such as opening the sidebar for the tap or any gesture. Undo and redo cover every
change. An optional **Advanced JSON** editor preserves support for complex layouts,
and key attributes the editor does not know are retained. Layout changes remain a
draft until **Save**; **Cancel** asks before discarding them and leaves the file
unchanged. Source: [editor](../../app/src/main/java/com/termux/app/fragments/settings/termux/ExtraKeysEditorDialog.java)
and [key inspector](../../app/src/main/java/com/termux/app/fragments/settings/termux/ExtraKeyInspector.java).

These controls edit the settings file itself, not a competing Android preference
store; the Display screen's accent, color scheme and font are in the same file. Clear a text field or select **App default** to remove an override; the layout
editor has **Restore default layout** followed by **Save**. Settings such as
transcript size may require a new session or an app restart.

The settings file is **`~/.config/termuxpp/config.yaml`**, grouped by topic
(`appearance`, `terminal`, `sessions`, `sidebar`, `keyboard`, `extra-keys`,
`gestures`, `integrations`, `power`), with real booleans, numbers and the extra
keys as a list of rows. The Properties screen is grouped and titled the same
way; the [config.yaml reference](config-yaml.md) lists every setting and the
former names that are still read. A change in the app edits only that setting,
keeps the comments of the file and writes it atomically. A symlinked
`config.yaml` (from dotfiles) is read but not changed by the app. Unknown paths
are shown in a message. Sources: [schema](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/settings/properties/TermuxConfigSchema.java)
and [YAML reader and writer](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/settings/properties/TermuxYamlConfig.java).

TermuxPP no longer reads Java properties settings files. When there is no
`config.yaml`, the app converts the first of these it finds once and renames
it with a `.migrated` suffix:

1. `~/.config/termuxpp/termuxpp.properties`
2. `~/.config/termuxpp/termux.properties` (previous fork filename)
3. `~/.config/termux/termux.properties`
4. `~/.termux/termux.properties`

The deprecated `use-black-ui` becomes `appearance.night-mode`; other keys the
layout does not know are left out and logged. The adapted first-run profile
script still installs the packaged example, which the app then migrates; it
skips that once `config.yaml` exists.

This is an intentional fork difference. Upstream reads `termux.properties` in
the Java properties format from its legacy configuration locations. Sources: [upstream constants](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java),
[fork constants](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/TermuxConstants.java),
[properties GUI](../../app/src/main/java/com/termux/app/fragments/settings/termux/PropertiesPreferencesFragment.java),
[file editor](../../app/src/main/java/com/termux/app/settings/TermuxPropertiesEditor.java),
[YAML layout](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/settings/properties/TermuxConfigSchema.java),
[migration](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/settings/properties/TermuxConfigMigration.java),
and [helper adaptation rules](../../app/src/main/resources/termuxpp/helper-rewrites.tsv).

## Scope

The TermuxPP core APK contains the API, Tasker, Boot, Styling, and Widget
components under one application ID and shared UID. The `termux-am` helper APK
is an internal runtime artifact loaded with `app_process`, not an installable
companion. Stock Termux packages, APKs, and Android helpers remain a separate
family. The path redirect and unchanged Tasker protocol preserve specific
compatibility points; neither makes the two app identities interchangeable.

TermuxPP no longer bundles a floating terminal. Upstream provides Float as a
separate add-on.

## Services settings

ADB shell and guest services can show an ongoing notification while running.
**Settings → Services** controls the default and each service can override it.
The guest configuration's `nixpp.serviceNotifications.enable` sets the initial
default; `services.<name>.notification` overrides it, including for `adb-shell`. The
default is off. ADB changes take effect immediately; guest services apply a
change on restart. Each service has its own Android notification channel.
Notifications require Android notification permission and guest notifications
also require `tpp`. ADB remains a foreground service, sharing the main TermuxPP
foreground notification; Android 13 and later also list active foreground
services in the system task manager. Access approval notifications are separate.
Source: [ADB shell service](../../app/src/main/java/com/termux/app/AdbShellService.java).

**Settings → Services** shows each service's runtime state and whether it starts
on boot. Start, Stop, Restart, and View logs work for both ADB shell and declared
Nix guest services. ADB shell uses the Android listener and its recent Android
logs, and remains available without the guest. Enable ADB shell access under
**Settings → Debugging** before starting it. Stop keeps the enrollment token and
approved clients and suspends automatic startup across reboots. Start or Restart
resumes automatic startup and follows the configured Wi-Fi policy.

Guest actions use the guest's `svc` interface with validated service names.
Upstream [termux-services](https://github.com/termux/termux-services) uses runit
and stores logs under `$PREFIX/var/log/sv/`. The TermuxPP Run at boot section
lists scripts selected by the Android boot receiver from the native TermuxPP
home. Tap the info icon in that section to open the boot setup dialog.
[Services settings](../../app/src/main/java/com/termux/app/fragments/settings/termux/ServicesPreferencesFragment.java).

## Terminal feedback position

TermuxPP can place terminal feedback toasts, including session-switch
indicators, in any of nine positions through `terminal-toast-position`.
The default is top center.

## Reloading settings

The `tpp settings reload` command reloads properties from disk and
refreshes terminal styling in the app. A reload requested while the terminal is
in the background or the screen is off is applied when the terminal is shown
again; upstream's reload broadcast reaches only a visible activity. Sources:
[service](../../app/src/main/java/com/termux/app/TermuxService.java) and
[activity](../../app/src/main/java/com/termux/app/TermuxActivity.java).

## Image paste

The terminal offers image types to the keyboard (`EditorInfo.contentMimeTypes`
and the commit content API), so a keyboard such as Gboard pastes a copied
screenshot, a GIF or a sticker into a session as it does in a messaging app.
**Paste** in the terminal menu takes an image from the Android clipboard too.
Upstream offers no content types and pastes clipboard text only.

A terminal carries only text, so TermuxPP hands the image over as a file
(`~/.cache/termuxpp-clipboard` in the native home, at most 32 MiB) and runs
the guest helper `termuxpp-image-paste`:

- For a session on the phone, the guest's `wl-paste` serves the image, so a
  program that reads images with wl-clipboard, such as Claude Code's Ctrl+V,
  attaches it.
- For a session whose foreground process is an SSH connection (`ssh`,
  `autossh`, or `mosh-client`, whose host is reached with `ssh`), the helper
  puts the image on that host's clipboard with `wl-copy`. It finds the
  connection below the session's shell process and connects again with the same
  destination and connection options (`-p`, `-l`, `-i`, `-J`, `-F`, `-o`,
  `-S`, ...), without forwards or a remote command. ProxyJump helpers,
  `-N`/`-f` tunnels and background connections are not used; the search
  follows pschmitt/tmux-ssh-split.

TermuxPP then sends Ctrl+V to the session. A host without `wl-copy` gets the
file copied to `~/.cache/termuxpp-paste/` instead, and its path is pasted.
Without the Nix guest the handover file's path is pasted. The
`termuxpp-clipboard` guest package also provides `wl-copy` and `wl-paste` on
the Android clipboard for text. Sources:
[keyboard input](../../terminal-view/src/main/java/com/termux/view/TerminalView.java),
[delivery](../../app/src/main/java/com/termux/app/terminal/TerminalImagePaste.java)
and [guest tools](../../pkgs/nixpp/clipboard.nix).

### Reading clipboard images with tpp

Upstream `termux-clipboard-get` reads text. Bare `tpp clipboard get` writes
text when the clipboard holds text and the original bytes when it holds an
image. Use `--text` for text-only scripts or `--json` for the legacy wrapped
text output. Use `--image` to require an image, or `--image-with-mime` to
write its MIME type on the first line followed by the same bytes.

Image reads need the updated app and CLI and Android clipboard access while
TermuxPP has input focus. They fail without writing an image when the clip
is unavailable, marked sensitive, or larger than 32 MiB.
Sources:
[Android clipboard API](../../android/components/termux-api/app/src/main/java/dev/pschmitt/termux/api/apis/ClipboardAPI.java)
and [CLI](../../pkgs/tpp-api/src/cmd/clipboard_get.rs).

## Multi-finger terminal swipes

TermuxPP can bind a two- or three-finger swipe to a terminal gesture action.
Up, down, left, and right are separate bindings. By default, a two-finger
swipe down opens the current terminal’s tmux window picker when available.
The other seven bindings are unbound.
[Gesture settings](terminal-settings.md#tap-and-swipe-actions).

## Remote tmux integration

The [TermuxPP tmux plugin](tmux-plugin.md) shows the windows of any attached tmux session, local or over
SSH or mosh, in the sidebar and window picker, and switches windows from there. tmux reports the windows
to the terminal in a private OSC sequence that the app never draws, and the app selects a window by
typing a key the plugin binds. Hosts need only the plugin; the `tpp tmux attach` helper below remains
for hosts without it. Upstream Termux has no tmux integration.

A shell-launched SSH attachment can publish its remote tmux windows under its
TermuxPP sidebar entry. Tap a window to select it. The row shows that the switch
is pending until tmux confirms the new active window. If confirmation is missing
after three seconds, tap again to retry. Hold the terminal's sidebar entry
and choose **Open window picker**. The sidebar refreshes as the
active window, names, and window list change. Choosing the active window closes
the sidebar without sending another tmux switch. Upstream Termux's sidebar lists Android terminal
sessions. [Upstream sidebar source](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxSessionsListViewController.java).

TermuxPP hides the soft keyboard when the session sidebar opens and tries to
restore it on close if it was visible before. Set `keyboard.hide-on-sidebar`
to `false` to turn this off. [Upstream activity source](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxActivity.java).

Each window row shows an icon for the program in the window's active pane,
like a tmux status bar that maps `pane_current_command` to Nerd Font
glyphs. TermuxPP shows real logos instead (Claude, Codex, Gemini, Neovim,
zsh, Git, Python and others), tinted in their brand colour unless that would
be hard to see on the background. Unknown commands show a terminal icon.
The helper sends the command with each window; an older helper sends none,
and the rows then have no icon.
[Program icons](../../app/src/main/java/com/termux/app/terminal/TmuxProgramIcon.java).

The `sidebar.layout` setting selects nested windows, a
second sidebar level, or separate session and tmux views.
[Sidebar settings](terminal-settings.md#tmux-sidebar-layout).

Each sidebar independently controls whether it shows terminal sessions,
tmux windows, the New session button, and the Keyboard button. A sidebar
that shows tmux windows alone lists them for the current terminal session.
Enable the optional right sidebar with `sidebar.right.enabled` and swipe inward
from the right edge. [Sidebar settings](terminal-settings.md#tmux-sidebar-layout).
Gesture bindings can open either drawer with `open-left-sidebar` and
`open-right-sidebar`. Existing `open-sidebar` bindings open the left drawer.

The bundled `tpp tmux` command uses the terminal's OpenSSH client.
A shell SSH wrapper can replace its interactive SSH invocation with
`tpp tmux attach 'exec tmux new-session -A -s main' -- -t HOST`.
Use `tpp tmux switcher` to open the picker from the active terminal and
a current nixpp runtime for terminals launched inside the guest.

The window picker lists only the window rows, without a title or the
terminal's name.

The shell helper owns the SSH connection. It keeps one remote `sh` open on
that connection to identify the attached tmux client, list windows and select
a window, and falls back to a new channel per command if that shell breaks.
It sends snapshots over the app-UID session socket and keeps a `tmux-wait`
request open there. When you tap a window, the app answers the wait and
shows the switch as pending. The helper selects the window and lists the
windows again in one round trip, so the app can show the confirmed state.
Without a choice the wait ends after 750 ms and the helper looks at tmux again. An app without `tmux-wait` hands the choice back with the next
snapshot instead. The app clears the list when the attachment ends or its
lease expires. The remote host needs tmux and the usual shell utilities. The
command caps the snapshot to fit the session socket's request limit.
[SSH multiplexing](https://man.openbsd.org/ssh_config#ControlMaster) ·
[Rust CLI](../../pkgs/tpp-cli/src/tmux.rs) ·
[TermuxPP attachment state](../../app/src/main/java/com/termux/app/TmuxWindows.java) ·
[Session socket](../../app/src/main/java/com/termux/app/SessionCommandServer.java).

## Terminal session CLI

`tpp session new [--name NAME] [-- COMMAND ARG ...]` creates and selects a
session and prints its ID. Omit the command to start the default shell.
Command arguments are passed separately. The command runs in the configured
default session environment, including the Nix guest when enabled.

`tpp session switcher` opens the TermuxPP session picker from a visible terminal.
`tpp session list` uses the same display-name fallback as the sidebar, including
`Session N` for tabs without a custom name or terminal title. The same socket
powers `tpp ui` commands to show, hide or toggle More, Settings (with an optional
search query), the command palette, the session and tmux pickers and the
sidebars, and `tpp ui clear` to close them all. The command palette (also the
`open-palette` action) searches actions, sessions, tmux windows and settings;
upstream Termux has no palette. `tpp ui picker` shows the lines of its stdin in
the same dialog and prints the one you pick, like fzf. Upstream Termux's sidebar
uses numeric prefixes alongside the session name and terminal title.
[Upstream sidebar source](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java).

TermuxPP ships `tpp session` to list, focus, rename, reorder, close,
and kill app terminal sessions. It uses stable session IDs and a local
socket restricted to the app's Android UID. Every command accepts `--json` (also as `tpp --json session ...`): `list` prints an array and the other commands a JSON object that echoes the accepted request. See [Session commands](terminal-settings.md#session-commands).

The command is Rust, in [`tpp-session`](../../pkgs/tpp-session), a library without third-party
dependencies that the single `tpp` binary links in: `tpp session ...` calls
[`cli::run`](../../pkgs/tpp-session/src/cli.rs). It replaces a Java class started through `app_process` and
a Python script for the Nix guest, so the native prefix needs no Python, JVM or other package, and a
call is one small native process. The app installs `tpp` and its completions
through the [native CLI installer](../../app/src/main/java/com/termux/app/nativecli/NativeCliInstaller.java)
([manifest](../../app/tpp-native.gradle)); [`TermuxService`](../../app/src/main/java/com/termux/app/TermuxService.java)
first removes the launcher, script and completion files that earlier versions wrote, because the installer
leaves paths it did not create alone. Earlier builds also installed `tpp-session` as a second name for
`tpp session`, with completions of its own; the installer removes that link and those completions on update.

The app retries opening the session socket during service startup if the first
bind fails. It accepts one JSON array line on the abstract socket `dev.pschmitt.termux.sessions`
([`SessionCommandServer`](../../app/src/main/java/com/termux/app/SessionCommandServer.java)). `TPP_SESSION_SOCKET`
names another abstract socket, which the tests use. Commands, options, abbreviations, exit statuses (0, 1, 2),
usage errors, the JSON layout and the table behave as the Python implementation did; the differences are
that help text is wrapped at 80 columns whatever the terminal width, that arguments that are not UTF-8
are shown with U+FFFD instead of being rejected, and that a reply that is not a JSON object is an
error message instead of a traceback. `tpp session` is completed by the `tpp` completions, which
ask the binary for the candidates.

## Command actions for taps and extra keys

The TermuxPP Properties editor can assign `run-command` to single, double or triple taps and to
the extra-keys row swipes. It can also run commands from individual extra-key JSON objects using a
`command` field. Each action opens a new visible terminal session and runs the configured text as
one shell `-c` argument, preserving the current session. `sessions.command-context` follows the
normal `sessions.nix.enabled` behavior, always enters the managed Nix guest, or uses the outer Termux prefix.
Command strings are redacted from property and execution logs. Sources: [gesture dispatcher](../../app/src/main/java/com/termux/app/terminal/GestureActionDispatcher.java),
[extra-key parser](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/extrakeys/ExtraKeyButton.java),
[properties editor](../../app/src/main/java/com/termux/app/fragments/settings/termux/PropertiesPreferencesFragment.java),
and [gesture documentation](terminal-settings.md#tap-and-swipe-actions).

An extra key can define a `hold` key independently of its swipe-up `popup`.
It can also bind left, right, and down flings and a double tap, and a long press can open a menu
of up to 12 alternates that the finger slides across, like the accent popup of a phone keyboard.
Any of these, and the tap itself, can send a key, a macro, run a command or run an app `action`
such as `open-left-sidebar`. The horizontal bindings take priority over a row swipe starting on
that key. Only keys with a double tap delay their single tap. Sources:
[long-press menu](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/extrakeys/ExtraKeyMenu.java)
and [key actions](../../app/src/main/java/com/termux/app/terminal/io/TermuxTerminalExtraKeys.java).
A held modifier arms for one keypress and shows its label while armed.
Tap the shared key to cancel the alternate modifier, double-tap to lock it,
or tap locked Shift to return to the primary key.
Upstream uses long presses to lock modifiers or repeat keys. [Upstream extra-key view](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java).

The extra-key toolbar has a separate font preference, appearance themes,
and a configurable row background. Labels can be edited
for the main, long-press, and swipe-up keys. Custom labels keep their case.
The font can be `google-sans`, Gboard's font: the system's Google Sans on a
Pixel, a bundled Google Sans Flex (SIL OFL) elsewhere; `extra-keys.font-weight` sets the
label weight. The `-` key shows a
hyphen-minus; upstream draws it as a horizontal bar (U+2015) in its
[label table](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysConstants.java).
Individual keys can override their text and background colors, add spacing around
the keycap, and show an automatic or custom corner hint. The layout editor can resize each row and
leave blank slots for unassigned keys.
`extra-keys.compact` lowers every row to three quarters of the height that
`extra-keys.height` gives, off by default; `extra-keys.compact: custom` sets an
exact row height in dp with `extra-keys.row-height`.
`extra-keys.auto-hide` (off by default) shows the extra keys only while the soft
keyboard is visible, so both hide and appear together.
[Extra-key appearance](terminal-settings.md#extra-key-appearance).

TermuxPP adds optional extra-key press and release motion. Key preview is the
default, and Key bubble, Press in, Pop, Lift, and Off are available in `extra-keys.animation`.

Configured tap, volume-button and extra-key swipe actions briefly confirm the
chosen action in a pill above the keys. Motion respects Android's animation
setting. Six selectable confirmation effects are available through `gestures.feedback.style`,
with soft flash as the default. `gestures.feedback.position` independently places the gesture pill
at any of nine positions, with bottom center as the default. [Gesture feedback implementation](../../app/src/main/java/com/termux/app/terminal/GestureFeedback.java).

Upstream Termux opens the toolbar's text input by dragging the extra keys row
from right to left. In TermuxPP both swipe directions over the extra keys row
are configurable actions, and the right-to-left swipe switches to the next
session by default. Opening the text input is the `toggle-text-input` action,
which any gesture can run. Set `gestures.extra-keys.swipe.left.action: toggle-text-input`
for the upstream behavior. Any gesture, volume button or action key can also run
`show-extra-keys`, `hide-extra-keys` or `toggle-extra-keys` to show or hide the toolbar,
and `hide-keyboard` to hide the soft keyboard; upstream only toggles the toolbar
with volume up + Q or K and the Keyboard button's long press. The key row follows the finger slightly and a pill
previews the action. The label updates when the gesture reaches the
long-swipe threshold. Sources: [swipe pager](../../app/src/main/java/com/termux/app/terminal/io/ExtraKeysSwipeViewPager.java),
[swipe detector](../../app/src/main/java/com/termux/app/terminal/io/ExtraKeysSwipeDetector.java)
and [extra keys swipe documentation](terminal-settings.md#extra-keys-swipe).

## Wake lock

Upstream Termux's wake lock also holds a high-performance Wi-Fi lock and is kept until it is
released. In TermuxPP the Wi-Fi lock is opt-in (`wake-lock-wifi`), `wake-lock-timeout` can release
a forgotten wake lock by itself, the notification shows for how long it has been held, and the
terminal menu can toggle it. `termux-wake-lock` and `termux-wake-unlock` work as upstream. Sources:
[service](../../app/src/main/java/com/termux/app/TermuxService.java) and
[wake lock documentation](terminal-settings.md#wake-lock).

## Settings and search

TermuxPP groups the Settings home into Terminal, Automation and services, and
App and support. The full-width search field stays in place while editing the
query. Results show their location in Settings. Display offers
the default purple accent, fixed colors, and an adaptive accent that uses
Android wallpaper colors on Android 12 and later. Settings subpages group related
controls. Debugging puts ADB shell access and Wi-Fi status above Logging, with
the full instructions available from Setup and security. Properties uses readable
labels, a quick entry point for Extra keys, and an outlined value editor. Nix
guest services shows a guest status row with refresh and keeps service policy
details with the service actions.
[Upstream Settings home](https://github.com/termux/termux-app/blob/master/app/src/main/res/xml/root_preferences.xml) ·
[TermuxPP Settings layout](../../app/src/main/res/layout/activity_settings.xml)

Settings search indexes the reachable preference screens and the dynamically generated TermuxPP
properties. A result shows its settings path; selecting it opens its editor when available. Other results scroll to the preference
on its settings screen. Search indexes preference labels and help text, never
configured property values. Sources: [search index](../../app/src/main/java/com/termux/app/settings/SettingsSearchIndex.java)
and [Settings navigation](../../app/src/main/java/com/termux/app/activities/SettingsActivity.java).

TermuxPP offers **Terminal engine** in the properties settings. Set
`terminal.engine: libghostty` in `config.yaml` to use libghostty for new
sessions, or `terminal.engine: termux` to select the default Termux engine.
Open a new session after changing the setting or reloading the file.
The libghostty build supports arm64-v8a and uses the existing
TermuxPP view to display Kitty graphics from libghostty. TermuxPP uses the
Termux engine on devices where the native library cannot load. Upstream
Termux uses its own Java emulator.
Scrolling up in a libghostty session keeps older output visible while new
output arrives. Scroll to the bottom to follow live output again.

On the ZF10, fzf 0.75.0 in the Nix guest can segfault in the Go runtime
under either terminal engine. Running `GOMAXPROCS=1 fzf` avoids the crash
in the tested workload.

[TermuxPP emulator](../../terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java)
[Upstream Termux emulator](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java)

Set `sessions.create-on-single-session-switch: true` in `config.yaml` to
create and open a new terminal when previous or next session is invoked with
only one terminal open. This applies to keyboard shortcuts and configured
gestures, including the extra-keys swipe. The default is to keep the current session.

Each `shortcut.create-session`, `shortcut.next-session`, `shortcut.previous-session` and
`shortcut.rename-session` property accepts `disabled` (case and surrounding spaces ignored) to
switch that shortcut off without a parse error. They also take `ctrl+shift+` and a letter, and
`shortcut.create-session` defaults to `ctrl+shift+t`; upstream has no default shortcut and ignores
Shift. Sources:
[parser](../../termux-shared/src/main/java/dev/pschmitt/termux/shared/termux/settings/properties/TermuxSharedProperties.java)
and [validator](../../app/src/main/java/com/termux/app/settings/PropertyInputSpec.java).

The ADB shell listener optionally restricts its availability to configured
Wi-Fi SSIDs. See [ADB shell Wi-Fi restriction](integrations.md#adb-shell-wi-fi-restriction).
