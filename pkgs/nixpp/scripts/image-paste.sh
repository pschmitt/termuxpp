# shellcheck shell=bash
# Delivers an image that was pasted into a TermuxPP session to the program
# running in it. TermuxPP runs this in the guest and acts on the last stdout
# line: "key" sends Ctrl+V (the image paste key of Claude Code and similar
# tools), "text PAYLOAD" pastes PAYLOAD instead.
#
# A session on the phone itself needs nothing more: the guest's wl-paste
# serves the image TermuxPP left in its handover directory. For a session
# whose title names another host, the image goes onto that host's clipboard
# with wl-copy over SSH. Hosts without wl-copy get the file copied and its
# path pasted.

usage() {
  cat <<EOF
Usage: $(basename "$0") --type MIME [--title TITLE] FILE

Options:
  -t, --type MIME    The image's MIME type, for example image/png.
  --title TITLE      The session title, used to find the session's host.
  -h, --help         Show this help.
EOF
}

# The phone's own name, so its local sessions are not sent anywhere.
local_host() {
  jq -r '.host // empty' /etc/nixpp/upgrade.json 2>/dev/null
}

# The host a session title names: "t:HOST:...", "user@HOST ..." or
# "tmux:session@HOST", the forms shell and tmux titles use.
host_from_title() {
  local title="$1"
  local host_re='[A-Za-z0-9][A-Za-z0-9._-]*'

  if [[ "$title" =~ ^!?t:(${host_re}): ]]
  then
    printf '%s\n' "${BASH_REMATCH[1]}"
  elif [[ "$title" =~ @(${host_re}) ]]
  then
    printf '%s\n' "${BASH_REMATCH[1]}"
  fi
}

short_name() {
  printf '%s\n' "${1%%.*}"
}

is_local() {
  local host="$1"
  local self

  [[ -z "$host" ]] && return 0
  self="$(local_host)"
  case "$(short_name "$host")" in
    localhost|"$(short_name "${self:-localhost}")")
      return 0
      ;;
  esac
  return 1
}

ssh_to() {
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$@"
}

# Puts the image on the host's clipboard. Returns 127 when the host has no
# wl-copy, another status when the host cannot be reached.
copy_to_clipboard() {
  local host="$1"
  local mime="$2"
  local file="$3"

  # wl-copy keeps serving the selection in the background; detach its output
  # so the SSH session can end.
  ssh_to "$host" "command -v wl-copy >/dev/null || exit 127; exec wl-copy --type '${mime}' >/dev/null 2>&1" < "$file"
}

# Copies the image to the host and prints its path there.
copy_file() {
  local host="$1"
  local file="$2"
  local extension="$3"
  local name

  name="termuxpp-$(date +%Y%m%d-%H%M%S).${extension}"
  ssh_to "$host" "d=\"\$HOME/.cache/termuxpp-paste\" && mkdir -p \"\$d\" && cat > \"\$d/${name}\" && printf '%s' \"\$d/${name}\"" < "$file"
}

main() {
  local mime=""
  local title=""
  local file host status remote_path

  while [[ -n "${1:-}" ]]
  do
    case "$1" in
      -h|--help)
        usage
        return 0
        ;;
      -t|--type)
        mime="${2:-}"
        shift 2
        ;;
      --title)
        title="${2:-}"
        shift 2
        ;;
      *)
        break
        ;;
    esac
  done

  file="${1:-}"
  if [[ -z "$file" || ! "$mime" =~ ^image/[a-z0-9.+-]+$ ]]
  then
    usage >&2
    return 2
  fi
  if [[ ! -r "$file" ]]
  then
    printf 'Cannot read %s\n' "$file" >&2
    return 1
  fi

  host="$(host_from_title "$title")"
  if is_local "$host"
  then
    printf 'key\n'
    return 0
  fi

  status=0
  copy_to_clipboard "$host" "$mime" "$file" || status=$?
  if [[ "$status" -eq 0 ]]
  then
    printf 'key\n'
    return 0
  fi
  if [[ "$status" -ne 127 ]]
  then
    printf 'Could not reach %s (ssh exit status %s)\n' "$host" "$status" >&2
    return 1
  fi

  remote_path="$(copy_file "$host" "$file" "${mime#image/}")" || {
    printf 'Could not copy the image to %s\n' "$host" >&2
    return 1
  }
  printf 'text %s\n' "$remote_path"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ft=sh et ts=2 sw=2 :
