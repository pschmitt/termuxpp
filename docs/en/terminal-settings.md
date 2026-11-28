---
page_ref: /docs/apps/termux/terminal-settings.html
---

# Terminal settings

Open **Settings → Properties** to edit settings or search for a property from
the main Settings screen. The editor writes `~/.config/termuxpp/config.yaml`
([Settings file](#settings-file)).
In a provisioned Nix guest, the app uses the guest's home first, with native
files as fallbacks. Edit the file in the same home to manage it through dotfiles.

- [Session commands](#session-commands)
- [Opening the app interface](#opening-the-app-interface)
- [Configuration files](#configuration-files)
- [Terminal feedback position](#terminal-feedback-position)
- [Tap and swipe actions](#tap-and-swipe-actions)
- [Volume buttons](#volume-buttons)
- [Extra-key appearance](#extra-key-appearance)
- [URLs and hyperlinks](#urls-and-hyperlinks)
- [Wake lock](#wake-lock)

## Session commands

`tpp session` controls the terminal tabs owned by the running TermuxPP app. Open
TermuxPP before running it. It is native code in the `tpp` binary, which the app
installs under its native prefix together with Bash, Zsh, and Fish completions; no
Python or other package is needed. Completion suggests commands, live IDs and
positions, `--json` for every command (once), and `--help` for every command.

```text
tpp session new
tpp session new --name "Build logs" -- tail -f build.log
tpp session switcher
tpp session list --json
tpp session focus ID
tpp session rename ID "Build logs"
tpp session move ID 1
tpp session close ID
tpp session kill ID
```

`tpp session new` opens the default shell in a newly selected session. Use
`--name` to name it and `--` to pass a program and its arguments in the
default session environment. The command prints the new session ID, or
an object containing `id` with `--json`.

`tpp session switcher` opens the same picker as the session-switcher gesture
while the terminal screen is visible. The text output of `tpp session list`
uses the sidebar's naming order: the custom name, the terminal title, then
`Session N` using the tab's current position.

Every command accepts `--json`, after the command or in front of it
(`tpp --json session close ID`). `list --json` prints the sessions as a JSON array. `close`,
`focus`, `kill`, `move`, `rename`, `reload-settings` and `switcher` print nothing without
`--json`; with it they print a JSON object that echoes the request after the app
accepted it:

```json
{"ok": true, "action": "close", "id": "ID"}
{"ok": true, "action": "move", "id": "ID", "position": 1}
{"ok": true, "action": "rename", "id": "ID", "name": "Build logs"}
{"ok": true, "action": "reload-settings"}
```

For `close` this means that the hangup was requested, not that the shell has
exited. Failures behave the
same with and without `--json`: a message on standard error, no output on
standard output and a non-zero exit status. JSON is colored on a terminal
(unless `NO_COLOR` is set) and plain when piped.

Copy an ID from `tpp session list`. IDs stay the same when tabs are
renamed or moved. Positions start at 1. `focus` selects the tab without
bringing the app to the foreground. `close` requests a hangup for a running
shell and dismisses the tab when the shell exits. `kill` sends SIGKILL
to the shell. Run `close` on an exited tab to dismiss it.

The Nix guest has the same commands in its own build of the same code; both talk to the
app through the same abstract Unix socket. `TPP_SESSION_SOCKET` selects a different socket name
(for tests).

## Opening the app interface

Run these commands from the visible TermuxPP terminal. Each surface takes `show` (the default),
`hide` or `toggle`; hiding what is not open does nothing.

```text
tpp ui palette
tpp ui palette --search "font"
tpp ui more toggle
tpp ui settings --search "font size"
tpp ui session-switcher hide
tpp ui tmux-switcher toggle
tpp ui sidebar
tpp ui sidebar toggle --right
tpp ui clear
```

`tpp ui picker` works like fzf: it shows the lines of its standard input in a picker with a search
field and prints the line you pick, so it fits in pipes and command substitutions. Closing the
picker without a choice exits with status 130. `--title` names the picker, `--query` types a search
first. It takes up to 20,000 lines; blank lines are dropped.

```sh
cd "$(ls -d */ | tpp ui picker --title Directories)"
git switch "$(git branch --format='%(refname:short)' | tpp ui picker)"
```

`tpp ui settings --search` opens Settings with the search results already visible.
`tpp ui session-switcher` and `tpp session switcher` open the same picker. `tpp ui clear` closes
everything at once: both sidebars, the palette, the pickers, the menu and Settings.

## Command palette

The command palette searches everything in one place: the actions (the same ones that taps,
swipes and keys can run, plus Settings), the sessions, the tmux windows of every session and,
once you type, every setting. Every word you type must appear in an entry's name or its
description; names that start with what you typed come first. Enter or a tap runs the entry
at the top or the one you moved to with the arrow keys: it switches to the session or window,
runs the action, or opens Settings at that setting.

Open it with the `open-palette` action (bind it to a tap, a swipe, a volume key or an extra key
like any other action), with Ctrl+Alt+F on a hardware keyboard, with a Ctrl shortcut of your own
(`keyboard.shortcuts.palette: ctrl+g`; that key then no longer reaches the shell), or with
`tpp ui palette`. It opens where the other pickers do
(`sessions.picker-position`).

## Terminal appearance

Open **Settings → Display** to preview and select a
color scheme and a font. **Use configuration files** follows the file lookup
order below, starting in the ready Nix guest's home when available. **Default**
selects the built-in appearance. Selecting a preset or **Default** overrides
the files for that setting without writing to either home directory. Font
presets use the selected face for all styles, with bold and italic derived by
the renderer. Palette changes apply to open sessions when you return to the
terminal.

## Settings file

The settings live in `~/.config/termuxpp/config.yaml`, grouped by topic. The
[config.yaml reference](config-yaml.md) lists every setting with its type,
default and accepted values, and the former names that are still read. For
example:

```yaml
appearance:
  night-mode: true
sessions:
  picker-position: terminal
sidebar:
  style: rounded
extra-keys:
  keys:
    - [ESC, {key: /, popup: BACKSLASH}, HOME, UP, END, PGUP]
    - [TAB, CTRL, ALT, LEFT, DOWN, RIGHT, PGDN]
gestures:
  tap:
    double:
      action: open-sidebar
```

The **Properties** screen groups the settings like the file, and the search
in Settings finds a setting by its title or its path. When you change a setting there, the app edits only that
setting and keeps the rest of the file, comments included. Unknown paths are
ignored, logged and listed on screen. This page names settings by their path
in `config.yaml`.

## Configuration files

Keep `config.yaml`, `colors.properties`,
and the terminal fonts under `~/.config/termuxpp`. The optional fonts are
`font.ttf`, `font-italic.ttf`, `font-bold.ttf`, and `font-bold-italic.ttf`.
The app accepts symlinked fonts. The settings file must be a regular file.

Appearance files are looked up in `~/.config/termuxpp`, `~/.config/termux`
and `~/.termux`, in that order. Lookup is per file. `XDG_CONFIG_HOME` does
not change these paths. See [Built-in integrations](integrations.md) for script locations.

Run `tpp settings reload` after editing a file. In native sessions, TermuxPP's
adapted `termux-tools` also reads `shell` and `motd.sh` in the same
directory order. A custom `motd.sh` can override the welcome message.

## Setting previews

The cursor shape and blink rate, terminal margins, feedback position, and extra-key
style, appearance and toolbar height settings show a preview while you edit.
Accent color and night mode show a sample interface. Action pickers for terminal
taps, swipes, and volume buttons show a miniature terminal that previews the
selected action. Tap the preview to replay it. The bell setting has a **Try bell**
button. Changes take effect after you save.

## tmux sidebar layout

Open **Settings → Properties → Sidebar → tmux window layout**
or set `sidebar.layout` in `config.yaml`. The layout applies when a sidebar
shows both terminal sessions and tmux windows.

- `nested` (default) shows tmux windows below their terminal. Hold the terminal
  row to collapse or expand the windows, or to open the window picker.
- `drill-down` opens a second sidebar level for one terminal's tmux windows.
- `modes` adds a switch between the terminal list and tmux windows grouped
  by terminal.

After editing the file, run `tpp settings reload`. Open the sidebar while a
tmux attachment is active to see its windows. The highlight follows the active
tmux window, including when you switch with a keyboard shortcut.

**Highlight style** marks the active tmux window and the
current terminal session with either a subtle outline (plus an accent dot
or number badge) or a filled rounded accent pill. Set
`sidebar.style: rounded` (default) or `subtle`.

Under **Sidebars**, choose whether each drawer shows terminal sessions,
tmux windows, the **New session** button, and the **Keyboard** button.
A drawer with tmux windows on and terminal sessions off shows windows
for the current terminal session.

The left drawer defaults to all four items on. The right drawer defaults
to tmux windows only and opens with a right-edge swipe after you enable
**Right sidebar** (`sidebar.right.enabled` in YAML).

Both the layout and style dialogs show a preview before you save.

The session picker and the tmux window picker use the same rows as the
sidebar. With the `nested` layout, the session picker lists each terminal's
tmux windows below it, unless you collapsed them in the sidebar. With the
other layouts, or for collapsed windows, it shows a **tmux windows ›** row
that opens the window picker. The window picker uses the configured style.

## Picker position

**Settings → Properties → Sessions → Picker position** decides where
the session picker and the tmux window picker open:

- `top` (default) at the top of the screen,
- `center` in the middle of the screen,
- `terminal` in the middle of the terminal view, which stays above the
  keyboard while it is shown.

You can also write `sessions.picker-position: terminal` in
`config.yaml` and run `tpp settings reload`.

## Terminal feedback position

Use **Settings → Properties → Appearance → Message position** to move
short terminal messages such as the session number. The default is
`top-center`. The nine values are `top-left`, `top-center`, `top-right`,
`center-left`, `center`, `center-right`, `bottom-left`, `bottom-center`, and
`bottom-right`. You can also write `appearance.toast-position: top-right` in
`config.yaml` and run `tpp settings reload`.

## Tap and swipe actions

Bind a two- or three-finger swipe under **Settings → Properties → Gestures**.
Each finger count has separate up, down, left, and right bindings. Move the
fingers together and lift to run the assigned action. By default, swiping down
with two fingers opens the TermuxPP window picker for the current tmux
attachment. The other seven swipes are unbound. Set
`gestures.swipe.two-finger.down.action: none` to disable that binding.
Pinch-to-zoom continues to work.

For example, set these in `config.yaml`:

```yaml
gestures:
  swipe:
    two-finger:
      left:
        action: next-session
    three-finger:
      up:
        action: open-sidebar
```

Use the corresponding `keys` or `command` setting for `send-keys` or
`run-command`, just as for taps and extra-key swipes.

Custom taps, extra-key swipes, and volume-button actions show visual feedback
by default. Turn **Gesture feedback** off in **Settings → Properties →
Gestures**, or set `gestures.animations: false` in `config.yaml`.
The assigned actions still run. Choose **Feedback effect** in the same
settings group to select `soft-flash` (default), `touch-ripples`, `edge-glow`,
`light-sweep`, `touch-bloom`, or `corner-brackets`. You can also set, for
example, `gestures.feedback.style: touch-ripples` in `config.yaml`.
Ripples and bloom start at the tap location; other actions use the terminal center.
Choose **Feedback position** to place the gesture action pill at any of
the nine terminal positions. Its default is `bottom-center`; it is independent
of `appearance.toast-position`. For example, set
`gestures.feedback.position: top-right` in `config.yaml`.
When Android animations are disabled, the action label remains visible briefly
without an animated effect.


| Setting (under `gestures`) | Gesture | Default |
| --- | --- | --- |
| `tap.single.action` | One finger tap on the terminal | `show-keyboard` |
| `tap.double.action` | Two quick taps in a row | `none` |
| `tap.triple.action` | Three quick taps in a row | `none` |
| `tap.triple.focus-title` | Regular expression for a running session to focus before trying the triple-tap action | unset |
| `extra-keys.swipe.right.action` | Swipe on the normal extra keys row, finger moving to the right (see below) | `none` |
| `extra-keys.swipe.left.action` | Swipe on the normal extra keys row, finger moving to the left (see below) | `next-session` |
| `extra-keys.long-swipe.right.action` | Longer swipe to the right | `none` |
| `extra-keys.long-swipe.left.action` | Longer swipe to the left | `none` |

`sessions.command-context` selects the shell environment of command actions
(default `session`, see [Running commands](#running-commands)).

The gesture keys are independent and active at the same time. Valid values, case-insensitive:

| Value | Action |
| --- | --- |
| `none` | Do nothing. |
| `show-keyboard` | Show the soft keyboard. If it is already visible nothing changes. |
| `toggle-keyboard` | Hide the soft keyboard if it is visible, otherwise show it. Before Android 6 the visibility cannot be read and this only shows it. |
| `hide-keyboard` | Hide the soft keyboard. If it is already hidden nothing changes. |
| `next-session` | Switch to the next session, wrapping around. With one session, creates a new one if `sessions.create-on-single-session-switch: true`. |
| `previous-session` | Switch to the previous session, wrapping around. With one session, creates a new one if `sessions.create-on-single-session-switch: true`. |
| `new-session` | Open a new terminal session and switch to it, like the **New** button. |
| `toggle-text-input` | Switch the extra keys toolbar to its text input, or back to the extra keys. Does nothing while the toolbar is hidden. |
| `show-extra-keys` | Show the extra keys toolbar. If it is already visible nothing changes. |
| `hide-extra-keys` | Hide the extra keys toolbar. If it is already hidden nothing changes. |
| `toggle-extra-keys` | Hide the extra keys toolbar if it is visible, otherwise show it. The choice is remembered like the Keyboard button's long press. |
| `open-left-sidebar` | Open the left sidebar. The existing `open-sidebar` binding also opens this side. |
| `open-right-sidebar` | Open the right sidebar when it is enabled. |
| `open-session-switcher` | Open a compact picker with the current session selected, plus New session, Keyboard and Settings actions. |
| `open-tmux-switcher` | Open the TermuxPP window picker for the current terminal’s local or remote tmux attachment. |
| `open-palette` | Open the command palette: search actions, sessions, tmux windows and settings. |
| `open-menu` | Open the terminal menu, the bottom sheet that a long press on the terminal and then "More..." opens (Extract URL, Share transcript, Autofill, Reset, Kill process, Style, Settings and so on). It never touches the soft keyboard. |
| `font-size-up` | Increase the terminal font size by one step, like pinching out. It never touches the soft keyboard. |
| `font-size-down` | Decrease the terminal font size by one step, like pinching in. It never touches the soft keyboard. |
| `send-keys` | Send keys to the terminal, like Escape or Ctrl+L, given by the matching `*-keys` key (see below). |
| `run-command` | Run the matching `*-command` text in a new visible terminal session. See [Running commands](#running-commands). |

A missing or empty key uses its default. A value that is not listed is logged and the default is
used. The soft keyboard flags that disable the keyboard always win: `show-keyboard`,
`toggle-keyboard` and `hide-keyboard` do nothing while it is disabled, the other actions still work.

When a triple-tap action is configured, `gestures.tap.triple.focus-title` can
match the title reported by a program such as tmux. A match focuses that
session. If the current session matches, it stays selected. If no active
session matches, the configured action runs. For example, combine a title
pattern with `send-keys` and a shell shortcut that starts the desired program.

A recognized custom tap, volume-button action, or extra-keys swipe briefly
shows the action in a pill above the keys. The ordinary single tap to show
the keyboard skips confirmation. Motion follows Android's animator duration
scale.

## Sending keys

The action `send-keys` sends keys to the current terminal session, as if they were typed. Every
gesture binding has a `keys` setting next to its `action` that says which keys to send, for example
`gestures.tap.double.keys` or `gestures.extra-keys.swipe.left.keys`.

## Running commands

Select `run-command` for a tap or swipe, then set the `command` next to its `action`, for example
`gestures.tap.triple.command` or `gestures.extra-keys.swipe.right.command`.

Commands open a new visible terminal session and leave the current program alone. The command is
interpreted by the selected shell, then the session stays open at a shell prompt so you can inspect
output and continue working. Arguments are passed as separate process arguments up to the shell;
the command text itself is one shell `-c` argument, so quoted spaces and shell syntax are kept
intact.

`sessions.command-context` selects the shell environment:

* `session` follows `sessions.nix.enabled`, the setting used by ordinary new terminal sessions.
* `nix` always enters the managed Nix guest. If it is not provisioned, the app reports that and
  does not silently run the command in the outer shell.
* `outer` runs in the native Termux prefix.

An unset or blank command makes its `run-command` action do nothing. Commands are trusted shell
code stored in `config.yaml`; do not put credentials or other secrets in command text. The
app redacts command values from its property and execution logs. Use a script that reads runtime
secrets when a command needs them.

Extra-key buttons can run commands too. Use a command object instead of `key` or `macro` in the
`extra-keys` JSON, with an optional label:

```yaml
extra-keys:
  keys: [["ESC", {"command": "nix develop", "display": "DEV"}]]
```

The same `sessions.command-context` applies. A command button cannot also send a key or macro; it
can be used as a popup button in the same way as a regular key.

In the extra-keys editor, **Long-press key** assigns an alternate key to a hold.
For example, `{key: CTRL, hold: SHIFT}` keeps Ctrl on tap, locks Ctrl on
double-tap, and arms Shift for the next key on hold. The key label changes to
Shift while it is armed. Tap the displayed Shift once to return to Ctrl, or
double-tap it to lock Shift. A tap releases locked Shift. A `popup` entry
still handles the swipe up separately.

The keys are written exactly like the `macro` of an extra keys button in `extra-keys`, so what works
there works here:

* Keys are separated by whitespace and sent in order.
* The names of the extra keys row: `ESC`, `TAB`, `ENTER`, `SPACE`, `UP`, `DOWN`, `LEFT`, `RIGHT`,
  `HOME`, `END`, `PGUP`, `PGDN`, `INS`, `DEL`, `BKSP` and `F1` to `F12`, and the aliases of the row
  such as `ESCAPE`, `RETURN`, `PAGEUP`, `DELETE`, `BACKSPACE`. Names are upper case, as in the extra
  keys.
* `CTRL`, `ALT`, `SHIFT` and `FN` apply to the next key only and have to be followed by one. `CTRL l`
  is Ctrl+L, `CTRL ALT x` is Ctrl+Alt+X. `CONTROL` and `SHFT` are accepted too.
* Any other word is typed as text, a character at a time. `ESC :wq ENTER` sends Escape, the
  characters `:wq` and Enter. A space has to be written `SPACE`, since spaces separate the keys.
* The words of the row that are not terminal input (`KEYBOARD`, `DRAWER`, `PASTE`, `SCROLL`) are not
  accepted. Nothing is ever run as a command, only terminal key input is sent.

If the keys key is missing, blank or invalid (for example a `CTRL` with no key after it), the problem
is logged and the gesture does nothing, as if its action were `none`. That also means that nothing is
animated or consumed for the swipe, and that taps do not wait for it. The keys keys are ignored for
every other action.

Each key is sent with its own modifiers, so no modifier stays pressed after the gesture. The `CTRL`
or `ALT` button of the extra keys row is different: when it is latched at the time of the gesture it
applies to the first key that is sent and is released, as it would for any key typed.

A tap that only sends keys does not show the soft keyboard. Combine it with another gesture if you
want both.

## How taps are told apart

A double tap must not also run the single tap action, and a triple tap must not also run the double
tap action. TermuxPP therefore acts on a tap count only when no further tap can follow:

* A tap that reaches the highest count that has an action acts at once. With a triple tap action
  configured, the third tap acts immediately.
* A lower count waits until the time for the next tap has run out (the system double tap timeout,
  about 300 ms after the press), and then acts. With a double tap action configured, the single
  tap acts after that wait.
* While no double or triple tap action is configured, the single tap acts exactly as before, with
  the latency of the system's confirmed single tap, and nothing else changes.

Taps have to follow each other within the system double tap timeout and close to one another. A
press that moves (scrolling, text selection), a long press, a second finger and taps from an
external mouse are never counted, and drop a tap that was waiting. With a terminal application that
uses the mouse (for example tmux with `mouse on`), every tap is still forwarded to it as a click
at once, as before, in addition to the configured action.

## Extra keys swipe

Deliberate swipes over the normal extra keys row run actions in both directions. They are named by
the direction the finger moves, like the terminal swipes.

| Setting | Finger moves | Default |
| --- | --- | --- |
| `gestures.extra-keys.swipe.right.action` | left to right | `none` |
| `gestures.extra-keys.swipe.left.action` | right to left | `next-session` |

Once a horizontal swipe is detected, the row follows it slightly and a pill above the keys names
the pending action. The pill highlights when the swipe reaches the activation distance (a fifth
of the row's width, at least 64 dp). Release to run the highlighted action, or move back below
the threshold to cancel. The keys under the finger are released without being pressed. Tapping, holding
and repeating keys, the swipe-up popup and vertical scrolling of the row keep working.

### Long swipes

`gestures.extra-keys.long-swipe.right.action` and `gestures.extra-keys.long-swipe.left.action` add a second action to
a direction, run by a swipe further than the long swipe distance: half of the row's width, and at
least twice the swipe distance. Both are unset by default. The pill changes to the long-swipe
action and gives a tick when it reaches that threshold.

The preview is available with `gestures.animations` off. The row stays still and successful
gestures skip confirmation in that mode. Example:

```yaml
gestures:
  extra-keys:
    long-swipe:
      right:
        action: new-session
```

## Volume buttons

The two volume buttons can run the same actions. They are configured under `keyboard.volume` in
the same file:

| Setting | Button | Default |
| --- | --- | --- |
| `keyboard.volume.up.action` | Volume up | `modifier` |
| `keyboard.volume.down.action` | Volume down | `modifier` |
| `keyboard.volume.up.keys` | The keys that volume up sends with `send-keys` | none |
| `keyboard.volume.down.keys` | The keys that volume down sends with `send-keys` | none |

The two buttons are independent. Valid values of the action keys, case-insensitive, are `modifier`
and every action of the table above except `run-command` (`none`, `show-keyboard`, `toggle-keyboard`,
`hide-keyboard`, `show-extra-keys`, `hide-extra-keys`, `toggle-extra-keys`,
`next-session`, `previous-session`, `open-left-sidebar`, `open-right-sidebar`, `open-session-switcher`, `open-tmux-switcher`, `open-menu`, `open-palette`, `font-size-up`, `font-size-down`,
`send-keys`).
The `*-keys` keys are written as described in "Sending keys".

* `modifier` is the default and the behaviour without any configuration: while the button is held,
  volume down is a virtual Ctrl key and volume up is a virtual Fn key (Fn plus a letter or digit
  gives arrows, Page Up and so on), and the system volume does not change. Note that a plain press
  of a volume button does not change the font size by default, the font size is changed by
  pinching or by the `font-size-up` and `font-size-down` actions.
* `none` gives the button back to Android: it changes the system volume as in any other app.
* Any other action replaces the modifier role of that button and runs when the button is pressed.
  The button is consumed, so the system volume does not change either, also when the button is
  released.
* The action runs once for each press and never on release. A held button auto repeats, and only
  `font-size-up` and `font-size-down` repeat with it, so that holding the button keeps growing or
  shrinking the text. Every other action (a session switch, the sidebar, the menu, the keyboard,
  keys) runs once however long the button is held.
* The soft keyboard flags that disable the keyboard win as for every other gesture: `show-keyboard`,
  `toggle-keyboard` and `hide-keyboard` do nothing while it is disabled, the other actions still work.
* `send-keys` without a usable `keyboard.volume.up.keys` or `keyboard.volume.down.keys` is logged and does nothing,
  as if the action were `none`, which gives the button back to the system volume.
* A value that is not listed is logged and `modifier` is used.
* `keyboard.volume.mode: volume` still wins over everything: both buttons then always
  change the system volume and these keys are ignored.
* Volume buttons of a full external (alphabetic) keyboard are never taken over, they stay with
  the system.
* A change takes effect after `tpp settings reload`. A button that is held at that moment is not
  left stuck as a modifier.

## Locking a modifier

The modifier keys of the extra keys row (`CTRL`, `ALT`, `SHIFT`, `FN`) have three ways to be used:

| Gesture on the modifier | Result |
|-------------------------|--------|
| Tap | Applies to the next key only, then it is released (unchanged) |
| Double tap | Locked: it applies to every key until you tap it again |
| Long press | Locked, the same as a double tap (unchanged). Long press again to release |

The second tap of a double tap has to follow within the system double tap timeout, otherwise
it releases the modifier like before. For example, double tap `CTRL` and type `d` twice to send
`Ctrl+D` twice. A locked modifier keeps the active colors of a latched one. This is not
configurable and has no effect on other keys like `ESC`.

## Example

```yaml
gestures:
  tap:
    # Optional: keep the default single tap (show-keyboard) or turn it off with none
    single:
      action: show-keyboard
    # Double tap hides the keyboard when it is visible and shows it otherwise.
    # Alternatively toggle-text-input opens the text input of the extra keys toolbar.
    double:
      action: toggle-keyboard
    # Triple tap opens the sidebar; open-menu would open the terminal menu instead,
    # the one that a long press opens.
    triple:
      action: open-sidebar
  extra-keys:
    swipe:
      # Optional: swipe the extra keys row to the right to go to the previous session
      right:
        action: previous-session
```

Sending keys:

```yaml
gestures:
  tap:
    double:
      action: send-keys
      keys: ESC
    triple:
      action: send-keys
      keys: CTRL l
  extra-keys:
    swipe:
      right:
        action: send-keys
        keys: ESC :q ENTER
```

Volume buttons:

```yaml
keyboard:
  volume:
    # Volume up grows the text, volume down shrinks it (hold to keep going)
    up:
      action: font-size-up
    down:
      action: font-size-down
    # Alternatively: volume up switches to the next session (next-session) and
    # volume down sends Escape (action: send-keys, keys: ESC); none keeps the
    # system volume.
```

Run `tpp settings reload` after editing the file. Stock Termux reads
`termux.properties` and does not understand `config.yaml`.

## Extra-key appearance

**Font** under **Settings → Properties → Extra keys** sets the font used
for the toolbar and its key previews. In YAML, set `extra-keys.font` to `system`
(default), `terminal` (match the terminal font), `monospace`, or `serif`.

In **Extra keys → Layout**, tap a key to edit its optional display label. Custom
labels preserve their case. In YAML, use `display` for the main key,
`hold-display` for a long-press key, or `popup.display` for a swipe-up key.
Each key also accepts `text-color` and `background-color` in hexadecimal.
Leave these blank in the editor to use the theme. Pressed and latched keys
still follow the active key colors.

```yaml
extra-keys:
  font: terminal
  keys: [[{key: CTRL, display: Ctrl, hold: SHIFT, hold-display: "⇧"}, TAB]]
```

`extra-keys.height` scales the height of every extra keys row, from 0.4 to 3 times the
normal height. `extra-keys.compact: true` lowers the rows further to three quarters of that, for a
denser toolbar. It is off by default.

```yaml
extra-keys:
  compact: true
```

`extra-keys.auto-hide: true` shows the extra keys only while the soft keyboard is visible: when the
keyboard hides (the Back key, a gesture, a hardware keyboard), the extra keys hide with it, and they
come back with the keyboard. Hiding the extra keys with the toolbar toggle still hides them until
you show them again. It is off by default.

```yaml
extra-keys:
  auto-hide: true
```

Choose **Press animation** in **Settings → Properties → Extra keys** and
tap a preview key to try it. In YAML, set `extra-keys.animation` to
`key-preview` (default), `key-bubble`, `press-in`, `pop`, `lift`, or `off`. Key preview shows
an enlarged label above the pressed key. Key bubble uses a compact round preview close to the key.
Off keeps the pressed-key color.

| Setting | Applies to | Theme default |
| --- | --- | --- |
| `extra-keys.active.text-color` | Text of an active (tapped, or locked by a long press) special button: `CTRL`, `ALT`, `SHIFT`, `FN` | `#FF0000` (red) |
| `extra-keys.active.background-color` | Background of any extra key while it is pressed, of the swipe-up popup key and, when this key is set, of an active (latched or locked) special button | `#9E9E9E` (grey) while pressed; no fill for latched keys |
| `extra-keys.corner-radius` | Corner radius in dp of the backgrounds of all extra keys (pressed, latched and popup) | `0` / theme (square) |

The value is `#RGB`, `#RRGGBB` or `#AARRGGBB` (alpha first, like Android), case-insensitive. A
value that is present but invalid is logged and ignored.
Inactive buttons follow the theme unless the key defines its own text or background color.

Without `extra-keys.active.background-color` a latched `CTRL`/`ALT` only changes its text
color, as before. With it, the latched key also gets that background, which stays filled until the
key is unlatched (it is not reset when the finger is lifted).

`extra-keys.corner-radius` is a plain decimal number of dp from `0` to `32`, for example
`8` or `6.5`. Signs, units, exponents, `NaN` and values above `32` are invalid, logged and ignored
(the theme shape is used). Only the drawn background is rounded: the touch area of every key stays
the full grid cell. A radius above half the key height makes a pill.

```yaml
extra-keys:
  active:
    text-color: '#D8B4FE'
    background-color: '#3B1D6A'
  corner-radius: 8
```

**Extra keys theme** selects the default toolbar appearance or **Rounded (Gboard-inspired)**,
which gives unstyled keys rounded, spaced caps and picks a light or dark palette with the app.
On Android 14 and later its colors come from the system's Material You palette, the tokens Gboard's
default theme uses: keys in `surface_bright` on `surface_container`, and Escape and the four arrow
keys in `secondary_container` like Gboard's function keys. Pressed and latched keys and the chosen
long-press menu item use the `primary` accent. Earlier versions use Gboard's static palette.
**Extra keys row background color** sets the color behind the keys. A custom background,
corner radius, active color, or per-key color takes precedence over the theme.

```yaml
extra-keys:
  theme: rounded
  background-color: "#191920" # optional; the theme follows the system palette
```

In **Extra keys → Edit layout**, open a row’s menu and choose **Columns in this row**.
New slots appear as faint spacers (␣) in the editor and stay blank in the terminal until assigned a key.
In YAML, each list under `extra-keys.keys` is a row, and each entry is one column.
An empty string reserves a blank slot.

**Fling left key**, **Fling right key**, and **Fling down key** assign a key to a quick movement
that starts on that keycap. The direction follows the finger. A configured horizontal fling takes
priority over the row swipe when the gesture starts there. Tap, hold, and swipe up keep
their respective bindings.

```yaml
extra-keys:
  keys:
    - [{key: LEFT, fling-left: HOME}, {key: DOWN, fling-down: PGDN}, {key: RIGHT, fling-right: END}]
```

A key, or any of its gestures, can run an app **action** instead of sending input: `action` takes
the same names as the gesture settings (`open-left-sidebar`, `open-right-sidebar`, `new-session`,
`next-session`, `previous-session`, `open-session-switcher`, `open-tmux-switcher`, `open-menu`, `open-palette`,
`show-keyboard`, `toggle-keyboard`, `hide-keyboard`, `toggle-text-input`, `show-extra-keys`,
`hide-extra-keys`, `toggle-extra-keys`, `font-size-up`, `font-size-down`).
Use `macro` to send keys and `command` to run a command. An action key shows a symbol unless
`display` sets a label. **Double tap** (`double-tap`) binds a second tap within the double-tap
timeout; only keys that have one wait for it, so other keys stay instant.

A **long-press menu** (`menu`) lists up to 12 alternates, each a key name or a key object with
`key`, `macro`, `command` or `action`. Long-press the key, slide to an item and lift the finger to
choose it; the item nearest to the finger is highlighted, starting with the one above the key.
Mark one item `default: true` to make it the one above the key: the menu opens with it
highlighted, and lifting the finger without sliding picks it.
A key has either a long-press menu or a `hold` key, not both. In the layout editor, use
**Long-press menu → Add menu item**.

```yaml
extra-keys:
  keys:
    - - {key: "|", popup: "-", menu: ["~", "/", "\\", {key: "-", default: true}]}
      - {action: open-left-sidebar, double-tap: {action: open-right-sidebar}}
      - {key: ESC, fling-left: {action: previous-session}, fling-right: {action: next-session}}
```

A key object can set `inset` from 0 to 8 dp to leave space on each side of its keycap.
Set **Corner label** to **Auto**, **Custom text**, or **Off** in the key editor.
Auto uses the first available alternate label in this order: swipe up, fling down, fling left,
fling right, then hold. Set `hint-mode` to `auto`, `custom`, or `off` in YAML,
and set `hint` for custom text. Existing `hint` values keep their text when no mode is set.
The label changes only the appearance of the key.

```yaml
extra-keys:
  corner-radius: 12
  keys:
    - [{key: UP, popup: PGUP, hint: PgUp, inset: 2, background-color: "#1D2A34"}]
    - [{key: DOWN, popup: PGDN, hint: PgDn, inset: 2, background-color: "#1D2A34"}]
```

## URLs and hyperlinks

TermuxPP finds URLs in the terminal text (tap on a URL, and the long press menu entry *Extract URL*)
and supports terminal hyperlinks (`OSC 8`). Both are shown and opened by the same actions.

Opening a link is always something you do: nothing is ever opened when a program prints a link.

## Tapping a link

The tap opens links only when `terminal-onclick-url-open` is set to `true` in `config.yaml`
(the setting that already existed for URLs in the text; it is off by default). A tap on

- a hyperlink opens its address. If the text that is shown for the link is not its address, a
  dialog shows both and asks first (*Open*, *Copy address* or *Cancel*);
- a URL in the text opens that URL, also when an application has broken it over several rows (see
  below);
- anything else is a normal tap.

## Extract URL

*Extract URL* lists the hyperlinks and URLs of the transcript and screen, latest first, the
hyperlinks before the URLs found in the text. A hyperlink whose text is not its address is listed as
`text → address`. A click copies the address, a long press opens it.

## OSC 8 hyperlinks

```
ESC ] 8 ; params ; URI ST     starts a link, the text after it is the link
ESC ] 8 ; ; ST                ends it
```

`ST` is `ESC \` or `BEL`. Supported:

- the label (displayed text) is independent from the URI, and is not changed in any way;
- the `id=` parameter; other parameters are ignored. Links are identified by URI and `id`;
- a link that continues over rows, whether by auto wrap or because the application wrote it on
  several rows (a link is the cells that carry it, so it ends at each row end and starts again on the
  next row);
- links in the scrollback, which scroll and reflow on a resize together with their text.

A link is dropped together with the text of the cells: when they are erased (`EL`, `ED`, `ECH`,
`DCH`, ...), overwritten (also by half of a wide character, or by another link), scrolled out of the
transcript or cleared with `clear` (`CSI 3 J`). The main and the alternate screen do not share an
open link, and a reset (`RIS`, `reset`) ends it. A link never changes how text is displayed.

### What is accepted and the limits

A link that is not accepted is ignored: its text is shown as usual, without a link.

| | |
|---|---|
| Schemes | `http`, `https`, `mailto`, `ftp`, `ftps`, `sftp`. Others (`javascript:`, `intent:`, `file:`, `content:`, `data:`, `tel:`, ...) are ignored |
| Characters | Control characters (also C1), whitespace, bidi and other format characters, private use and unassigned code points are rejected. Other Unicode is kept as it is, as are percent encoding, query, fragment and parentheses |
| Length | URI at most 2083 characters, `id` at most 250 (a longer `id` is dropped, the link stays) |
| Sequence size | An escape sequence longer than 16384 characters is not executed |
| Distinct links | At most 8192 at the same time, and 1 MiB of characters in all. When this is exceeded further links are shown without a link (never with another link); space is freed when the text that carries them is erased or scrolled out. After a bulk erase it can take some more links until the space is available again |
| Extract URL | At most 2000 hyperlinks are listed |

A hyperlink whose label is the address (with or without scheme, with or without a trailing `/`,
ignoring whitespace the application inserted) is opened without asking. Anything else, including a
label that only shows the beginning of the address, is shown in a dialog.

## URLs in the text and multi-line URLs

URLs are found with a pattern for the schemes TermuxPP knows (`http`, `https`, `ftp`, `sftp`,
`git`, `ws`, ...). Percent encoding, query, fragment, parentheses and Unicode letters are kept.
`. , ; : ! ? ' "` at the end are not part of the URL, and neither is a `)` that has no `(` in the URL.

A terminal application that wraps its own output (for example a CLI printing a long login URL, indented
by two spaces, a tab, or inside a box) ends each row with a newline instead of letting the terminal
wrap. As URLs have no whitespace the rows are joined **only if all of this is true**:

1. the URL is the last thing on its row (a right border `│ ┃ ║ |` of a box and spaces may follow);
2. it reaches the right edge of the terminal, or of the box, to within 2 columns: a URL that ends
   earlier had no reason to be broken;
3. the next row is exactly one word of URL characters, after any amount of spaces and tabs and an
   optional left border, and does not start with a scheme (that is another URL). A row with a
   second word, an empty row, or a row of only punctuation is never a continuation;
4. the result is at most 8192 characters long and 65 rows. For URLs of more rows the first 65 rows are
   the URL.

The indentation, the border and the newline are removed from the URL only; the text is not changed.
Each row of a long URL must again reach the edge to continue it.

Not handled, by design: text that is cut by the application without a recognizable edge (for
example a URL that was shortened with `…`, or wrapped at a width that is not the terminal width), URLs
whose rows are separated by an empty row, and a continuation that is a word and also valid text (the
rule above joins a row such as `report` after a URL that ends at the edge).

## Wake lock

The wake lock keeps the CPU running while the screen is off, so programs and SSH sessions keep
working. Acquire and release it with the notification's button, with **Wake lock** in the terminal
menu (under **More** while it is off), or with `termux-wake-lock` and `termux-wake-unlock`. While it
is held, the notification shows for how long.

| Key | Meaning | Default |
| --- | --- | --- |
| `wake-lock-timeout` | Release the wake lock by itself after this many minutes (0 to 10080), so a forgotten one does not keep the phone awake | `0` (keep it until released) |
| `wake-lock-wifi` | Also hold a high-performance Wi-Fi lock, which keeps the Wi-Fi radio out of power save | `false` |

```yaml
power:
  wake-lock:
    timeout: 120
```

## Where it is implemented

| | |
|---|---|
| `terminal-emulator` `TerminalHyperlink`, `TerminalHyperlinkRegistry` | validation, interning and the limits |
| `TerminalRow`, `TerminalBuffer`, `TerminalEmulator` | the hyperlink of each cell, its lifetime, `OSC 8` parsing |
| `termux-shared` `TermuxUrlUtils` | URLs in the text, joining rows |
| `TermuxTerminalViewClient` | tap, the confirmation and *Extract URL* |

Tests: `HyperlinkTest` (emulator) and `TermuxUrlUtilsTest` (`termux-shared`).
