---
page_ref: /docs/apps/termux/integrations.html
---

# Built-in integrations

The TermuxPP APK includes **API, Tasker, Boot, Styling, and Widget**.
Open **Settings → Integrations** for their available controls and diagnostics.

## API

API receivers and services run in TermuxPP. Shell commands such as
`tpp battery` use the TermuxPP build of the API command-line tools,
provided by the nixpp guest. See [Android helpers](aarch64-bootstrap.md#android-helpers).
Grant the Android permissions required by the commands you use. Expiring
shell notifications display a countdown in the Android notification shade.
Critical notifications stay visible until dismissed.

## Tasker and Locale

In Tasker, add a plug-in action and select the TermuxPP entry provided by the
main APK. Grant Tasker the `com.termux.permission.RUN_COMMAND` permission in
Android settings. The built-in plug-in keeps the Termux Tasker/Locale intent
protocol and uses TermuxPP's properties, including the managed guest's
properties when available.

Put scripts in `~/.config/termuxpp/tasker/`. Relative names also resolve in
`~/.config/termux/tasker/` and `~/.termux/tasker/`, in that order. Commands
outside these directories require `allow-external-apps=true`. Keep this
disabled unless an automation needs it. The plug-in can return command output
and exit status. Its **Kill TermuxPP** action stops shells and the app, with an
optional relaunch.

## Boot, Styling, and Widget

| Integration | Where to use it |
| --- | --- |
| **Boot** | Open **Settings → Services → Run at boot** to see the scripts Android will schedule. Tap the info icon for setup and an example. Put scripts in the native TermuxPP home's `~/.config/termuxpp/boot/`. TermuxPP schedules them in filename order. For duplicate names, that directory takes precedence over `~/.config/termux/boot/` and `~/.termux/boot/`. |
| **Styling** | Open **Settings → Display** to choose a font and color scheme. See [Terminal settings](terminal-settings.md#configuration-files) for custom files. |
| **Widget** | Add the TermuxPP widget from your launcher. Put terminal shortcuts in the native home's `~/.shortcuts/` and background tasks in `~/.shortcuts/tasks/`. |

API, Tasker, and widget log levels are in **Settings → Debugging → Integration logging**.

## Migrating from standalone companions

Update the main TermuxPP APK in place with a build signed by the same
certificate. On startup, eligible companion preferences are copied once from
installed legacy packages into empty TermuxPP preference files. Reopen and save
Tasker actions against the built-in plug-in when they still reference a legacy
package. Test your actions and recreate launcher widgets before removing legacy
apps through Android settings. [APK updates](self-update.md).

## Component sources

The Gradle projects are linked into the main APK. Build the app from the
repository root using the [Android build instructions](../../AGENTS.md).
The native API tools and the TermuxAm helper have their own build instructions.

| Component | Source |
| --- | --- |
| API | [Android component](../../android/components/termux-api/) and [CLI tools](../../pkgs/tpp-api-bridge/TERMUXPP.md) |
| Tasker | [Plug-in component](../../android/components/termux-tasker/) |
| Boot | [Boot component](../../android/components/termux-boot/) |
| Styling | [Styling component](../../android/components/termux-styling/) |
| Widget | [Widget component](../../android/components/termux-widget/) |
| TermuxAm | [Helper](../../android/components/termux-am/TERMUXPP.md) |

## ADB shell Wi-Fi restriction

Set `adb-shell-allowed-ssids` in **Settings → Properties → Advanced** or in
`termuxpp.properties` to a comma-separated list of exact Wi-Fi names.
The ADB shell listener closes active connections when the network becomes
ineligible and resumes when an allowed SSID is available. With a list set,
an unknown SSID also blocks access. Android may require Location to be on
and TermuxPP to have location permission to read the connected network.

An empty list leaves the ADB shell service available on any network when
enabled. This setting controls the TermuxPP listener. Android's own ADB
debugging and debug-build `run-as` are controlled separately by Android.
