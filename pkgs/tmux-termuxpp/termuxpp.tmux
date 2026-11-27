#!/bin/sh
# TermuxPP tmux plugin: reports the windows of each session to the TermuxPP terminals attached to it, for the
# app's sidebar and window picker, and lets the app select a window. Load it with TPM or `run-shell PATH`.
# Protocol: docs/en/tmux-plugin.md in the TermuxPP repository.

dir=$(cd "$(dirname "$0")" && pwd)
notify="$dir/scripts/notify.sh"

# The app selects a window by typing this key, the digits of the window ID (without its @) and Return.
# A key table collects the digits, so there is no prompt to draw and nothing depends on timing: tmux
# reads the keys in order even when they arrive in one write. A fixed array index keeps reloading the
# configuration from adding the key or the hooks again. tmux does not expand escapes in command
# arguments, so the key is passed with a real ESC.
tmux set -s 'user-keys[77]' "$(printf '\033[5;30077~')"
# Client ttys distinguish clients even when they share a session or pane.
tmux bind-key -n User77 "run-shell -C 'set -g @termuxpp-window-#{client_tty} \"\"' ; switch-client -T termuxpp"
for digit in 0 1 2 3 4 5 6 7 8 9
do
  tmux bind-key -T termuxpp "$digit" \
    "run-shell -C 'set -ag @termuxpp-window-#{client_tty} $digit' ; switch-client -T termuxpp"
done
tmux bind-key -T termuxpp Enter "run-shell \"'$notify' select '#{client_tty}' '#{session_id}'\""
tmux set-hook -g 'client-detached[77]' "run-shell -C 'set -guq @termuxpp-window-#{hook_client}'"

for hook in client-attached client-session-changed session-window-changed window-linked \
  window-unlinked window-renamed after-new-window after-select-window after-kill-pane pane-exited
do
  tmux set-hook -g "${hook}[77]" "run-shell -b \"'$notify' report '#{session_id}'\""
done
# No report on detach: the app forgets the windows when the tmux client leaves the alternate screen.

# Clients that were attached before the plugin was loaded.
"$notify" report
