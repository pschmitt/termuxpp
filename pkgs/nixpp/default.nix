{
  lib,
  stdenv,
  buildPackages,
  dix,
  installShellFiles,
  rustPlatform,
  python3,
}:
let
  completionGenerator =
    if stdenv.buildPlatform.canExecute stdenv.hostPlatform then
      "$out/bin/nixpp"
    else
      lib.getExe (buildPackages.callPackage ./default.nix { });
in
rustPlatform.buildRustPackage {
  pname = "nixpp";
  version = "0.5.0";
  src = lib.cleanSource ./source;
  cargoLock.lockFile = ./source/Cargo.lock;
  nativeBuildInputs = [ installShellFiles ];
  postInstall = ''
    test -x "$NIXPP_DIX"
    "${completionGenerator}" completions bash > nixpp
    "${completionGenerator}" completions zsh > _nixpp
    "${completionGenerator}" completions fish > nixpp.fish
    installShellCompletion --cmd nixpp --bash nixpp --zsh _nixpp --fish nixpp.fish
    # The name of the native launcher, so `nixpp-setup` works in the guest too
    # (where `nixpp setup` hands the invocation to the native side).
    # /bin/sh, not stdenv.shell: in a cross build (the bootstrap seed's guest)
    # that is the build machine's shell. nixpp provides /bin/sh in every guest.
    printf '#!/bin/sh\nexec %s setup "$@"\n' "$out/bin/nixpp" > "$out/bin/nixpp-setup"
    chmod 0755 "$out/bin/nixpp-setup"
  '';
  NIXPP_REFERENCE_PREPARE = ./tests/prepare-session.py;
  NIXPP_REFERENCE_PYTHON = "${python3}/bin/python3";
  NIXPP_DIX = lib.getExe' dix "dix";
  NIXPP_DIX_LEGACY = if lib.versionOlder (lib.getVersion dix) "3" then "1" else "0";

  passthru = {
    inherit dix;
  };

  meta = {
    description = "Declarative Android guest system manager";
    homepage = "https://github.com/pschmitt/termuxpp/tree/main/pkgs/nixpp";
    mainProgram = "nixpp";
    platforms = lib.platforms.linux;
    license = lib.licenses.mit;
  };
}
