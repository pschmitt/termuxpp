# TermuxPP API command-line tools

This fork addresses `dev.pschmitt.termux.api` and uses its matching Unix socket.
The native fallback prefix is `/data/data/dev.pschmitt.termux/files/usr`.
The start/stop helpers address TermuxPP:API, not stock Termux:API.

Cross-build with the Android NDK for AArch64:

```sh
cmake -S . -B build-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_INSTALL_PREFIX=/data/data/dev.pschmitt.termux/files/usr
cmake --build build-android
DESTDIR="$PWD/staging" cmake --install build-android
```

This installs the platform bridge (the C client of the socket protocol,
`libtermux-api` and `termux-callback`) and the completions; there are no command
scripts. The `tpp` executable itself is the Rust workspace at the repository root
(`cargo build --release -p tpp`, crate `tpp-cli`, with the session control `tpp-session`
and the API backends `tpp-api` linked in); its command table is
`tpp-cli/src/commands.rs`, which a test keeps in step with the commands of
`tpp-api`. Every command runs in the binary. `tpp-api/tests/oracle` keeps a copy
of the former shell scripts, which the Rust commands are tested against.

Run compilation on a remote build worker. Install the staged prefix contents
into TermuxPP using a controlled provisioning step. Do not install these tools
into stock Termux or mix them with the upstream `termux-api` package. This
repository no longer builds a Debian package of them; the nixpp guest provides
the `tpp` commands (`pkgs/nixpp/android-api.nix`): it builds this directory
with CMake, points the bridge and `tpp api start|stop` at the guest `am`,
and wraps `tpp` so it reaches the app through the guest `am` and converts guest
paths (`/android/...`, `/home`, `/nix`) to Android paths (`TPP_API_AM`,
`TPP_API_PATH_CMD`). The build fails if a command lacks safe `-h` help or if
the bridge keeps a stock-prefix reference.
Battery status, cold-start broadcast fallback, and toast delivery were tested
successfully on a device.

The native prefix gets the same commands from the app: `app/tpp-native.gradle`
packs `termux-callback.in` and the completions into the APK
assets, builds `termux-api-broadcast` with the NDK as
`libtermux-api-broadcast.so` and the Rust `tpp` executable (workspace crate
`tpp-cli`, with the session control and `tpp-api` linked in) as `libtpp.so` (only `lib*.so`
files are extracted from an APK). `bin/tpp` and `bin/tpp-session` are links to
that one executable. At app and session start
`NativeCliInstaller` copies the assets into the prefix (replacing
`@TERMUX_PREFIX@`) and links `libexec/termux-api-broadcast` to the library, so an
app update updates the CLI. It writes only paths that are absent or that it
installed unchanged before (digests in `var/lib/tpp/native-install.json`), and
reports files of Debian packages, edited files and stale `termux-api` or
`termux-am` dpkg entries without touching them or the dpkg database. The
client still runs `PREFIX/bin/am`, which the APK does not ship: keep an existing
`termux-am` installation.

Install the [TermuxPP app with its built-in API](../../docs/en/integrations.md#api) and
sign it with the same certificate as TermuxPP. Test `tpp battery`,
`tpp api start`, and `tpp api stop` on-device. The API socket's same-UID
check remains enabled.
