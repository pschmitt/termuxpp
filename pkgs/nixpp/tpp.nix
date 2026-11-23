# The tpp executable for the managed Linux guest: the Rust workspace's `tpp`
# (crate tpp-cli, with the session control of tpp-session linked in). The
# crates have no third-party dependencies, so Cargo.lock needs no vendoring.
# The Android build of the same executable is made by app/tpp-native.gradle.
{
  lib,
  rustPlatform,
  util-linux,
}:
let
  root = ../..;
  # Keep the input set and digest format in sync with app/tpp-native.gradle.
  versionFiles = [
    (root + "/Cargo.toml")
    (root + "/Cargo.lock")
    (root + "/pkgs/tpp-cli/Cargo.toml")
    (root + "/pkgs/tpp-api/Cargo.toml")
    (root + "/pkgs/tpp-session/Cargo.toml")
  ]
  ++ lib.concatMap (dir: lib.filesystem.listFilesRecursive (root + dir)) [
    "/pkgs/tpp-cli/src"
    "/pkgs/tpp-api/src"
    "/pkgs/tpp-api/help"
    "/pkgs/tpp-session/src"
    "/pkgs/tpp-session/assets"
  ];
  sourceVersion = builtins.hashString "sha256" (
    lib.concatMapStrings (
      file: "${lib.removePrefix "${toString root}/" (toString file)} ${builtins.hashFile "sha256" file}\n"
    ) (lib.sort (a: b: toString a < toString b) versionFiles)
  );
in
rustPlatform.buildRustPackage {
  pname = "tpp";
  version = "0.1.0";

  src = lib.fileset.toSource {
    inherit root;
    fileset = lib.fileset.unions [
      (root + "/Cargo.toml")
      (root + "/Cargo.lock")
      (root + "/pkgs/tpp-api")
      (root + "/pkgs/tpp-cli")
      (root + "/pkgs/tpp-session")
    ];
  };
  cargoLock.lockFile = root + "/Cargo.lock";
  cargoBuildFlags = [
    "-p"
    "tpp"
  ];
  cargoTestFlags = [ "--workspace" ];
  # The parity tests of tpp-api run the former scripts (tpp-api/tests/oracle)
  # as the reference; those need getopt, which the sandbox lacks otherwise.
  nativeCheckInputs = [ util-linux ];
  passthru = { inherit sourceVersion; };

  meta = {
    description = "TermuxPP command line: Android API commands, terminal session control";
    mainProgram = "tpp";
    license = lib.licenses.gpl3Only;
    platforms = lib.platforms.linux;
  };
}
