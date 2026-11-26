# Clipboard tools for the TermuxPP guest: wl-copy and wl-paste backed by the
# Android clipboard, and the helper TermuxPP runs when an image is pasted
# into a session (see scripts/image-paste.sh).
{
  callPackage,
  coreutils,
  findutils,
  jq,
  lib,
  openssh,
  symlinkJoin,
  writeShellApplication,
}:
let
  tpp = callPackage ./tpp.nix { };
  # TermuxPP writes pasted images here: the app's native home, which the guest
  # sees under the same path.
  handoverDir = "/data/data/dev.pschmitt.termux/files/home/.cache/termuxpp-clipboard";
  tool =
    name: script: inputs:
    writeShellApplication {
      inherit name;
      runtimeInputs = inputs;
      text = ''
        export TERMUXPP_CLIPBOARD_DIR="''${TERMUXPP_CLIPBOARD_DIR:-${handoverDir}}"
        ${builtins.readFile script}
      '';
    };
in
symlinkJoin {
  name = "termuxpp-clipboard";
  paths = [
    (tool "wl-copy" ./scripts/wl-copy.sh [
      coreutils
      tpp
    ])
    (tool "wl-paste" ./scripts/wl-paste.sh [
      coreutils
      findutils
      tpp
    ])
    (tool "termuxpp-image-paste" ./scripts/image-paste.sh [
      coreutils
      jq
      openssh
    ])
  ];
  meta = {
    description = "wl-clipboard for the TermuxPP guest and its image paste helper";
    mainProgram = "termuxpp-image-paste";
    platforms = lib.platforms.linux;
  };
}
