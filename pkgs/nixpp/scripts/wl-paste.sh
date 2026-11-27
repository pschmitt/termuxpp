# shellcheck shell=bash
# wl-paste for the TermuxPP guest, which has no Wayland compositor. Text comes
# from the Android clipboard (tpp clipboard get --text). An image pasted into a
# session in the last 15 minutes is served from the handover directory
# TermuxPP writes, so programs that read images with wl-paste (Claude Code's
# Ctrl+V) can attach it.
#
# TERMUXPP_CLIPBOARD_DIR is set by the Nix wrapper.

handover_dir="${TERMUXPP_CLIPBOARD_DIR:?}"

IMAGE_MAX_AGE_MINUTES=15
TEXT_TYPES=("text/plain;charset=utf-8" text/plain UTF8_STRING STRING TEXT)

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Paste content from the Android clipboard, or the last image pasted into a
TermuxPP session.

Options:
  -n, --no-newline       Do not append a newline character.
  -l, --list-types       Instead of pasting, list the offered types.
  -p, --primary          Use the primary clipboard (same as the clipboard).
  -t, --type mime/type   Paste this type.
  -s, --seat seat-name   Accepted for compatibility.
  -v, --version          Display version info.
  -h, --help             Display this message.
EOF
}

# The MIME type of a recent pasted image, if there is one.
image_type() {
  local type_file="${handover_dir}/type"
  local image_file="${handover_dir}/image"
  local type

  [[ -r "$type_file" && -r "$image_file" ]] || return 1
  [[ -n "$(find "$image_file" -mmin "-${IMAGE_MAX_AGE_MINUTES}" 2>/dev/null)" ]] || return 1
  type="$(<"$type_file")"
  [[ "$type" =~ ^image/ ]] || return 1
  printf '%s\n' "$type"
}

list_types() {
  image_type || true
  printf '%s\n' "${TEXT_TYPES[@]}"
}

is_text_type() {
  local wanted="$1"
  local type

  [[ "$wanted" == text/* ]] && return 0
  for type in "${TEXT_TYPES[@]}"
  do
    [[ "$wanted" == "$type" ]] && return 0
  done
  return 1
}

paste_text() {
  local no_newline="$1"
  local text

  text="$(tpp clipboard get --text)" || return 1
  if [[ -n "$no_newline" ]]
  then
    printf '%s' "$text"
  else
    printf '%s\n' "$text"
  fi
}

paste_type() {
  local wanted="$1"
  local no_newline="$2"
  local offered

  if is_text_type "$wanted"
  then
    paste_text "$no_newline"
    return
  fi
  offered="$(image_type)" || offered=""
  # "image/png" asks for that type; a bare "image" or "png" is inferred.
  if [[ -n "$offered" && ( "$wanted" == "$offered" || "$offered" == *"$wanted"* ) ]]
  then
    cat "${handover_dir}/image"
    return
  fi
  printf 'No suitable type of content copied\n' >&2
  return 1
}

main() {
  local no_newline=""
  local list=""
  local wanted=""

  while [[ -n "${1:-}" ]]
  do
    case "$1" in
      -h|--help)
        usage
        return 0
        ;;
      -v|--version)
        printf 'wl-clipboard 2.2.1 (TermuxPP guest)\n'
        return 0
        ;;
      -n|--no-newline)
        no_newline=1
        shift
        ;;
      -l|--list-types)
        list=1
        shift
        ;;
      -p|--primary)
        shift
        ;;
      -t|--type)
        wanted="${2:-}"
        shift 2
        ;;
      --type=*)
        wanted="${1#--type=}"
        shift
        ;;
      -s|--seat)
        shift 2
        ;;
      -w|--watch)
        printf '%s: --watch is not supported in the TermuxPP guest\n' "$(basename "$0")" >&2
        return 1
        ;;
      *)
        usage >&2
        return 2
        ;;
    esac
  done

  if [[ -n "$list" ]]
  then
    list_types
  elif [[ -n "$wanted" ]]
  then
    paste_type "$wanted" "$no_newline"
  else
    paste_text "$no_newline"
  fi
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ft=sh et ts=2 sw=2 :
