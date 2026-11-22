{
  callPackage,
  cmake,
  coreutils,
  inputs,
  lib,
  python3,
  runCommand,
  stdenv,
  symlinkJoin,
  writeShellApplication,
  notificationTimeouts ? {
    low = 4000;
    normal = 7000;
  },
}:
let
  source = inputs.droid-nix-src + "/pkgs/android-integration";
  androidApp = {
    packageName = "dev.pschmitt.termux";
    dataDir = "/android/data/data/dev.pschmitt.termux";
  };
  # The one tpp executable. tpp-session is the same file under another name
  # (it picks the session control by its argv[0]); the Android app's session
  # socket is reached through the abstract socket namespace, so no file of the
  # app prefix is involved.
  tppCli = callPackage ./tpp.nix { };
  inherit (callPackage ./default.nix { }) signalStack;
  session = writeShellApplication {
    name = "tpp-session";
    text = ''
      export LD_PRELOAD=${signalStack}/lib/stack.so''${LD_PRELOAD:+:$LD_PRELOAD}
      exec -a tpp-session ${tppCli}/bin/tpp "$@"
    '';
  };
  reloadSettings = writeShellApplication {
    name = "tpp-reload-settings";
    text = ''
      export LD_PRELOAD=${signalStack}/lib/stack.so''${LD_PRELOAD:+:$LD_PRELOAD}
      exec -a tpp-session ${tppCli}/bin/tpp reload-settings "$@"
    '';
  };
  notifySend = writeShellApplication {
    name = "notify-send";
    text = ''
      export TERMUXPP_NOTIFICATION_API=${backend}/libexec/termux-api
      export TERMUXPP_ANDROID_PATH=${androidPath}
      export TERMUXPP_NOTIFY_TIMEOUTS=${lib.escapeShellArg (builtins.toJSON notificationTimeouts)}
      exec ${lib.getExe python3} ${./scripts/notify-send.py} "$@"
    '';
  };
  sessionCompletions = runCommand "tpp-session-completions" { } ''
    install -Dm644 ${../../pkgs/tpp-session/assets/session-completions/bash} \
      "$out/share/bash-completion/completions/tpp-session"
    install -Dm644 ${../../pkgs/tpp-session/assets/session-completions/fish} \
      "$out/share/fish/vendor_completions.d/tpp-session.fish"
    install -Dm644 ${../../pkgs/tpp-session/assets/session-completions/zsh} \
      "$out/share/zsh/site-functions/_tpp-session"
  '';
  # TermuxPP already runs the upstream TermuxAm socket server. Droid-nix uses
  # the same protocol but places the socket directly under its files directory.
  termux-am = (callPackage (source + "/termux-am.nix") { inherit androidApp; }).overrideAttrs (old: {
    postPatch = old.postPatch + ''
      substituteInPlace termux-am.h \
        --replace-fail /files/am.sock /files/apps/dev.pschmitt.termux/termux-am/am.sock
    '';
  });
  androidPath = lib.getExe (writeShellApplication {
    name = "nixpp-android-path";
    runtimeInputs = [ coreutils ];
    text = builtins.readFile ./scripts/android-api-path.sh;
  });
  openTools =
    (callPackage (source + "/termux-tools.nix") { inherit androidApp termux-am; }).overrideAttrs
      (old: {
        postPatch = old.postPatch + ''
          substituteInPlace scripts/termux-open.in \
            --replace-fail '${androidApp.packageName}/${androidApp.packageName}.OpenReceiver' '${androidApp.packageName}/com.termux.app.TermuxOpenReceiver' \
            --replace-fail 'realpath "$FILE"' '${androidPath} "$FILE"'
        '';
      });
  backend = stdenv.mkDerivation {
    pname = "termuxpp-api-backend";
    version = "0.60.0";
    src = lib.cleanSource ../tpp-api-bridge;
    nativeBuildInputs = [ cmake ];

    postPatch = ''
      # The bridge: install prefix and TermuxAm in the store.
      # substituteInPlace --replace-fail aborts the build if the source moved on.
      substituteInPlace termux-api.c \
        --replace-fail '"/data/data/dev.pschmitt.termux/files/usr"' "\"$out\"" \
        --replace-fail 'execv(PREFIX "/bin/am"' 'execv("${termux-am}/bin/am"'

      # patchShebangs maps this to the store
      sed -i 's|^#!@TERMUX_PREFIX@/bin/|#!/bin/|' termux-callback.in
    '';

    doInstallCheck = true;
    installCheckPhase = ''
      runHook preInstallCheck
      failed=0
      fail() {
        printf 'backend check: %s\n' "$*" >&2
        failed=1
      }

      grep -q ${termux-am}/bin/am "$out/libexec/termux-api-broadcast" ||
        fail "the bridge does not exec TermuxAm from the store"
      test -x "$out/libexec/termux-api" || fail "libexec/termux-api is missing"
      test -x "$out/libexec/termux-callback" || fail "libexec/termux-callback is missing"
      case "$(head -n1 "$out/libexec/termux-callback")" in
        "#!/nix/store/"*) ;;
        *) fail "termux-callback: interpreter is not in the store" ;;
      esac

      # The commands are in the tpp binary: no command script is installed.
      ! test -e "$out/libexec/tpp" || fail "libexec/tpp exists"

      test "$failed" = 0
      runHook postInstallCheck
    '';
  };
  # The plugin directory of tpp: empty, the commands are in the binary.
  tppPlugins = runCommand "tpp-guest-plugins" { } ''
    mkdir -p "$out"
  '';
  tpp = writeShellApplication {
    name = "tpp";
    text = ''
      export LD_PRELOAD=${signalStack}/lib/stack.so''${LD_PRELOAD:+:$LD_PRELOAD}
      export TPP_PLUGIN_DIR="${tppPlugins}:''${HOME}/.local/libexec/tpp/plugins''${TPP_PLUGIN_DIR:+:$TPP_PLUGIN_DIR}"
      # The API commands reach the app through TermuxAm, and guest paths
      # (/home, /nix, /android/...) are converted to the Android paths that the
      # app can open.
      export TPP_API_AM=${termux-am}/bin/am
      export TPP_API_PATH_CMD=${androidPath}
      PATH=${termux-am}/bin:"$PATH"
      export PATH
      exec ${tppCli}/bin/tpp "$@"
    '';
  };
  # The dispatcher end to end: every command answers -h and --help with its
  # usage text and nothing else (no API call, no service change).
  dispatcherHelp = runCommand "tpp-dispatcher-help" { nativeBuildInputs = [ tpp ]; } ''
    export HOME="$TMPDIR"
    tpp --help > /dev/null
    tpp --command-names > names
    test "$(wc -l < names)" -gt 50
    while read -r -a words
    do
      for flag in -h --help
      do
        if ! text=$(timeout 20 tpp "''${words[@]}" "$flag" < /dev/null 2>&1)
        then
          echo "tpp ''${words[*]} $flag failed: $text" >&2
          exit 1
        fi
        if ! grep -qi '^usage:' <<< "$text"
        then
          echo "tpp ''${words[*]} $flag printed no usage line: $text" >&2
          exit 1
        fi
      done
      if ! text=$(timeout 20 tpp help "''${words[@]}" < /dev/null 2>&1)
      then
        echo "tpp help ''${words[*]} failed: $text" >&2
        exit 1
      fi
    done < names
    touch "$out"
  '';
  tppCompletions = runCommand "tpp-completions" { } ''
    install -Dm644 ${../tpp-api-bridge/completions/tpp.bash} "$out/share/bash-completion/completions/tpp"
    install -Dm644 ${../tpp-api-bridge/completions/tpp.fish} "$out/share/fish/vendor_completions.d/tpp.fish"
    install -Dm644 ${../tpp-api-bridge/completions/tpp.zsh} "$out/share/zsh/site-functions/_tpp"
  '';
in
symlinkJoin {
  name = "termuxpp-api";
  paths = [
    tpp
    tppCompletions
    session
    reloadSettings
    notifySend
    openTools.xdg_open
    sessionCompletions
  ];
  postBuild = ''
    ln -s ${openTools.open}/bin/droid-nix-open "$out/bin/tpp-open"
    ln -s ${termux-am}/bin/am "$out/bin/tpp-am"
  '';
  passthru = {
    inherit backend termux-am;
    tpp = tppCli;
    tests.dispatcher-help = dispatcherHelp;
  };
  meta = {
    description = "TermuxPP Android API commands for the managed Linux guest";
    platforms = [ "aarch64-linux" ];
    license = with lib.licenses; [
      mit
      gpl3Only
    ];
  };
}
