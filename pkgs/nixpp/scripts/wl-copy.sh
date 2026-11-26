# shellcheck shell=bash
# wl-copy for the TermuxPP guest, which has no Wayland compositor. Text goes
# to the Android clipboard (tpp clipboard set); an image is kept in the
# handover directory, where the guest's wl-paste serves it.
#
# TERMUXPP_CLIPBOARD_DIR is set by the Nix wrapper.

handover_dir="${TERMUXPP_CLIPBOARD_DIR:?}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options] [text...]

Copy text (the arguments, or stdin without arguments) to the Android
clipboard. Images (--type image/...) are kept for the guest's wl-paste.

Options:
  -t, --type mime/type   The type of the content.
  -n, --trim-newline     Do not copy the trailing newline character.
  -c, --clear            Clear the clipboard.
  -o, -f, -p, -s SEAT    Accepted for compatibility.
  -v, --version          Display version info.
  -h, --help             Display this message.
EOF
}

store_image() {
  local type="$1"
  local tmp

  mkdir -p "$handover_dir" || return 1
  chmod 700 "$handover_dir" || return 1
  tmp="$(mktemp "${handover_dir}/.image.XXXXXX")" || return 1
  cat > "$tmp" || return 1
  mv -f "$tmp" "${handover_dir}/image" || return 1
  printf '%s\n' "$type" > "${handover_dir}/type"
}

copy_text() {
  local trim="$1"
  shift
  local text

  if [[ "$#" -gt 0 ]]
  then
    text="$*"
  else
    # $(...) drops every trailing newline; keep them unless asked to trim one.
    text="$(cat; printf x)"
    text="${text%x}"
    if [[ -n "$trim" ]]
    then
      text="${text%$'\n'}"
    fi
  fi
  # A here-string would append a newline.
  printf '%s' "$text" | tpp clipboard set >/dev/null
}

main() {
  local type=""
  local trim=""

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
      -t|--type)
        type="${2:-}"
        shift 2
        ;;
      --type=*)
        type="${1#--type=}"
        shift
        ;;
      -n|--trim-newline)
        trim=1
        shift
        ;;
      -c|--clear)
        tpp clipboard set "" >/dev/null
        return
        ;;
      -o|--paste-once|-f|--foreground|-p|--primary)
        shift
        ;;
      -s|--seat)
        shift 2
        ;;
      --)
        shift
        break
        ;;
      -*)
        usage >&2
        return 2
        ;;
      *)
        break
        ;;
    esac
  done

  if [[ "$type" == image/* ]]
  then
    store_image "$type"
  else
    copy_text "$trim" "$@"
  fi
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]
then
  main "$@"
fi

# vim: set ft=sh et ts=2 sw=2 :
