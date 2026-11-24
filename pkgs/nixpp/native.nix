# nixpp for the Android/Bionic side of TermuxPP (bin/nixpp in the app's
# prefix): sets up and runs the nixpp guest. Cross-compiled against the NDK's
# Bionic sysroot; the result is the Android/AArch64 executable. The NDK ships
# its host toolchain for x86_64 Linux only, so aarch64 Linux hosts link with
# nixpkgs' clang and lld against the same sysroot and runtime libraries.
{
  lib,
  inputs,
  pkgs,
}:
let
  inherit (pkgs.stdenv.hostPlatform) system;
  seed = import ./bootstrap-seed.nix { inherit lib pkgs inputs; };

  fenix = inputs.fenix.packages.${system};
  rustToolchain = fenix.combine [
    fenix.minimal.cargo
    fenix.minimal.rustc
    fenix.targets.aarch64-linux-android.latest.rust-std
  ];
  rustPlatform = pkgs.makeRustPlatform {
    cargo = rustToolchain;
    rustc = rustToolchain;
  };
  androidPkgs = import inputs.nixpkgs {
    inherit system;
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  };
  android = androidPkgs.androidenv.composeAndroidPackages {
    includeNDK = true;
    ndkVersions = [ "27.2.12479018" ];
    platformVersions = [ ];
    buildToolsVersions = [ ];
    includeEmulator = false;
  };
  ndkRoot = "${android.ndk-bundle}/libexec/android-sdk/ndk-bundle";
  ndkToolchain = "${ndkRoot}/toolchains/llvm/prebuilt/linux-x86_64";
  linker =
    if system == "x86_64-linux" then
      "${ndkToolchain}/bin/aarch64-linux-android24-clang"
    else
      # The NDK clang's resource directory holds Android's compiler-rt
      # builtins and libunwind for the target. nixpkgs' clang sets no default
      # dynamic linker, and newer clang defaults to 16 KiB pages without a
      # read-only segment; keep the layout the NDK r27 toolchain produces.
      "${pkgs.writeShellScript "aarch64-linux-android24-clang" ''
        resource_dir=$(echo ${ndkToolchain}/lib/clang/*)
        exec ${pkgs.llvmPackages.clang-unwrapped}/bin/clang \
          --target=aarch64-linux-android24 \
          --sysroot=${ndkToolchain}/sysroot \
          -resource-dir="$resource_dir" \
          --ld-path=${pkgs.llvmPackages.lld}/bin/ld.lld \
          -Wl,-dynamic-linker,/system/bin/linker64 \
          -Wl,-z,max-page-size=4096 \
          -Wl,--rosegment \
          "$@"
      ''}";
in
rustPlatform.buildRustPackage {
  pname = "nixpp-runtime-termux";
  version = "0.5.0";

  src = lib.cleanSource ./source;
  cargoLock.lockFile = ./source/Cargo.lock;
  doCheck = false;
  env = {
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = linker;
  };
  # Pin the seed shipped next to this executable; nixpp refuses any other.
  preBuild = ''
    NIXPP_BOOTSTRAP_SHA256=$(sha256sum ${seed}/share/nixpp/bootstrap-aarch64.zip | cut -d' ' -f1)
    export NIXPP_BOOTSTRAP_SHA256
  '';
  buildPhase = ''
    runHook preBuild
    cargo build --locked --offline --release --target aarch64-linux-android
    runHook postBuild
  '';
  installPhase = ''
    runHook preInstall
    install -Dm0755 target/aarch64-linux-android/release/nixpp "$out/bin/nixpp"
    runHook postInstall
  '';

  allowedReferences = [ ];

  # Rust's standard library comes from the cross toolchain; the RustCrypto
  # dependencies are statically linked.
  passthru = {
    inherit seed;
    abi = "android-bionic";
  };

  meta = {
    description = "Native Android runtime and declarative guest system manager";
    homepage = "https://github.com/pschmitt/termuxpp/tree/main/pkgs/nixpp";
    mainProgram = "nixpp";
    platforms = [
      "x86_64-linux"
      "aarch64-linux"
    ];
    license = lib.licenses.mit;
  };
}
