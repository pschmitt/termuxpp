# TermuxPP Android command helper

This fork defaults the Android caller identity to `dev.pschmitt.termux`.
The Java namespace remains `com.termux.termuxam`: the APK is loaded by
`app_process`, not installed as an Android companion app. The upstream
`@TERMUX_APP_PACKAGE@` build-time substitution remains supported.

With JDK 17 and Android SDK/build-tools 35, build on a remote worker. From the
monorepo root, use the integrated Gradle project:

```sh
./gradlew :termux-am:assembleRelease
```

The unsigned helper APK is at
`android/components/termux-am/app/build/outputs/apk/release/termux-am-release-unsigned.apk`.
This repository no longer packages the helper as a Debian package. The nixpp
guest provides the `am` launcher (`pkgs/nixpp/android-api.nix`). Do not install
the helper APK with Android's package installer: it is loaded by `app_process`,
and Android 14+ requires it to be read-only.
