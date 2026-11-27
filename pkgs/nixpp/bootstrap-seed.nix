# The nixpp runtime's launcher and bootstrap seed, built from the pinned
# droid-nix sources (MIT, see the droid-nix-src flake input) instead of taking them from
# a prebuilt droid-nix APK.
#
# - share/nixpp/launcher: the static seccomp launcher (bootstrap/launcher).
# - share/nixpp/manifest.json: the pinned session packages (session-manifest.nix).
# - share/nixpp/bootstrap-aarch64.zip: a registered chroot store holding
#   static nix and busybox plus the Linux nixpp closure, plus EXECUTABLES.txt and SYMLINKS.txt
#   (ZIP entries cannot carry modes or links; nixpp restores both).
#
# Everything else comes from cache.nixos.org during `nixpp init`. nixpp writes
# /etc, /bin/sh and the guest user itself, so droid-nix's login scripts and
# etc module are not part of the seed.
{
  lib,
  pkgs,
  inputs,
}:
let
  src = inputs.droid-nix-src;

  # Static aarch64 binaries, cross-compiled against musl; cache.nixos.org has
  # no static aarch64 nix.
  static = pkgs.pkgsCross.aarch64-multiplatform-musl.pkgsStatic;

  # The nix command alone: nix-everything also pulls in nix-nswrapper, which
  # the runtime cannot use (no user namespaces, sandbox = false).
  nix =
    (static.nixVersions.nixComponents_2_35.overrideScope (
      _: prev: {
        nix-util = prev.nix-util.overrideAttrs (old: {
          patches = old.patches or [ ] ++ [ "${src}/bootstrap/nix-util-syscall-h.patch" ];
        });
        nix-cli = prev.nix-cli.overrideAttrs { stripAllList = [ "bin" ]; };
      }
    )).nix-cli;
  inherit (static) busybox;

  # Android's app seccomp filter traps openat2 (Android 15); the upstream
  # handler re-translated its own reissue until the stack overflowed.
  # The SIGSYS handler blocks asynchronous signals: Go's handlers nesting
  # into it read a clobbered goroutine register. It still runs on the
  # current stack (no SA_ONSTACK: Rust's alternate stacks are too small for
  # it), which overflows Go's goroutine stacks until it gets a stack of its own.
  #
  # The exempt syscall stub (allow_start..allow_end, 0x30 bytes with the
  # rt_sigreturn trampoline at +0x24) is an ABI between launcher versions:
  # every running guest process carries a seccomp filter that exempts exactly
  # its addresses, and a newer launcher exec'd from such a process runs under
  # that filter. A moved stub killed every exec on zf10. So the stub lives in
  # its own section at a fixed address, with the C code from 0x1000006000,
  # and the build fails if it is anywhere else. Earlier launchers put it
  # wherever their code ended, so an installed runtime keeps the address of
  # the launcher it was bootstrapped with: `withStub ADDRESS` builds the
  # launcher for such a runtime. New bootstraps use 0x1000005690.
  mkLauncher =
    stub:
    (static.callPackage "${src}/bootstrap/launcher" { }).overrideAttrs (old: {
      patches = old.patches or [ ] ++ [
        ./launcher-foreign-trap.patch
        ./launcher-sigsys-onstack.patch
        ./launcher-stub-pinned.patch
      ];
      buildPhase = ''
        if [ "$((${stub} & 0xf))" != 0 ] || [ "$((${stub} + 0x30))" -gt "$((0x1000006000))" ] \
          || [ "$((${stub}))" -lt "$((0x1000001000))" ]; then
          echo "launcher: stub address ${stub} must be 16-aligned within 0x1000001000..0x1000005fd0" >&2
          exit 1
        fi
        ${static.stdenv.cc.targetPrefix}cc -O2 -Wall -static -no-pie -fno-pie \
          -Wl,-Ttext-segment=0x1000000000 \
          -Wl,--section-start=.launcher_stub=${stub} \
          -Wl,-Ttext=0x1000006000 \
          -o launcher launcher.c stub.S
      '';
      # The check is part of the install: nixpkgs skips installCheck when
      # cross-compiling, and upstream's installPhase runs no hooks.
      installPhase = ''
        install -D -m 0755 launcher $out/bin/launcher
        symbols=$(${static.stdenv.cc.targetPrefix}nm "$out/bin/launcher")
        for expected in "allow_start 0" "sig_restorer 36" "allow_end 48"; do
          name=''${expected% *}
          want=$((${stub} + ''${expected#* }))
          got=$(printf '%s\n' "$symbols" | awk -v n="$name" '$3 == n { print "0x" $1 }')
          if [ "$((got))" != "$want" ]; then
            echo "launcher: $name is at ''${got:-nowhere}, the stub ABI needs $(printf 0x%x "$want")" >&2
            exit 1
          fi
        done
      '';
      passthru = old.passthru or { } // {
        withStub = mkLauncher;
        inherit stub;
      };
    });
  launcher = mkLauncher "0x1000005690";

  guestNixpp = pkgs.pkgsCross.aarch64-multiplatform.callPackage ./. { };
  manifest = import ./session-manifest.nix { inherit pkgs inputs guestNixpp; };

  closure = pkgs.closureInfo {
    rootPaths = [
      nix
      busybox
      guestNixpp
    ];
  };
in
pkgs.runCommand "termuxpp-nix-bootstrap-seed"
  {
    nativeBuildInputs = [
      pkgs.nix
      pkgs.sqlite
      pkgs.zip
    ];
    passthru = {
      inherit
        launcher
        nix
        busybox
        manifest
        ;
    };
    meta = {
      description = "Launcher and bootstrap seed for the TermuxPP nixpp runtime";
      license = lib.licenses.mit;
      # Builds the static AArch64 seed from x86_64 or AArch64 Linux.
      platforms = [
        "x86_64-linux"
        "aarch64-linux"
      ];
    };
  }
  ''
    root=$PWD/seed
    mkdir -p "$root/nix/store"
    for path in $(< ${closure}/store-paths); do
      cp --archive "$path" "$root$path"
    done
    export HOME=$TMPDIR
    nix-store --store "$root" --load-db < ${closure}/registration
    rm -f "$root/nix/var/nix/db/reserved"
    # Fold the write-ahead log into db.sqlite so the seed holds one consistent
    # database file instead of transient -wal/-shm companions.
    # Registration times are the build time; pin them for a reproducible seed.
    sqlite3 "$root/nix/var/nix/db/db.sqlite" \
      'UPDATE ValidPaths SET registrationTime = 1; PRAGMA journal_mode=DELETE;' > /dev/null
    rm -f "$root"/nix/var/nix/db/db.sqlite-{wal,shm}
    chmod -R u+w "$root/nix"
    mkdir -p "$root"/nix/var/nix/{profiles,gcroots}

    cd "$root"
    # Paths relative to the runtime root, as nixpp's extraction expects.
    find nix -type f -perm -u+x | LC_ALL=C sort > EXECUTABLES.txt
    find nix -type l | LC_ALL=C sort | while read -r link; do
      printf '%s←%s\n' "$(readlink "$link")" "$link"
      rm "$link"
    done > SYMLINKS.txt

    # Reproducible archive: fixed timestamps, sorted members, no extra fields.
    find . -exec touch -h -d @1 {} +
    mkdir -p "$out/share/nixpp"
    find . -mindepth 1 | LC_ALL=C sort | zip -q -X -9 -@ "$out/share/nixpp/bootstrap-aarch64.zip"
    install -m 0755 ${launcher}/bin/launcher "$out/share/nixpp/launcher"
    install -m 0644 ${manifest} "$out/share/nixpp/manifest.json"
  ''
