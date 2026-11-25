#!/bin/sh
# Report tmux windows to TermuxPP terminals as a private OSC sequence that the app reads and never draws.
# Other terminals ignore the unknown sequence.
#
# Usage: notify.sh report [SESSION_ID]   report a session's windows (every session's without an ID)
#                                        to the clients attached to it

osc() {
  printf '\033]7769;tmux-windows;1;%s\007' "$2" 2>/dev/null > "$1" || :
}

report_session() {
  # One line per window: ID, index, active flag, the active pane's command and the name, tab-separated.
  payload=$(tmux list-windows -t "$1" -F \
    '#{window_id}	#{window_index}	#{window_active}	#{pane_current_command}	#{window_name}' \
    2>/dev/null | base64 | tr -d '\n')
  [ -n "$payload" ] || return 0
  tmux list-clients -t "$1" -F '#{client_tty}' 2>/dev/null | while IFS= read -r tty
  do
    [ -n "$tty" ] && osc "$tty" "$payload"
  done
}

case ${1:-} in
  report)
    if [ -n "${2:-}" ]
    then
      report_session "$2"
    else
      tmux list-sessions -F '#{session_id}' 2>/dev/null | while IFS= read -r session
      do
        report_session "$session"
      done
    fi
    ;;
  *)
    echo "Usage: $0 report [SESSION_ID]" >&2
    exit 2
    ;;
esac
