{
  lib,
  stdenvNoCC,
  makeWrapper,
  installShellFiles,
  bash,
  zsh,
  fish,
  python3,
  android-tools,
  openssl,
}:
stdenvNoCC.mkDerivation {
  pname = "tpp-adb-shell";
  version = "0.5.0";
  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      (lib.fileset.fileFilter (
        f:
        f.hasExt "py"
        && (
          lib.hasPrefix "tpp-" f.name
          || lib.hasPrefix "termuxpp_adb_" f.name
          || lib.hasPrefix "test_tpp_adb_" f.name
          || lib.hasPrefix "test_termuxpp_adb_" f.name
        )
      ) ./.)
      ./completions
    ];
  };
  nativeBuildInputs = [
    makeWrapper
    installShellFiles
  ];
  nativeCheckInputs = [
    python3
    bash
    zsh
    fish
  ];
  doCheck = true;
  checkPhase = ''
    runHook preCheck
    python3 -I -m unittest discover -s . -p 'test_*adb_*.py'
    runHook postCheck
  '';
  installPhase = ''
    runHook preInstall
    install -Dm644 tpp-adb-shell.py "$out/libexec/tpp-adb-shell.py"
    install -Dm644 termuxpp_adb_devices.py "$out/libexec/termuxpp_adb_devices.py"
    install -Dm644 tpp-adb-ssh-proxy.py "$out/libexec/tpp-adb-ssh-proxy.py"
    install -Dm644 completions/tpp-adb-shell.bash "$out/libexec/completions/tpp-adb-shell.bash"
    install -Dm644 completions/tpp-adb-shell.zsh "$out/libexec/completions/tpp-adb-shell.zsh"
    install -Dm644 completions/tpp-adb-shell.fish "$out/libexec/completions/tpp-adb-shell.fish"
    installShellCompletion --cmd tpp-adb-shell \
      --bash completions/tpp-adb-shell.bash \
      --zsh completions/tpp-adb-shell.zsh \
      --fish completions/tpp-adb-shell.fish
    installShellCompletion --cmd tpp-adb-ssh-proxy \
      --bash completions/ssh-proxy.bash \
      --zsh completions/ssh-proxy.zsh \
      --fish completions/ssh-proxy.fish
    makeWrapper ${python3}/bin/python3 "$out/bin/tpp-adb-shell" \
      --add-flags "-I $out/libexec/tpp-adb-shell.py" \
      --prefix PATH : ${
        lib.makeBinPath [
          android-tools
          openssl
        ]
      }
    makeWrapper ${python3}/bin/python3 "$out/bin/tpp-adb-ssh-proxy" \
      --add-flags "-I $out/libexec/tpp-adb-ssh-proxy.py" \
      --prefix PATH : ${
        lib.makeBinPath [
          android-tools
          openssl
        ]
      }
    runHook postInstall
  '';
  meta = {
    description = "TermuxPP ADB shell client with run-as and fingerprint-approved mTLS transports";
    mainProgram = "tpp-adb-shell";
    platforms = lib.platforms.linux;
  };
}
