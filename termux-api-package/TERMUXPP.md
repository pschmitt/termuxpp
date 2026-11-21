# TermuxPP API command-line tools

This fork addresses `dev.pschmitt.termux.api` and uses its matching Unix socket.
The native fallback prefix is `/data/data/dev.pschmitt.termux/files/usr`.
The start/stop helpers address TermuxPP:API, not stock Termux:API.

Cross-build with the Android NDK for AArch64:

```sh
cmake -S . -B build-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_INSTALL_PREFIX=/data/data/dev.pschmitt.termux/files/usr \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5
cmake --build build-android
DESTDIR="$PWD/staging" cmake --install build-android
```

Run compilation on a remote build worker. Install the staged prefix contents
into TermuxPP using a package or controlled provisioning step. Do not install
these tools into stock Termux or mix them with the upstream `termux-api` package.
Use the monorepo's [`scripts/package-helpers.py`](../scripts/package-helpers.py)
with `--api-source termux-api-package` and the matching [TermuxAm helper](../termux-am/TERMUXPP.md)
to generate device or bootstrap debs. The generated `termux-api` package includes
the original `termux-*` command scripts with the fork prefix in their shebangs
and API invocations. After device installation, run
`apt-mark hold termux-am termux-api` to prevent upstream upgrades from
overwriting the fork-specific helpers. See the core's [AArch64 bootstrap
guide](../docs/en/aarch64-bootstrap.md) for the complete procedure.
Battery status, cold-start broadcast fallback, and toast delivery were tested
successfully on a device.

The native prefix gets the same commands from the app: `app/tpp-native.gradle`
packs `tpp.in`, the command scripts, the tables, `termux-callback.in` and the
completions into the APK assets and builds `termux-api` and
`termux-api-broadcast` with the NDK as `libtermux-api-broadcast.so` (only
`lib*.so` files are extracted from an APK). At app and session start
`NativeCliInstaller` copies the assets into the prefix (replacing
`@TERMUX_PREFIX@`) and links `libexec/termux-api-broadcast` to the library, so an
app update updates the CLI. It writes only paths that are absent or that it
installed unchanged before (digests in `var/lib/tpp/native-install.json`), and
reports files of Debian packages, edited files and stale `termux-api` or
`termux-am` dpkg entries without touching them or the dpkg database. The
client still runs `PREFIX/bin/am`, which the APK does not ship: keep an existing
`termux-am` installation.

Install the [TermuxPP app with its built-in API](../docs/en/integrations.md#api) and
sign it with the same certificate as TermuxPP. Test `termux-battery-status`,
`termux-api-start`, and `termux-api-stop` on-device. The API socket's same-UID
check remains enabled.
