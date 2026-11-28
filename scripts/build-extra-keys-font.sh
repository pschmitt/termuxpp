#!/usr/bin/env bash
# Build the bundled extra-keys font (extra-keys.font: google-sans on phones without
# Google Sans) from Google Sans Flex (SIL OFL 1.1): every axis but the weight
# pinned (so extra-keys.font-weight gets real 100-900 cuts from one file),
# subset to Latin, punctuation and the symbols extra keys use.
#
# Usage: scripts/build-extra-keys-font.sh GoogleSansFlex.ttf
# The variable font is ofl/googlesansflex in https://github.com/google/fonts.
# Needs fonttools (nix shell nixpkgs#python3Packages.fonttools).

set -euo pipefail

usage() {
  echo "Usage: $0 GoogleSansFlex.ttf" >&2
}

main() {
  if [[ $# -ne 1 || ! -f $1 ]]
  then
    usage
    return 2
  fi

  local src=$1
  local out
  out="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/extra-keys-fonts"
  TMPDIR_FONT=$(mktemp -d)
  trap 'rm -rf "$TMPDIR_FONT"' EXIT
  local tmp=$TMPDIR_FONT

  # Basic Latin to Latin Extended-B, general punctuation, currency, letterlike
  # symbols, arrows, math operators, technical (⎋ ⌫ ⏎), box drawing, shapes.
  local unicodes="U+0020-024F,U+2000-206F,U+20A0-20CF,U+2100-214F,U+2190-21FF"
  unicodes+=",U+2200-22FF,U+2300-23FF,U+2500-25FF"

  mkdir -p "$out"
  fonttools varLib.instancer -q -o "$tmp/wght.ttf" "$src" \
    opsz=18 wdth=100 ROND=0 slnt=0
  pyftsubset "$tmp/wght.ttf" --unicodes="$unicodes" \
    --layout-features='*' --no-hinting --output-file="$out/GoogleSansFlex.ttf"
  ls -l "$out"
}

main "$@"
