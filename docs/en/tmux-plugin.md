# tmux plugin

The TermuxPP tmux plugin shows the windows of an attached tmux session in the app's session sidebar and
window picker, and lets you switch windows from there. It works the same for a local tmux, tmux over SSH
or mosh, and nested setups, because it only talks to the app through the terminal itself: tmux writes
the window list into the terminal's output, and the app types into the terminal to select a window.
Nothing else is needed on the host: no `tpp`, no shell wrapper and no SSH options.

Source: [`pkgs/tmux-termuxpp`](../../pkgs/tmux-termuxpp).

## Install

Load `termuxpp.tmux` from your tmux configuration. [TPM](https://github.com/tmux-plugins/tpm) expects a
plugin at the root of its repository, which this one is not, so load the file directly:

```tmux
run-shell /path/to/termuxpp/pkgs/tmux-termuxpp/termuxpp.tmux
```

With Nix, the flake provides the package `tmux-termuxpp` (also `lib.tmux.packagesFor pkgs` for another
Nixpkgs). Add it to Home Manager's `programs.tmux.plugins`, or load
`${pkg}/share/tmux-plugins/termuxpp/termuxpp.tmux`.

The plugin needs tmux 3.2 or newer, a POSIX shell and `base64`. Loading it again, for example when the
configuration is reloaded, replaces its hooks and keys instead of adding them twice.

## What it does

- **Reports.** Hooks on attaching, switching sessions, creating, closing, renaming and selecting windows
  report the session's windows to the terminals of every client attached to it. tmux renames windows
  after the program running in them unless `automatic-rename` is off, so the program icons follow too.
  Loading the plugin reports every session once.
- **Selection.** The plugin defines `user-keys[77]` as `ESC [ 5 ; 3 0 0 7 7 ~` and binds the key
  `User77` to the key table `termuxpp`, which collects digits and selects window `@DIGITS` on Return.
  Its hooks and keys use index 77 and the user option `@termuxpp-window`.
- **Clearing.** The app forgets the windows when the tmux client leaves the terminal's alternate
  screen: on detach, on exit, and when a connection drops and the shell takes over the terminal again.

## Protocol

tmux to the app, an OSC sequence the app never draws (other terminals ignore it):

```text
ESC ] 7769 ; tmux-windows ; 1 ; BASE64 BEL
```

`BASE64` encodes one line per window, with tab-separated fields:

```text
WINDOW_ID  INDEX  ACTIVE  COMMAND  NAME
@4         1      1       claude   code review
```

`WINDOW_ID` is `@` and digits, `ACTIVE` is `0` or `1`, `COMMAND` is the active pane's
`pane_current_command` (may be empty) and `NAME` is the window name, which may contain spaces but not tabs.
An empty list clears the windows. A report with another version, or a line that does not parse, is
ignored as a whole.

The app to tmux, typed into the terminal:

```text
ESC [ 5 ; 3 0 0 7 7 ~   DIGITS   CR
```

where `DIGITS` is the window ID without its `@`. The keys may arrive in one write; tmux reads them in
order. App side: [`TmuxWindows`](../../app/src/main/java/com/termux/app/TmuxWindows.java) and
[`TerminalEmulator`](../../terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java)
(`OSC_TMUX_WINDOWS`).

## Limits

- The reports go to every client of the session, including terminals other than TermuxPP; they ignore
  an unknown OSC sequence. A report is written to the client's tty from a hook, outside tmux, so in rare
  cases it can land between two parts of tmux's own output; the app then ignores the garbled report and
  takes the next one.
- A window whose program changes while `automatic-rename` is off keeps its old icon until the next
  report.
- Typing the select key while tmux shows a prompt or a menu goes to that prompt instead.
