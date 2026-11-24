{
  lib,
  stdenv,
  buildPackages,
  dix,
  installShellFiles,
  makeWrapper,
  rustPlatform,
  python3,
  symlinkJoin,
}:
let
  completionGenerator =
    if stdenv.buildPlatform.canExecute stdenv.hostPlatform then
      "$out/bin/nixpp"
    else
      lib.getExe (buildPackages.callPackage ./default.nix { });

  # Android's seccomp path translation runs in a SIGSYS handler, which
  # overflows Rust's default alternate signal stack. dix 3.0.0 segfaulted on
  # the Pixel's PRoot signal stack (confirmed 2026-11-19 against Nixpkgs
  # b1b8759); the same live diff passes with this preload. Drop it once the
  # unwrapped binary passes that test on the Pixel.
  signalStack = stdenv.mkDerivation {
    pname = "nixpp-signal-stack";
    version = "1";
    src = ./signal-stack.c;
    dontUnpack = true;
    buildPhase = ''
      $CC -shared -fPIC -O2 -Wall -Wextra -Werror "$src" -o stack.so
    '';
    installPhase = ''
      install -Dm755 stack.so $out/lib/stack.so
    '';
  };
  guestDix = symlinkJoin {
    name = "dix-nixpp-${lib.getVersion dix}";
    paths = [ dix ];
    nativeBuildInputs = [ makeWrapper ];
    postBuild = ''
      wrapProgram $out/bin/dix --prefix LD_PRELOAD : ${signalStack}/lib/stack.so
    '';
  };
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
    printf '#!%s\nexec %s setup "$@"\n' "${stdenv.shell}" "$out/bin/nixpp" > "$out/bin/nixpp-setup"
    chmod 0755 "$out/bin/nixpp-setup"
  '';
  NIXPP_REFERENCE_PREPARE = ./tests/prepare-session.py;
  NIXPP_REFERENCE_PYTHON = "${python3}/bin/python3";
  NIXPP_DIX = lib.getExe' guestDix "dix";
  NIXPP_DIX_LEGACY = if lib.versionOlder (lib.getVersion dix) "3" then "1" else "0";

  passthru = {
    dix = guestDix;
    inherit signalStack;
  };

  meta = {
    description = "Declarative Android guest system manager";
    homepage = "https://github.com/pschmitt/termuxpp/tree/main/pkgs/nixpp";
    mainProgram = "nixpp";
    platforms = lib.platforms.linux;
    license = lib.licenses.mit;
  };
}
