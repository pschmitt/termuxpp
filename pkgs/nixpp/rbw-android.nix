# rbw (the pschmitt fork, which has Android Keystore unlock and Termux
# detection) for the Android/Bionic side of TermuxPP: bin/rbw and
# bin/rbw-agent in the app's prefix, which `nixpp setup` uses to read the
# enrollment credentials from Bitwarden. Built from source with the same
# cross toolchain as nixpp-runtime-termux (android-toolchain.nix).
{
  lib,
  inputs,
  pkgs,
}:
let
  toolchain = import ./android-toolchain.nix { inherit lib inputs pkgs; };
  inherit (toolchain) rustPlatform linker;
  src = inputs.rbw-src;
  cargoToml = lib.importTOML "${src}/Cargo.toml";
in
rustPlatform.buildRustPackage {
  pname = "rbw-termux";
  inherit (cargoToml.package) version;

  inherit src;
  cargoLock.lockFile = "${src}/Cargo.lock";
  doCheck = false;
  env = {
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = linker;
    # ring compiles C and assembly in its build script.
    CC_aarch64_linux_android = toolchain.cc;
    AR_aarch64_linux_android = toolchain.ar;
  };
  buildPhase = ''
    runHook preBuild
    # No clipboard feature: arboard has no Android backend.
    cargo build --locked --offline --release --target aarch64-linux-android \
      --no-default-features --bin rbw --bin rbw-agent
    runHook postBuild
  '';
  installPhase = ''
    runHook preInstall
    install -Dm0755 target/aarch64-linux-android/release/rbw "$out/bin/rbw"
    install -Dm0755 target/aarch64-linux-android/release/rbw-agent "$out/bin/rbw-agent"
    install -Dm0644 LICENSE "$out/share/licenses/rbw/LICENSE"
    runHook postInstall
  '';

  allowedReferences = [ ];

  passthru.abi = "android-bionic";

  meta = {
    description = "Android/Bionic build of the pschmitt rbw fork for TermuxPP";
    homepage = "https://github.com/pschmitt/rbw";
    mainProgram = "rbw";
    platforms = [
      "x86_64-linux"
      "aarch64-linux"
    ];
    license = lib.licenses.mit;
  };
}
