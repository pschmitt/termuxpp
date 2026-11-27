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
- [tmux](#tmux)
- [sidebar](#sidebar)
- [keyboard](#keyboard)
- [extra-keys](#extra-keys)
- [gestures](#gestures)
- [integrations](#integrations)
- [power](#power)
- [Gesture actions](#gesture-actions)
- [Settings outside config.yaml](#settings-outside-configyaml)
- [Migration from termux.properties](#migration-from-termuxproperties)

## The file

- **Location:** `~/.config/termuxpp/config.yaml`. In a provisioned Nix guest,
  the app reads the file from the guest's home first and falls back to the
  native home. `XDG_CONFIG_HOME` does not change this path. The file must be a
  regular file: the app does not follow a symlink, so a file that Home Manager
  links into the Nix store is not read. Fonts may be symlinks.
- **Reload:** the app reads the file at start. After editing it, run
  `tpp settings reload`.
- **Editing in the app:** **Settings → Properties** edits the same file. When
  you save a setting there, the app rewrites the whole file in the order
  used on this page. **Comments and your formatting are lost.** If you manage
  the file through dotfiles, edit only the file, or copy changes made in the
  app back into your dotfiles.
- **Missing settings** use their default. You only need to write the settings
  you change.
- **Invalid values** are logged and replaced by the default. The app still
  starts.
- **Unknown paths** are ignored and logged. A typo such as `apperance:` turns
  off every setting below it without an error on screen.
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

The **Legacy key** column gives the setting's name in the former
`termux.properties` format. The **Properties** screen also shows these names.

## appearance

| Path | Type | Default | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `fullscreen` | bool | `false` | `fullscreen` | Hide the status bar. |
| `fullscreen-workaround` | bool | `false` | `use-fullscreen-workaround` | Keep the soft keyboard from covering the terminal in fullscreen mode. It only applies with `fullscreen: true`. |
| `night-mode` | enum: `true`, `false`, `system` | `system` | `night-mode` | Dark theme always, never, or following the device. |
| `toast-position` | enum: [position](#positions) | `top-center` | `terminal-toast-position` | Where short terminal messages appear, such as the session number after a switch. |
| `disable-session-change-toast` | bool | `false` | `disable-terminal-session-change-toast` | Do not show the session number and name when you switch sessions. |

## terminal

| Path | Type | Default | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `engine` | enum: `termux`, `libghostty` | `termux` | `terminal-engine` | Terminal engine for new sessions. `libghostty` is only available on ARM64. |
| `margin.horizontal` | int 0–100 (dp) | `3` | `terminal-margin-horizontal` | Space to the left and right of the text. |
| `margin.vertical` | int 0–100 (dp) | `0` | `terminal-margin-vertical` | Space above and below the text. |
| `cursor.style` | enum: `block`, `underline`, `bar` | `block` | `terminal-cursor-style` | Cursor shape. |
| `cursor.blink-rate` | int 0 or 100–2000 (ms) | `0` | `terminal-cursor-blink-rate` | Duration of each blink phase. `0` turns blinking off. |
| `transcript-rows` | int 100–50000 | `2000` | `terminal-transcript-rows` | Scrollback lines kept per session. |
| `toolbar-height` | float 0.4–3 | `1` | `terminal-toolbar-height` | Height of the extra keys toolbar as a factor of its normal height. |
| `bell` | enum: `vibrate`, `beep`, `ignore` | `vibrate` | `bell-character` | Response to the BEL character. |
| `open-url-on-click` | bool | `false` | `terminal-onclick-url-open` | Open a link when you tap it, without the long-press menu. See [URLs and hyperlinks](terminal-settings.md#urls-and-hyperlinks). |
| `default-working-directory` | path | `~` | `default-working-directory` | Start directory of new sessions. It must exist and be readable. |

## sessions

| Path | Type | Default | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `picker-position` | enum: `top`, `center`, `terminal` | `top` | `terminal-picker-position` | Where the session picker and tmux window picker open. `terminal` centers them on the terminal, above the keyboard. |
| `create-on-single-session-switch` | bool | `false` | `create-session-on-single-session-switch` | With only one session open, next/previous session creates a new one. |
| `tmpdir-max-age-days` | int −1–100000 | `3` | `delete-tmpdir-files-older-than-x-days-on-exit` | When the last session exits, delete files in `$TMPDIR` that were not used for this many days. `-1` keeps all files and `0` deletes all of them. |
| `nix.session` | bool | `true` | `nix-session` | Start new sessions in the Nix guest when it is available. With `false`, they start the native shell. The failsafe session always starts the native shell. |
| `nix.user` | string | `nix` | `nix-user` | User name in the Nix guest: a lowercase letter or `_`, then up to 31 lowercase letters, digits, `_` or `-`. It is read only when the guest is set up. |

## tmux

| Path | Type | Default | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `sidebar.layout` | enum: `nested`, `drill-down`, `modes` | `nested` | `terminal-tmux-sidebar-layout` | Layout of a sidebar that shows both sessions and tmux windows. See [tmux sidebar layout](terminal-settings.md#tmux-sidebar-layout). |
| `sidebar.style` | enum: `rounded`, `subtle` | `rounded` | `terminal-tmux-sidebar-style` | How the active tmux window and the current session are marked in the sidebars and pickers. |
| `sidebar.right` | bool | `false` | `tmux-right-sidebar` | Enable the right sidebar, opened by a swipe from the right edge. Configure what it shows under [`sidebar.right`](#sidebar). |

## sidebar

Each drawer has the same four switches. A drawer with tmux windows on and
sessions off shows the windows of the current session.

| Path | Type | Default (left / right) | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `left.show-sessions`, `right.show-sessions` | bool | `true` / `false` | `sidebar-{left,right}-show-sessions` | List terminal sessions. |
| `left.show-tmux-windows`, `right.show-tmux-windows` | bool | `true` / `true` | `sidebar-{left,right}-show-tmux-windows` | List tmux windows. |
| `left.show-new-session`, `right.show-new-session` | bool | `true` / `false` | `sidebar-{left,right}-show-new-session` | Show the **New session** button. |
| `left.show-keyboard-button`, `right.show-keyboard-button` | bool | `true` / `false` | `sidebar-{left,right}-show-keyboard-button` | Show the **Keyboard** button. |

The right drawer only opens after `tmux.sidebar.right: true`.

## keyboard

| Path | Type | Default | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `back-key` | enum: `back`, `escape` | `back` | `back-key` | What the Back button does when the terminal has focus. |
| `hide-on-startup` | bool | `false` | `hide-soft-keyboard-on-startup` | Do not show the soft keyboard when the app starts. |
| `hide-on-sidebar` | bool | `true` | `hide-soft-keyboard-on-sidebar` | Hide the soft keyboard while a sidebar is open and show it again when the sidebar closes. |
| `toggle-behaviour` | enum: `show/hide`, `enable/disable` | `show/hide` | `soft-keyboard-toggle-behaviour` | What the keyboard toggle does. `enable/disable` keeps the keyboard from popping up until you toggle it again. |
| `char-based-input` | bool | `false` | `enforce-char-based-input` | Send each character as you type. Use it when typed text only appears after Enter, which happens with some keyboards (for example on Samsung devices). |
| `ctrl-space-workaround` | bool | `false` | `ctrl-space-workaround` | Fix Ctrl+Space not sending NUL on some devices. |
| `disable-hardware-shortcuts` | bool | `false` | `disable-hardware-keyboard-shortcuts` | Turn off the built-in Ctrl+Alt shortcuts of a hardware keyboard (for example Ctrl+Alt+N for the next session) so that the keys reach the terminal. |
| `shortcuts.create-session` | `ctrl+X` or `disabled` | `ctrl+t` | `shortcut.create-session` | Ctrl shortcut for a new session, for example `ctrl+t`. `disabled` removes it. |
| `shortcuts.next-session` | `ctrl+X` or `disabled` | `ctrl+2` | `shortcut.next-session` | Shortcut for the next session. |
| `shortcuts.previous-session` | `ctrl+X` or `disabled` | `ctrl+1` | `shortcut.previous-session` | Shortcut for the previous session. |
| `shortcuts.rename-session` | `ctrl+X` or `disabled` | `ctrl+r` | `shortcut.rename-session` | Shortcut to rename the current session. |
| `volume-keys` | enum: `virtual`, `volume` | `virtual` | `volume-keys` | `volume` gives both buttons back to Android and ignores `keyboard.volume.*`. |
| `volume.up.action` | `modifier` or action | `modifier` | `volume-up-action` | Volume up. `modifier` makes it a virtual Fn key while held. `run-command` is not allowed. |
| `volume.up.keys` | keys | unset | `volume-up-keys` | Keys for `send-keys`. |
| `volume.down.action` | `modifier` or action | `modifier` | `volume-down-action` | Volume down. `modifier` makes it a virtual Ctrl key while held. |
| `volume.down.keys` | keys | unset | `volume-down-keys` | Keys for `send-keys`. |

See [Volume buttons](terminal-settings.md#volume-buttons) for repeat and
modifier details.

## extra-keys

| Path | Type | Default | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `keys` | list of rows | two rows, see below | `extra-keys` | The buttons. See [Extra-key appearance](terminal-settings.md#extra-key-appearance). |
| `style` | enum: `default`, `arrows-only`, `arrows-all`, `all`, `none` | `default` | `extra-keys-style` | How key **names** are drawn, for example `LEFT` as an arrow. It does not change the look of the buttons, that is `theme`. |
| `theme` | enum: `default`, `rounded` | `default` | `extra-keys-theme` | Look of the button row. |
| `background-color` | color | theme | `extra-keys-row-background-color` | Color behind the row. |
| `font` | enum: `system`, `terminal`, `monospace`, `serif` | `system` | `extra-keys-font` | Font of the labels. `terminal` uses the terminal font. |
| `animation` | enum: `key-preview`, `key-bubble`, `press-in`, `pop`, `lift`, `off` | `key-preview` | `extra-keys-animation` | Effect of a pressed key. |
| `all-caps` | bool | `true` | `extra-keys-text-all-caps` | Show labels in capital letters. |
| `compact` | bool | `false` | `extra-keys-compact` | Make each row three quarters of its normal height. This applies on top of `terminal.toolbar-height`. |
| `corner-radius` | float 0–32 (dp) | theme | `extra-keys-button-corner-radius` | Corner radius of the buttons. It overrides the theme. |
| `active.text-color` | color | theme | `extra-keys-button-active-text-color` | Label color of a latched or locked modifier. |
| `active.background-color` | color | theme | `extra-keys-button-active-background-color` | Button color while pressed. |
| `swipe.left.{action,keys,command}` | action / keys / command | `none` | `extra-keys-swipe-left-*` | Swipe on the row with the finger moving **left to right**. |
| `swipe.right.{action,keys,command}` | action / keys / command | `next-session` | `extra-keys-swipe-right-*` | Swipe with the finger moving **right to left**. |
| `swipe.long-left.{action,keys,command}` | action / keys / command | `none` | `extra-keys-long-swipe-left-*` | Longer swipe in the `swipe.left` direction. |
| `swipe.long-right.{action,keys,command}` | action / keys / command | `none` | `extra-keys-long-swipe-right-*` | Longer swipe in the `swipe.right` direction. |

The default `keys`:

```yaml
extra-keys:
  keys:
    - [ESC, /, {key: '-', popup: '|'}, HOME, UP, END, PGUP]
    - [TAB, CTRL, ALT, LEFT, DOWN, RIGHT, PGDN]
```

The swipe directions are named after the toolbar page that the swipe moves
towards, not after the movement of the finger. See
[Extra keys swipe](terminal-settings.md#extra-keys-swipe).

## gestures

| Path | Type | Default | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `animations` | bool | `true` | `gesture-animations` | Visual feedback for custom taps, extra-key swipes and volume button actions. |
| `command-context` | enum: `session`, `nix`, `outer` | `session` | `gesture-command-context` | Where every `run-command` action and every extra-key `command` button runs. `session` follows `sessions.nix.session`, `nix` always uses the guest, and `outer` uses the native prefix. |
| `feedback.style` | enum: `soft-flash`, `touch-ripples`, `edge-glow`, `light-sweep`, `touch-bloom`, `corner-brackets` | `soft-flash` | `gesture-feedback-style` | Effect shown for a recognized gesture. |
| `feedback.position` | enum: [position](#positions) | `bottom-center` | `gesture-feedback-position` | Where the action label appears. This setting is independent of `appearance.toast-position`. |
| `tap.single.{action,keys,command}` | action / keys / command | `show-keyboard` | `terminal-single-tap-*` | One tap on the terminal. |
| `tap.double.{action,keys,command}` | action / keys / command | `none` | `terminal-double-tap-*` | Two quick taps. |
| `tap.triple.{action,keys,command}` | action / keys / command | `none` | `terminal-triple-tap-*` | Three quick taps. |
| `tap.triple.focus-title` | regular expression | unset | `terminal-triple-tap-focus-title` | Before the triple-tap action runs, switch to a session whose title matches. If no session matches, the action runs. |
| `swipe.{two,three}-finger.{up,down,left,right}.{action,keys,command}` | action / keys / command | `none`, except `two-finger.down`: `open-tmux-switcher` | `terminal-{2,3}-finger-swipe-DIR-*` | Multi-finger swipes on the terminal. They are named by the direction the fingers move. |

Each binding has three keys. `action` selects what happens, `keys` is read
only for `send-keys`, and `command` only for `run-command`:

```yaml
gestures:
  tap:
    triple:
      action: run-command
      command: htop
      focus-title: '^htop'
```

## integrations

| Path | Type | Default | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `allow-external-apps` | bool | `false` | `allow-external-apps` | Let other apps run commands through the `RUN_COMMAND` intent. Any app with the permission can then run commands as you. |
| `am-socket-server` | bool | `true` | `run-termux-am-socket-server` | Start the socket server of the fast `am` command at app start. |
| `disable-file-share-receiver` | bool | `false` | `disable-file-share-receiver` | Hide TermuxPP from the Android Share menu. |
| `disable-file-view-receiver` | bool | `false` | `disable-file-view-receiver` | Hide TermuxPP from **Open with**. |
| `adb-shell.allowed-ssids` | string | unset (any network) | `adb-shell-allowed-ssids` | Comma-separated exact Wi-Fi SSIDs on which the ADB shell service may run. Android must allow the app to read the SSID. |

## power

| Path | Type | Default | Legacy key | Description |
| --- | --- | --- | --- | --- |
| `wake-lock.timeout` | int 0–10080 (minutes) | `0` | `wake-lock-timeout` | Release the wake lock automatically after this time. `0` keeps it until you release it. |
| `wake-lock.wifi` | bool | `false` | `wake-lock-wifi` | Also hold a high-performance Wi-Fi lock while the wake lock is held. This uses more battery. See [Wake lock](terminal-settings.md#wake-lock). |

## Gesture actions

These values work for every `action` key. Volume buttons also accept
`modifier` but not `run-command`.

`none`, `show-keyboard`, `toggle-keyboard`, `hide-keyboard`,
`show-extra-keys`, `hide-extra-keys`, `toggle-extra-keys`, `toggle-text-input`,
`next-session`, `previous-session`, `new-session`, `open-left-sidebar`
(alias `open-sidebar`), `open-right-sidebar`, `open-session-switcher`,
`open-tmux-switcher`, `open-menu`, `font-size-up`, `font-size-down`,
`send-keys`, `run-command`.

[Tap and swipe actions](terminal-settings.md#tap-and-swipe-actions) describes
each action.

### Positions

`appearance.toast-position` and `gestures.feedback.position` accept the nine
values `top-left`, `top-center`, `top-right`, `center-left`, `center`,
`center-right`, `bottom-left`, `bottom-center` and `bottom-right`.

## Settings outside config.yaml

Some settings are stored elsewhere and cannot be set in `config.yaml`:

- **Color scheme and font:** `colors.properties` and `font*.ttf` in
  `~/.config/termuxpp`, or a preset chosen under **Settings → Display**.
  Presets are stored in the app's private preferences. See
  [Configuration files](terminal-settings.md#configuration-files).
- **Accent color:** **Settings → Display**, stored in the app's private
  preferences.
- **Extra keys visibility** (the Keyboard button's long press or
  `toggle-extra-keys`): stored in the app's private preferences.

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
