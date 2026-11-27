# Agent Instructions

## Android builds on remote workers

Run Gradle on a remote worker inside the repository's Android dev shell (`nix develop`, also available as `nix develop .#android`); do not invoke `./gradlew` outside it. The shell provides JDK 17, Gradle, Android platforms 35/36, build-tools 35.0.0/36.0.0 and NDK 29.0.14206865, sets `ANDROID_HOME`/`ANDROID_SDK_ROOT`, and points AGP at the SDK's Nix-provided `aapt2` through `GRADLE_OPTS`. Example from the repository root on the worker:

```sh
nix develop --accept-flake-config -c ./gradlew --no-daemon \
  :termux-shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Maven Central often rate-limits (HTTP 429) the shared egress addresses of remote workers, and a retry from the same address keeps failing for a while. The unit tests are the usual victims: Robolectric's `nativeruntime-dist-compat` and its `android-all-instrumented` runtimes. On a fresh worker, before the first Gradle run, fetch those artifacts from Google's Maven Central mirror (`https://maven-central.storage-download.googleapis.com/maven2/`) into `~/.m2/repository` and pass an `--init-script` that adds `mavenLocal()` restricted to the `org.robolectric` group (Robolectric reads `~/.m2` at test time on its own). Take the versions from the 429 error or from the Robolectric version in the Gradle files.

A full debug build in this shell compiled the native NDK targets for arm64-v8a, armeabi-v7a, x86 and x86_64 without any NDK patching. Do not treat Java-only tasks as proof the APK build works.

### Keeping the TermuxPP comparison current

[`docs/en/termuxpp-differences.md`](docs/en/termuxpp-differences.md) is the maintained comparison between TermuxPP and upstream Termux. When a change affects runtime behavior, Android package identity, permissions, prefix/bootstrap handling, companion or helper integration, package names, or update feeds, verify the resulting behavior against the current source and upstream, then update the comparison in the same change. Keep compatibility behavior distinct from intentional fork differences and refresh source links when implementation moves.
