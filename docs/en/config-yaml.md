---
page_ref: /docs/apps/termux/config-yaml.html
---

# config.yaml reference

TermuxPP reads its settings from `~/.config/termuxpp/config.yaml`. This page
lists every setting in the file. [Terminal settings](terminal-settings.md)
explains the larger features (gestures, extra keys, volume buttons, sidebars)
in more detail.

- [The file](#the-file)
- [Value types](#value-types)
- [appearance](#appearance)
- [terminal](#terminal)
- [sessions](#sessions)
- [sidebar](#sidebar)
- [keyboard](#keyboard)
- [extra-keys](#extra-keys)
- [gestures](#gestures)
- [integrations](#integrations)
- [power](#power)
- [Gesture actions](#gesture-actions)
- [Former names](#former-names)
- [Settings outside config.yaml](#settings-outside-configyaml)
- [Migration from termux.properties](#migration-from-termuxproperties)

## The file

- **Location:** `~/.config/termuxpp/config.yaml`. In a provisioned Nix guest,
  the app reads the file from the guest's home first and falls back to the
  native home. `XDG_CONFIG_HOME` does not change this path.
- **Symlinks:** the file may be a symlink, for example into your dotfiles.
  The app reads it, but does not change it: saving a setting in the app then
  shows an error, and you edit the linked file instead.
- **Reload:** the app reads the file at start. After editing it, run
  `tpp settings reload`.
- **Editing in the app:** **Settings → Properties** and **Settings → Display**
  edit the same file. A change edits only that setting and keeps the rest of
  the file, including comments. A missing group is added in the order used on
  this page. If the setting was written under a [former name](#former-names),
  the app replaces it with the current one.
- **Missing settings** use their default. You only need to write the settings
  you change.
- **Invalid values** are logged and replaced by the default. The app still
  starts.
- **Unknown paths** are ignored, logged and listed in a message on screen,
  so a typo such as `apperance:` does not go unnoticed.
- **A YAML syntax error or a duplicate key** rejects the whole file: the app
  shows a message and every setting uses its default until you fix it.

A minimal example:

```yaml
appearance:
  night-mode: system
terminal:
  cursor:
    style: bar
    blink-rate: 600
extra-keys:
  keys:
    - [ESC, {key: /, popup: BACKSLASH}, HOME, UP, END, PGUP]
    - [TAB, CTRL, ALT, LEFT, DOWN, RIGHT, PGDN]
gestures:
  tap:
    double:
      action: open-session-switcher
```

## Value types

| Type | Accepted values |
| --- | --- |
| bool | `true` or `false` |
| int, float | A YAML number inside the range given |
| enum | One of the listed words. Case does not matter. |
| color | `#RGB`, `#RRGGBB` or `#AARRGGBB`. Quote it in YAML (`"#201b29"`), because `#` starts a comment. |
| keys | A key sequence, see [Sending keys](terminal-settings.md#sending-keys) |
| command | Shell text, see [Running commands](terminal-settings.md#running-commands) |
| action | A [gesture action](#gesture-actions) |

The **Properties** screen groups the settings like this page, with a readable
title for each, for example **Triple tap: action** for
`gestures.tap.triple.action`. The search in Settings also finds a setting by
its path and by its key in the former `termux.properties` format.

## appearance

| Path | Type | Default | Description |
| --- | --- | --- | --- |
| `night-mode` | enum: `true`, `false`, `system` | `system` | Dark theme always, never, or following the device. |
| `accent` | enum: `default`, `adaptive`, `blue`, `teal`, `rose` | `default` | Accent color of the app interface. Set on **Settings → Display**. |
| `color-scheme` | `config`, `default` or a preset name | `config` | Terminal colors. `config` reads `colors.properties`, `default` uses the built-in colors. Set on **Settings → Display**, which lists the presets. |
| `font` | `config`, `default` or a preset name | `config` | Terminal font. `config` reads `font.ttf` and its variants. Set on **Settings → Display**. |
| `fullscreen` | bool | `false` | Hide the status bar. |
| `fullscreen-workaround` | bool | `false` | Keep the soft keyboard from covering the terminal in fullscreen mode. It only applies with `fullscreen: true`. |
| `toast-position` | enum: [position](#positions) | `top-center` | Where short terminal messages appear, such as the session number after a switch. |
| `session-change-toast` | bool | `true` | Show the session number and name when you switch sessions. |

## terminal

| Path | Type | Default | Description |
| --- | --- | --- | --- |
| `engine` | enum: `termux`, `libghostty` | `termux` | Terminal engine for new sessions. `libghostty` is only available on ARM64. |
| `margin.horizontal` | int 0–100 (dp) | `3` | Space to the left and right of the text. |
| `margin.vertical` | int 0–100 (dp) | `0` | Space above and below the text. |
| `cursor.style` | enum: `block`, `underline`, `bar` | `block` | Cursor shape. |
| `cursor.blink-rate` | int 0 or 100–2000 (ms) | `0` | Duration of each blink phase. `0` turns blinking off. |
| `transcript-rows` | int 100–50000 | `2000` | Scrollback lines kept per session. |
| `bell` | enum: `vibrate`, `beep`, `ignore` | `vibrate` | Response to the BEL character. |
| `open-url-on-click` | bool | `false` | Open a link when you tap it, without the long-press menu. See [URLs and hyperlinks](terminal-settings.md#urls-and-hyperlinks). |
| `default-working-directory` | path | `~` | Start directory of new sessions. It must exist and be readable. |

## sessions

| Path | Type | Default | Description |
| --- | --- | --- | --- |
| `picker-fullscreen` | bool | `false` | Open the pickers full screen instead of as a dialog. `tpp ui ... --fullscreen` / `--no-fullscreen` overrides it. |
| `pickers.palette.fullscreen` | bool | follows `picker-fullscreen` | The command palette full screen, or not. |
| `pickers.picker.fullscreen` | bool | follows `picker-fullscreen` | `tpp ui picker` full screen, or not. |
| `pickers.session-switcher.fullscreen` | bool | follows `picker-fullscreen` | The session picker full screen, or not. |
| `pickers.tmux-switcher.fullscreen` | bool | follows `picker-fullscreen` | The tmux window picker full screen, or not. |
| `picker-position` | enum: `top`, `center`, `terminal` | `top` | Where the session picker, the tmux window picker and the command palette open. `terminal` centers them on the terminal, above the keyboard. |
| `create-on-single-session-switch` | bool | `false` | With only one session open, next/previous session creates a new one. |
| `command-context` | enum: `session`, `nix`, `outer` | `session` | Where commands run: every `run-command` gesture and every extra key with a `command`. `session` follows `nix.enabled`, `nix` always uses the guest, and `outer` uses the native prefix. |
| `tmpdir-cleanup-days` | int −1–100000 | `3` | When the last session exits, delete files in `$TMPDIR` that were not used for this many days. `-1` keeps every file and `0` deletes all of them. |
| `nix.enabled` | bool | `true` | Start new sessions in the Nix guest when it is available. With `false`, they start the native shell. The failsafe session always starts the native shell. |
| `nix.user` | string | `nix` | User name in the Nix guest: a lowercase letter or `_`, then up to 31 lowercase letters, digits, `_` or `-`. It is read only when the guest is set up. |

## sidebar

| Path | Type | Default | Description |
| --- | --- | --- | --- |
| `layout` | enum: `nested`, `drill-down`, `modes` | `nested` | Layout of a sidebar that shows both sessions and tmux windows. See [tmux sidebar layout](terminal-settings.md#tmux-sidebar-layout). |
| `style` | enum: `rounded`, `subtle` | `rounded` | How the current session and the active tmux window are marked in the sidebars and pickers. |
| `right.enabled` | bool | `false` | Enable the right sidebar, opened by a swipe from the right edge. |

Each sidebar has the same four switches, under `left` and `right`. A sidebar
with tmux windows on and sessions off shows the windows of the current
session.

| Path | Type | Default (left / right) | Description |
| --- | --- | --- | --- |
| `left.show-sessions`, `right.show-sessions` | bool | `true` / `false` | List terminal sessions. |
| `left.show-tmux-windows`, `right.show-tmux-windows` | bool | `true` / `true` | List tmux windows. |
| `left.show-new-session`, `right.show-new-session` | bool | `true` / `false` | Show the **New session** button. |
| `left.show-keyboard-button`, `right.show-keyboard-button` | bool | `true` / `false` | Show the **Keyboard** button. |

## keyboard

| Path | Type | Default | Description |
| --- | --- | --- | --- |
| `back-key` | enum: `back`, `escape` | `back` | What the Back button does when the terminal has focus. |
| `hide-on-startup` | bool | `false` | Do not show the soft keyboard when the app starts. |
| `hide-on-sidebar` | bool | `true` | Hide the soft keyboard while a sidebar is open and show it again when the sidebar closes. |
| `toggle-behaviour` | enum: `show/hide`, `enable/disable` | `show/hide` | What the keyboard toggle does. `enable/disable` keeps the keyboard from popping up until you toggle it again. |
| `char-based-input` | bool | `false` | Send each character as you type. Use it when typed text only appears after Enter, which happens with some keyboards (for example on Samsung devices). |
| `ctrl-space-workaround` | bool | `false` | Fix Ctrl+Space not sending NUL on some devices. |
| `hardware-shortcuts` | bool | `true` | Handle the built-in Ctrl+Alt shortcuts of a hardware keyboard (for example Ctrl+Alt+N for the next session, Ctrl+Alt+F for the command palette). Turn it off so that the keys reach the terminal. |
| `shortcuts.create-session` | `ctrl+X`, `ctrl+shift+L` or `disabled` | `ctrl+shift+t` | Shortcut for a new session. `disabled` removes it. |
| `shortcuts.next-session` | `ctrl+X`, `ctrl+shift+L` or `disabled` | unset | Shortcut for the next session, like `ctrl+2`. |
| `shortcuts.previous-session` | `ctrl+X`, `ctrl+shift+L` or `disabled` | unset | Shortcut for the previous session, like `ctrl+1`. |
| `shortcuts.rename-session` | `ctrl+X`, `ctrl+shift+L` or `disabled` | unset | Shortcut to rename the current session, like `ctrl+r`. |
| `shortcuts.palette` | `ctrl+X`, `ctrl+shift+L` or `disabled` | unset | Shortcut for the command palette. A hardware keyboard also has Ctrl+Alt+F. |
| `volume.mode` | enum: `virtual`, `volume` | `virtual` | `volume` gives both buttons back to Android and ignores `volume.up` and `volume.down`. |
| `volume.up.action` | `modifier` or action | `modifier` | Volume up. `modifier` makes it a virtual Fn key while held. `run-command` is not allowed. |
| `volume.up.keys` | keys | unset | Keys for `send-keys`. |
| `volume.down.action` | `modifier` or action | `modifier` | Volume down. `modifier` makes it a virtual Ctrl key while held. |
| `volume.down.keys` | keys | unset | Keys for `send-keys`. |

See [Volume buttons](terminal-settings.md#volume-buttons) for repeat and
modifier details.

## extra-keys

| Path | Type | Default | Description |
| --- | --- | --- | --- |
| `keys` | list of rows | two rows, see below | The buttons. See [Extra-key appearance](terminal-settings.md#extra-key-appearance). |
| `theme` | enum: `default`, `rounded` | `default` | Look of the button row. |
| `labels` | enum: `default`, `arrows-only`, `arrows-all`, `all`, `none` | `default` | How key names are drawn, for example `LEFT` as an arrow. |
| `height` | float 0.4–3 | `1` | Height of the toolbar as a factor of its normal height. |
| `compact` | `false`, `true`, `custom` | `false` | `true` makes each row three quarters of its normal height, on top of `height`; `custom` makes it `row-height` dp high. |
| `row-height` | int 16–120 (dp) | `28` | Height of each row when `compact` is `custom`. `height` does not apply then. |
| `background-color` | color | theme | Color behind the row. |
| `font` | enum: `system`, `google-sans`, `terminal`, `monospace`, `serif` | `system` | Font of the labels. `terminal` uses the terminal font; `google-sans` is Gboard's font: Google Sans on a Pixel, the bundled Google Sans Flex (OFL) elsewhere. |
| `font-weight` | int 100–900 | font's own | Weight of the labels: 400 regular, 500 medium (like Gboard), 700 bold. |
| `animation` | enum: `key-preview`, `key-bubble`, `press-in`, `pop`, `lift`, `off` | `key-preview` | Effect of a pressed key. |
| `all-caps` | bool | `true` | Show labels in capital letters. |
| `corner-radius` | float 0–32 (dp) | theme | Corner radius of the buttons. It overrides the theme. |
| `active.text-color` | color | theme | Label color of a latched or locked modifier. |
| `active.background-color` | color | theme | Button color while pressed. |

Swipes on the extra keys are gestures, see
[`gestures.extra-keys`](#gestures).

The default `keys`:

```yaml
extra-keys:
  keys:
    - [ESC, /, {key: '-', popup: '|'}, HOME, UP, END, PGUP]
    - [TAB, CTRL, ALT, LEFT, DOWN, RIGHT, PGDN]
```

## gestures

Every gesture binding has three keys. `action` selects what happens, `keys`
is read only for `send-keys`, and `command` only for `run-command`. Swipes are
named by the direction the finger moves.

| Path | Type | Default | Description |
| --- | --- | --- | --- |
| `animations` | bool | `true` | Visual feedback for custom taps, swipes and volume button actions. |
| `feedback.style` | enum: `soft-flash`, `touch-ripples`, `edge-glow`, `light-sweep`, `touch-bloom`, `corner-brackets` | `soft-flash` | Effect shown for a recognized gesture. |
| `feedback.position` | enum: [position](#positions) | `bottom-center` | Where the action label appears. This setting is independent of `appearance.toast-position`. |
| `tap.single.{action,keys,command}` | binding | `show-keyboard` | One tap on the terminal. |
| `tap.double.{action,keys,command}` | binding | `none` | Two quick taps. |
| `tap.triple.{action,keys,command}` | binding | `none` | Three quick taps. |
| `tap.triple.focus-title` | regular expression | unset | Before the triple-tap action runs, switch to a session whose title matches. If no session matches, the action runs. |
| `swipe.{two,three}-finger.{up,down,left,right}.{action,keys,command}` | binding | `none`, except `two-finger.down`: `open-tmux-switcher` | Multi-finger swipes on the terminal. |
| `extra-keys.swipe.right.{action,keys,command}` | binding | `none` | Swipe along the extra keys row, finger moving to the right. |
| `extra-keys.swipe.left.{action,keys,command}` | binding | `next-session` | Swipe along the row, finger moving to the left. |
| `extra-keys.long-swipe.right.{action,keys,command}` | binding | `none` | Longer swipe to the right. |
| `extra-keys.long-swipe.left.{action,keys,command}` | binding | `none` | Longer swipe to the left. |

See [Extra keys swipe](terminal-settings.md#extra-keys-swipe) for the swipe
distances. Example:

```yaml
gestures:
  tap:
    triple:
      action: run-command
      command: htop
      focus-title: '^htop'
  extra-keys:
    swipe:
      right:
        action: previous-session
```

## integrations

| Path | Type | Default | Description |
| --- | --- | --- | --- |
| `allow-external-apps` | bool | `false` | Let other apps run commands through the `RUN_COMMAND` intent. Any app with the permission can then run commands as you. |
| `am-socket-server` | bool | `true` | Start the socket server of the fast `am` command at app start. |
| `share-target` | bool | `true` | Offer TermuxPP in the Android Share menu. |
| `open-with-target` | bool | `true` | Offer TermuxPP in **Open with**. |
| `adb-shell.allowed-ssids` | string | unset (any network) | Comma-separated exact Wi-Fi SSIDs on which the ADB shell service may run. Android must allow the app to read the SSID. |

## power

| Path | Type | Default | Description |
| --- | --- | --- | --- |
| `wake-lock.timeout` | int 0–10080 (minutes) | `0` | Release the wake lock automatically after this time. `0` keeps it until you release it. |
| `wake-lock.wifi` | bool | `false` | Also hold a high-performance Wi-Fi lock while the wake lock is held. This uses more battery. See [Wake lock](terminal-settings.md#wake-lock). |

## Gesture actions

These values work for every `action` key. Volume buttons also accept
`modifier` but not `run-command`.

`none`, `show-keyboard`, `toggle-keyboard`, `hide-keyboard`,
`show-extra-keys`, `hide-extra-keys`, `toggle-extra-keys`, `toggle-text-input`,
`next-session`, `previous-session`, `new-session`, `open-left-sidebar`
(alias `open-sidebar`), `open-right-sidebar`, `open-session-switcher`,
`open-tmux-switcher`, `open-menu`, `open-palette`, `font-size-up`, `font-size-down`,
`send-keys`, `run-command`.

[Tap and swipe actions](terminal-settings.md#tap-and-swipe-actions) describes
each action.

### Positions

`appearance.toast-position` and `gestures.feedback.position` accept the nine
values `top-left`, `top-center`, `top-right`, `center-left`, `center`,
`center-right`, `bottom-left`, `bottom-center` and `bottom-right`.

## Former names

The settings were renamed in November 2026 so that their names say what they
do. The former names are still read. When both are present, the current name
wins. When you change such a setting in the app, it is written under its
current name. A message in the log lists the former names a file still uses.

| Former path | Current path |
| --- | --- |
| `appearance.disable-session-change-toast: X` | `appearance.session-change-toast: !X` |
| `terminal.toolbar-height` | `extra-keys.height` |
| `sessions.tmpdir-max-age-days` | `sessions.tmpdir-cleanup-days` |
| `sessions.nix.session` | `sessions.nix.enabled` |
| `gestures.command-context` | `sessions.command-context` |
| `tmux.sidebar.layout` | `sidebar.layout` |
| `tmux.sidebar.style` | `sidebar.style` |
| `tmux.sidebar.right` | `sidebar.right.enabled` |
| `keyboard.disable-hardware-shortcuts: X` | `keyboard.hardware-shortcuts: !X` |
| `keyboard.volume-keys` | `keyboard.volume.mode` |
| `extra-keys.style` | `extra-keys.labels` |
| `extra-keys.swipe.left.*` | `gestures.extra-keys.swipe.right.*` |
| `extra-keys.swipe.right.*` | `gestures.extra-keys.swipe.left.*` |
| `extra-keys.swipe.long-left.*` | `gestures.extra-keys.long-swipe.right.*` |
| `extra-keys.swipe.long-right.*` | `gestures.extra-keys.long-swipe.left.*` |
| `integrations.disable-file-share-receiver: X` | `integrations.share-target: !X` |
| `integrations.disable-file-view-receiver: X` | `integrations.open-with-target: !X` |

`!X` is the opposite boolean. The former extra-keys swipe names said which
toolbar page the swipe moves to, so `left` was a swipe with the finger moving
to the right. The current names follow the finger, like the terminal swipes.

## Settings outside config.yaml

Whether the extra keys toolbar is shown is not a setting but remembered state.
The Keyboard button's long press and the `toggle-extra-keys` action change it,
and it is stored in the app's private preferences.

`colors.properties` and the `font*.ttf` files live next to `config.yaml`, see
[Configuration files](terminal-settings.md#configuration-files).

## Migration from termux.properties

If there is no `config.yaml`, the app converts the first file it finds, in this
order:

1. `~/.config/termuxpp/termuxpp.properties`
2. `~/.config/termuxpp/termux.properties`
3. `~/.config/termux/termux.properties`
4. `~/.termux/termux.properties`

It then renames that file with a `.migrated` suffix and does not read
properties files again. `use-black-ui` becomes `appearance.night-mode`. Keys
that `config.yaml` does not know are left out and logged.

Earlier versions kept the accent, color scheme and font in the app's private
preferences. The app moves them to `config.yaml` once, at start.
