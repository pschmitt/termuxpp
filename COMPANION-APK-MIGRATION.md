# TermuxPP companion consolidation

This note records the one-APK migration implementation and the evidence still
needed before calling companion behavior complete. A merged APK does not by
itself prove that API commands and Tasker executions use the managed Nix guest.

## Current layout

The repository includes Gradle projects for API, Tasker, Boot, Styling, Widget,
Float and TermuxAm in `settings.gradle`. The six companion feature modules now
use `com.android.library` and are direct dependencies of the core application.
TermuxAm remains a separate helper APK. The installable application id is
`dev.pschmitt.termux`; library namespaces preserve the feature class names.

API socket dispatch now starts in the core package, and API, Tasker, Widget and
Styling component launchers are being retargeted to that package. Initialization
for API, Tasker, Widget and Float is called by the core `TermuxApplication`.
The embedded API share provider uses the distinct
`dev.pschmitt.termux.api.sharedfiles` authority and matching permission so the
core APK can update while the former standalone API package remains installed.
Feature preferences retain their old filenames and are copied once from each
installed standalone companion when the destination is empty. Tasker continues
to expose plugin components, but old Tasker profiles may still refer to the
former standalone package id; automatic profile rewriting is not implemented.
`ExecutionIntegrationTest` now runs as app-level instrumentation against the
final APK. It sends a Tasker plug-in fire intent through the merged receiver,
launches a script from the canonical Tasker directory, enters the active Nix
guest through `nixpp`, and checks returned stdout, stderr, and exit status. It
must still be run on a prepared device; compiling the instrumentation APK does
not prove the guest is present or ready.

The [`nix-community/nix-on-droid-app` source tree](https://github.com/nix-community/nix-on-droid-app) is a Termux core fork. Its
top-level tree does not contain separate API or Tasker projects or a one-APK
companion implementation. I found no droid-nix companion merge to carry over.

## Migration work

1. Build and inspect the merged manifest. Check all modules' permissions,
   authorities, receivers, services, activities and resource overlays for
   collisions, including API providers and Tasker plugin metadata.
2. Complete retargeting for every explicit component reference. Preserve API
   command names, the Locale/Tasker protocol, caller permission checks,
   `allow-external-apps` behavior, and API permission prompts. Do not weaken
   core `RUN_COMMAND` authorization.
3. Test upgrades with old standalone companions installed. Verify preference
   migration and decide the user-facing cleanup path for old packages. Confirm
   the required Tasker profile re-selection/save flow on real Tasker.
4. Make API commands and Tasker executions enter the managed Nix session for
   both interactive app terminals and SSH sessions. Preserve stdout, stderr,
   stdin, arguments, environment, and exit status. The terminal's Nix shell
   setup alone does not prove background Android services use that environment.
5. Confirm the self-updater and release workflow publish and install one APK
   containing all companion components. TermuxAm remains a separate internal
   helper APK, not an installable companion app.
6. Inventory every companion before closing the migration. The known Android
   apps are API, Tasker, Boot, Styling, Widget, and Float. TermuxAm is a separate
   command-line compatibility helper, not an Android app, so it remains part of
   the guest package/bootstrap work unless its role is deliberately replaced.

## Acceptance checks

- Build the core APK and inspect its merged manifest. Confirm every expected
  component is present, package authorities are unique, and permissions remain
  feature-specific and correctly protected.
- Install or upgrade one APK on a disposable Android test target. Verify the
  core terminal and every bundled settings or launcher entry point open. Verify
  no separate companion package is required for API, Tasker, Boot, Styling,
  Widget, or Float behavior.
- From the app terminal and an SSH session, run an API command such as
  `tpp battery` and check its structured response. Include one
  permission-gated API command and cold-start the API service after force-stop.
- From Tasker, discover the bundled plugin and execute a foreground and a
  background script through the managed Nix session. Check arguments, stdin,
  stdout, stderr, the actual exit status, and delivery of completion results.
  Confirm an unauthorized caller remains denied.
- Exercise the upgrade with old standalone API and Tasker packages installed.
  Verify preferences migrate and confirm the exact Tasker profile repair steps.
- Exercise Boot, Styling, Widget, and Float individually, including their
  Android component discovery and permission behavior.
- Run the app's relevant unit and instrumentation tests, then verify the exact
  release APK artifact list contains one application APK per intended ABI
  variant and no separate companion APKs.

## Tasker guest integration test

Build the application and its app-target instrumentation APK with the custom
bootstrap archive:

```sh
TERMUX_BOOTSTRAP_AARCH64_ZIP=/path/to/bootstrap-aarch64.zip \
TERMUX_BOOTSTRAP_AARCH64_SHA256=<archive-sha256> \
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Install both APKs on a prepared test device, then run the focused test:

```sh
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class dev.pschmitt.termux.tasker.ExecutionIntegrationTest \
  dev.pschmitt.termux.test/androidx.test.runner.AndroidJUnitRunner
```

The device needs the nixpp guest's `tpp` and `am` helpers,
the native `nixpp` executable and seed assets, and an active guest generation
with `/run/current-system/sw/bin/env`. The integration script lives inside
`~/.config/termuxpp/tasker/`, so it does not require `allow-external-apps`.
The instrumentation test sends from the app's own UID. A real Tasker host must
also be granted `com.termux.permission.RUN_COMMAND`; that external-caller
permission is not exercised by the self-sent test intent.
