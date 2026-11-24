# Cross-compilation toolchain for the Android/Bionic executables of TermuxPP
# (nixpp's native runtime, rbw): a Rust toolchain with the aarch64-linux-android
# standard library and a linker for the NDK's Bionic sysroot. The NDK ships its
# host toolchain for x86_64 Linux only, so aarch64 Linux hosts link with
# nixpkgs' clang and lld against the same sysroot and runtime libraries.
{
  inputs,
  pkgs,
}:
let
  inherit (pkgs.stdenv.hostPlatform) system;
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
{
  inherit rustPlatform linker ndkToolchain;
  # C compiler and archiver for build scripts of dependencies that compile C
  # (ring): the same driver the linker uses, and the NDK's llvm-ar.
  cc = linker;
  ar =
    if system == "x86_64-linux" then
      "${ndkToolchain}/bin/llvm-ar"
    else
      "${pkgs.llvmPackages.llvm}/bin/llvm-ar";
}
