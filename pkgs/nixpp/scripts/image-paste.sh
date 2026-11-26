# shellcheck shell=bash
# Delivers an image that was pasted into a TermuxPP session to the program
# running in it. TermuxPP runs this in the guest and acts on the last stdout
# line: "key" sends Ctrl+V (the image paste key of Claude Code and similar
# tools), "text PAYLOAD" pastes PAYLOAD instead.
#
# The session's foreground SSH connection (found below its shell process)
# decides where the image goes. Without one, the session is local and the
# guest's wl-paste serves the image TermuxPP left in its handover directory.
# With one, the image goes onto that host's clipboard with wl-copy, over a new
# connection made with the same destination and connection options. Hosts
# without wl-copy get the file copied and its path pasted.

usage() {
  cat <<EOF
Usage: $(basename "$0") --type MIME [--pid SESSION_PID] FILE

Options:
  -t, --type MIME    The image's MIME type, for example image/png.
  --pid PID          The session's shell process; its foreground SSH
                     connection decides the destination.
  -h, --help         Show this help.
EOF
}

declare -A CHILDREN=()
declare -A PGRP=()
declare -A TPGID=()
ARGV=()
SSH_ARGS=()

# Reads every process's parent, process group and terminal foreground group.
scan_processes() {
  local stat line rest pid ppid pgrp tpgid

  for stat in /proc/[0-9]*/stat
  do
    line="$(cat "$stat" 2>/dev/null)" || continue
    [[ -n "$line" ]] || continue
    pid="${stat#/proc/}"
    pid="${pid%/stat}"
    # The command name in parentheses may contain spaces.
    rest="${line##*) }"
    read -r _ ppid pgrp _ _ tpgid _ <<< "$rest"
    CHILDREN[$ppid]+=" $pid"
    PGRP[$pid]="$pgrp"
    TPGID[$pid]="$tpgid"
  done
}

read_argv() {
  local pid="$1"
  local i

  ARGV=()
  [[ -r "/proc/${pid}/cmdline" ]] || return 1
  mapfile -d '' -t ARGV < "/proc/${pid}/cmdline" 2>/dev/null || return 1
  [[ "${#ARGV[@]}" -gt 0 ]] || return 1
  # Guest programs run under the seccomp launcher, which shows up as
  # "launcher [--reexec] [-b HOST:GUEST]... TARGET ARGV..." (droid-nix's
  # bootstrap/launcher/launcher.c); unwrap it to the program's own argv.
  if [[ "${ARGV[0]##*/}" == launcher ]]
  then
    i=1
    [[ "${ARGV[$i]:-}" == --reexec ]] && i=$((i + 1))
    while [[ "${ARGV[$i]:-}" == -b ]]
    do
      i=$((i + 2))
    done
    if [[ "$((i + 1))" -lt "${#ARGV[@]}" ]]
    then
      ARGV=("${ARGV[@]:$((i + 1))}")
    else
      ARGV=("${ARGV[@]:$i}")
    fi
  fi
  [[ "${#ARGV[@]}" -gt 0 ]]
}

# An interactive connection, not a ProxyJump helper (ssh -W), a tunnel
# (-N, or -f in the background) or a git/sftp transport.
is_interactive_connection() {
  local arg

  for arg in "${ARGV[@]:1}"
  do
    case "$arg" in
      -W|*git-upload-pack*|*git-receive-pack*|*sftp*)
        return 1
        ;;
    esac
    # A cluster of flags without values, such as -fN or -At.
    if [[ "$arg" =~ ^-[46AaCfGgKkMNnqsTtVvXxYy]+$ && "$arg" =~ [Nf] ]]
    then
      return 1
    fi
  done
  return 0
}

# Prints the PID of the session's foreground ssh, autossh or mosh-client. The
# shallowest one is the connection that was typed: ProxyCommand scripts and
# ProxyJump run further ssh processes below it, in the same process group.
find_connection() {
  local root="$1"
  local queue=("${root}:0")
  local best=""
  local best_depth=999999
  local entry pid depth child

  while [[ "${#queue[@]}" -gt 0 ]]
  do
    entry="${queue[0]}"
    queue=("${queue[@]:1}")
    pid="${entry%%:*}"
    depth="${entry##*:}"
    for child in ${CHILDREN[$pid]:-}
    do
      queue+=("${child}:$((depth + 1))")
    done
    read_argv "$pid" || continue
    case "${ARGV[0]##*/}" in
      ssh|autossh|mosh-client)
        ;;
      *)
        continue
        ;;
    esac
    is_interactive_connection || continue
    # Only the connection the terminal is showing, not a background tunnel.
    [[ "${PGRP[$pid]:-}" == "${TPGID[$pid]:-x}" ]] || continue
    if [[ "$depth" -lt "$best_depth" ]]
    then
      best="$pid"
      best_depth="$depth"
    fi
  done
  [[ -n "$best" ]] || return 1
  printf '%s\n' "$best"
}

# The shift an ssh argument needs: 1 for a flag (also combined, like -At) or
# an option with an attached value (-p2222), 2 for an option followed by its
# value. Fails for the destination or a command. Adapted from
# pschmitt/tmux-ssh-split.
ssh_option_shift() {
  case "$1" in
    -[BbcDEeFIiJLlmOopQRSWw])
      printf '2\n'
      ;;
    -*)
      printf '1\n'
      ;;
    *)
      return 1
      ;;
  esac
}

# Connection options worth reusing; forwards, -f/-N/-t and logging are not.
keep_ssh_option() {
  case "$1" in
    -4|-6|-C|-[BbcFiJlmopS]|-[BbcFiJlmopS]?*)
      return 0
      ;;
  esac
  return 1
}

# Sets SSH_ARGS to the kept options and the destination of an ssh or autossh
# argv.
ssh_args_from_argv() {
  local args=("${ARGV[@]:1}")
  local i=0
  local destination=""
  local arg shift_by

  SSH_ARGS=()
  if [[ "${ARGV[0]##*/}" == autossh && "${args[0]:-}" == -M ]]
  then
    i=2
  fi
  while [[ "$i" -lt "${#args[@]}" ]]
  do
    arg="${args[$i]}"
    [[ "$arg" == -- ]] && break
    if ! shift_by="$(ssh_option_shift "$arg")"
    then
      # The destination; ssh still accepts options after it, then a command.
      [[ -n "$destination" ]] && break
      destination="$arg"
      i=$((i + 1))
      continue
    fi
    if keep_ssh_option "$arg"
    then
      SSH_ARGS+=("${args[@]:i:shift_by}")
    fi
    i=$((i + shift_by))
  done
  [[ -n "$destination" ]] || return 1
  SSH_ARGS+=("$destination")
}

# mosh-client runs as "mosh-client -# 'HOST | ADDRESS PORT'".
ssh_args_from_mosh() {
  local i host

  SSH_ARGS=()
  for i in "${!ARGV[@]}"
  do
    if [[ "${ARGV[$i]}" == "-#" ]]
    then
      host="${ARGV[$((i + 1))]:-}"
      host="${host%% *}"
      [[ -n "$host" ]] || return 1
      SSH_ARGS=("$host")
      return 0
    fi
  done
  return 1
}

ssh_to() {
  ssh -o BatchMode=yes -o ConnectTimeout=10 "${SSH_ARGS[@]}" "$@"
}

# Puts the image on the host's clipboard. Returns 127 when the host has no
# wl-copy, another status when the host cannot be reached.
copy_to_clipboard() {
  local mime="$1"
  local file="$2"

  # wl-copy keeps serving the selection in the background; detach its output
  # so the SSH session can end.
  ssh_to "command -v wl-copy >/dev/null || exit 127; exec wl-copy --type '${mime}' >/dev/null 2>&1" < "$file"
}

# Copies the image to the host and prints its path there.
copy_file() {
  local file="$1"
  local extension="$2"
  local name

  name="termuxpp-$(date +%Y%m%d-%H%M%S).${extension}"
  ssh_to "d=\"\$HOME/.cache/termuxpp-paste\" && mkdir -p \"\$d\" && cat > \"\$d/${name}\" && printf '%s' \"\$d/${name}\"" < "$file"
}

main() {
  local mime=""
  local pid=""
  local file connection host status remote_path

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
      --pid)
        pid="${2:-}"
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
  if [[ -n "$pid" && ! "$pid" =~ ^[0-9]+$ ]]
  then
    usage >&2
    return 2
  fi
  if [[ ! -r "$file" ]]
  then
    printf 'Cannot read %s\n' "$file" >&2
    return 1
  fi

  connection=""
  if [[ -n "$pid" ]]
  then
    scan_processes
    connection="$(find_connection "$pid")" || connection=""
  fi
  if [[ -z "$connection" ]]
  then
    # A local session: the guest's wl-paste serves the image.
    printf 'key\n'
    return 0
  fi

  if ! read_argv "$connection"
  then
    printf 'The SSH connection ended\n' >&2
    return 1
  fi
  status=0
  if [[ "${ARGV[0]##*/}" == mosh-client ]]
  then
    ssh_args_from_mosh || status=$?
  else
    ssh_args_from_argv || status=$?
  fi
  if [[ "$status" -ne 0 ]]
  then
    printf 'Could not tell where the session is connected to\n' >&2
    return 1
  fi
  host="${SSH_ARGS[-1]}"

  status=0
  copy_to_clipboard "$mime" "$file" || status=$?
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

  if ! remote_path="$(copy_file "$file" "${mime#image/}")"
  then
    printf 'Could not copy the image to %s\n' "$host" >&2
    return 1
  fi
  printf 'text %s\n' "$remote_path"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ft=sh et ts=2 sw=2 :
