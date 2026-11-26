# nixpp and the guest-side TermuxPP helpers, built with the caller's Nixpkgs.
#
# nixos-config calls this with its own guest `pkgs` (and follows this flake's
# nixpkgs input), so the paths CI builds here are the ones its hosts use.
{
  inputs,
  pkgs,
}:
let
  termuxpp-api = pkgs.callPackage ./android-api.nix { inherit inputs; };
in
{
  # The Linux/glibc guest system manager.
  nixpp = pkgs.callPackage ./. { };
  termuxpp-rish = pkgs.callPackage ./rish.nix { };
  termuxpp-ip = pkgs.callPackage ./ip.nix { };
  # wl-copy/wl-paste on the Android clipboard and the image paste helper.
  termuxpp-clipboard = pkgs.callPackage ./clipboard.nix { };
  # Requests storage access through the guest's termux-am, which addresses the
  # TermuxPP app identity (the outer environment's `am` does not).
  termuxpp-storage = pkgs.callPackage ./storage.nix { inherit (termuxpp-api) termux-am; };
  inherit termuxpp-api;

  # The Android/Bionic runtime and its bootstrap seed. The NDK only runs on
  # x86_64 Linux (meta.platforms); the attribute names must not depend on
  # `pkgs`, since callers merge this set into overlays.
  nixpp-runtime-termux = import ./native.nix {
    inherit inputs pkgs;
    inherit (pkgs) lib;
  };
  # rbw for the Android prefix (bin/rbw, bin/rbw-agent), from source.
  rbw-termux = import ./rbw-android.nix {
    inherit inputs pkgs;
    inherit (pkgs) lib;
  };
  nixpp-runtime-termux-seed = import ./bootstrap-seed.nix {
    inherit inputs pkgs;
    inherit (pkgs) lib;
  };
}
