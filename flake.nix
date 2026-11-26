{
  description = "TermuxPP reproducible Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # Rust toolchain with the aarch64-linux-android std for nixpp's Bionic build.
    fenix = {
      url = "github:nix-community/fenix/monthly";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    # rbw fork (Android Keystore unlock, Termux support), built from source for
    # the bootstrap's prefix (pkgs/nixpp/rbw-android.nix). Not a flake input
    # proper: only its source is used, so its own inputs stay out of the lock.
    rbw-src = {
      url = "github:pschmitt/rbw";
      flake = false;
    };
    # Sources (MIT) of the nixpp launcher, bootstrap seed and Android API
    # bridge (pkgs/nixpp/bootstrap-seed.nix); no droid-nix binary is used.
    droid-nix-src = {
      url = "github:lassulus/droid-nix/bb08d50117568e9994b6d87c918c4c2d7b5de9b1";
      flake = false;
    };
  };

  outputs =
    { self, nixpkgs, ... }@inputs:
    let
      nixppFor = pkgs: import ./pkgs/nixpp/packages.nix { inherit inputs pkgs; };
      termuxPackages = nixpkgs.legacyPackages.aarch64-linux.callPackage ./pkgs/termux-packages { };
      armPackages =
        removeAttrs (nixppFor nixpkgs.legacyPackages.aarch64-linux) [
          "nixpp-runtime-termux"
          "nixpp-runtime-termux-seed"
          "rbw-termux"
        ]
        // adbShellFor nixpkgs.legacyPackages.aarch64-linux
        // tmuxPluginFor nixpkgs.legacyPackages.aarch64-linux;
      # The tmux plugin that reports windows to the app, for any host running tmux.
      tmuxPluginFor = pkgs: {
        tmux-termuxpp = pkgs.callPackage ./pkgs/tmux-termuxpp { };
      };
      # Host-side ADB client built with a caller's Nixpkgs.
      adbShellFor = pkgs: {
        tpp-adb-shell = pkgs.callPackage ./pkgs/tpp-adb-shell { };
      };
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [
          "35"
          "36"
        ];
        buildToolsVersions = [
          "35.0.0"
          "36.0.0"
        ];
        includeNDK = true;
        ndkVersions = [ "29.0.14206865" ];
        includeEmulator = false;
        includeSystemImages = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
      ghosttyAndroidArchive = pkgs.fetchurl {
        url = "https://codeload.github.com/sylirre/ghostty-android-terminal/tar.gz/b75be1c28d606c2206616aacf1489e063f4bd751";
        hash = "sha256-AWVm+sJuf/xJ3Xi8piwRaFOYioD14pcDkqkMxd6mktQ=";
      };

      gradle = pkgs.gradle_8;
      # cargo and the Android std for every ABI of the APK: app/tpp-native.gradle
      # builds the Rust tpp executable (crates tpp-cli and tpp-session) with it,
      # through -PtppCargo.
      fenixPkgs = inputs.fenix.packages.${system};
      tppRust = fenixPkgs.combine [
        fenixPkgs.minimal.cargo
        fenixPkgs.minimal.rustc
        fenixPkgs.targets.aarch64-linux-android.latest.rust-std
        fenixPkgs.targets.armv7-linux-androideabi.latest.rust-std
        fenixPkgs.targets.i686-linux-android.latest.rust-std
        fenixPkgs.targets.x86_64-linux-android.latest.rust-std
      ];
      java = pkgs.jdk17_headless;
      bootstrapVersion = "2026.02.12-r1%2Bapt.android-7";
      gradleTasks = [
        ":app:assembleDebug"
      ];
      bootstraps = {
        aarch64 = pkgs.fetchurl {
          url = "https://github.com/termux/termux-packages/releases/download/bootstrap-${bootstrapVersion}/bootstrap-aarch64.zip";
          hash = "sha256-6irrqIGeUX23EfjDI2nonnxSzuc+B5MP+RGF4auT9PM=";
        };
        arm = pkgs.fetchurl {
          url = "https://github.com/termux/termux-packages/releases/download/bootstrap-${bootstrapVersion}/bootstrap-arm.zip";
          hash = "sha256-o49NOy9zX4O+K/VO/0Y+htwyo+L5+GHBVXxDeNJJwBg=";
        };
        i686 = pkgs.fetchurl {
          url = "https://github.com/termux/termux-packages/releases/download/bootstrap-${bootstrapVersion}/bootstrap-i686.zip";
          hash = "sha256-9bwLAlufO0ILX8ru/AZPiI9fIqDW/XCQ9KrAwz6zVVs=";
        };
        x86_64 = pkgs.fetchurl {
          url = "https://github.com/termux/termux-packages/releases/download/bootstrap-${bootstrapVersion}/bootstrap-x86_64.zip";
          hash = "sha256-t/0PLjpN5TS+MUT5+RrMdoYw/EY+rxNKsuZMVF6DT3o=";
        };
      };
      apk = pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
        pname = "termuxpp-debug-apk";
        version = "1.0.0";
        outputs = [ "out" ];
        src = ./.;
        nativeBuildInputs = [
          gradle
          java
          pkgs.patchelf
          tppRust
        ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        TERMUX_GHOSTTY_ARCHIVE = ghosttyAndroidArchive;
        ANDROID_USER_HOME = "/tmp/termuxpp-android-user";
        JAVA_HOME = java.home;
        TERMUX_GIT_REVISION = "0000000";
        TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS = "0";
        TERMUX_APK_VERSION_TAG = "apt-android-7-debug";
        SOURCE_DATE_EPOCH = "1";
        LANG = "C.UTF-8";
        LC_ALL = "C.UTF-8";
        TZ = "UTC";

        mitmCache = gradle.fetchDeps {
          pkg = finalAttrs.finalPackage;
          data = ./nix/deps.json;
          bwrapFlags = "--dir /bin --symlink ${pkgs.bash}/bin/bash /bin/sh";
        };
        gradleBuildTask = pkgs.lib.concatStringsSep " " gradleTasks;
        gradleUpdateTask = pkgs.lib.concatStringsSep " " gradleTasks;
        preGradleUpdate = ''
          gradleFlagsArray+=(--write-verification-metadata sha256)
        '';
        gradleFlags = [
          "--no-daemon"
          "-Pandroid.aapt2FromMavenOverride=${sdk}/build-tools/36.0.0/aapt2"
          "-PtppCargo=${tppRust}/bin/cargo"
        ];

        preBuild = ''
          mkdir -p "$ANDROID_USER_HOME"
          install -Dm644 ${bootstraps.aarch64} app/src/main/cpp/bootstrap-aarch64.zip
          install -Dm644 ${bootstraps.arm} app/src/main/cpp/bootstrap-arm.zip
          install -Dm644 ${bootstraps.i686} app/src/main/cpp/bootstrap-i686.zip
          install -Dm644 ${bootstraps.x86_64} app/src/main/cpp/bootstrap-x86_64.zip
        '';
        installPhase = ''
          mkdir -p "$out"
          cp app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk "$out/termuxpp-debug.apk"
        '';
      });
      androidShell = pkgs.mkShell {
        packages = [
          gradle
          java
          android.androidsdk
          android.platform-tools
          pkgs.git
          pkgs.patchelf
          tppRust
        ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        TERMUX_GHOSTTY_ARCHIVE = ghosttyAndroidArchive;
        JAVA_HOME = java.home;
        TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS = "0";
        TERMUX_APK_VERSION_TAG = "apt-android-7-debug";
        shellHook = ''
          export PATH="${sdk}/platform-tools:${sdk}/build-tools/36.0.0:$PATH"
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/36.0.0/aapt2''${GRADLE_OPTS:+ $GRADLE_OPTS}"
        '';
      };
    in
    {
      # nixpp built with a caller's Nixpkgs, e.g. nixos-config's guest pkgs.
      lib.nixpp = {
        packagesFor = nixppFor;
        # Tests and assets that nixos-config's system checks run against.
        src = ./pkgs/nixpp;
      };

      # The host-side ADB client for a caller's Nixpkgs (nixos-config:
      # `inputs.termuxpp.lib.adbShell.packagesFor pkgs`).
      lib.adbShell.packagesFor = adbShellFor;

      # The tmux plugin for a caller's Nixpkgs (nixos-config:
      # `inputs.termuxpp.lib.tmux.packagesFor pkgs`).
      lib.tmux.packagesFor = tmuxPluginFor;

      circus.consumerBuilds.nixos-config = {
        repository = "pschmitt/nixos-config";
        ref = "main";
        input = "termuxpp";
        follows = [
          "nixpkgs"
        ];
        packages.aarch64-linux = [
          "nixpp"
          "termuxpp-api"
          "termuxpp-clipboard"
          "termuxpp-ip"
          "termuxpp-rish"
          "termuxpp-storage"
          "tpp-adb-shell"
        ];
      };

      # Cache the Android smoke-test packages. Other APT packages build on demand.
      hydraJobs = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-linux" ] (target: {
        packages =
          if target == "aarch64-linux" then
            armPackages // { inherit (termuxPackages) bash htop; }
          else
            self.packages.${target};
        checks = self.checks.${target} or { };
      });

      packages.aarch64-linux = termuxPackages // armPackages;
      legacyPackages.aarch64-linux.termuxPkgs = termuxPackages;
      # Only what builds here: the guest helpers are aarch64-only (packages.aarch64-linux)
      # and Circus builds every attribute of this set.
      packages.${system} =
        pkgs.lib.filterAttrs (_: drv: pkgs.lib.meta.availableOn pkgs.stdenv.hostPlatform drv) (
          nixppFor pkgs
        )
        // adbShellFor pkgs
        // tmuxPluginFor pkgs
        // {
          default = apk;
          inherit apk;
        };
      apps.${system}.update-deps = {
        type = "app";
        program = "${apk.mitmCache.updateScript}";
      };
      # Android SDK (platforms 35/36, build-tools 35.0.0/36.0.0, NDK 29), JDK 17 and Gradle, with
      # aapt2 from the SDK: `nix develop` (or `nix develop .#android`), then ./gradlew.
      devShells.${system} = {
        default = androidShell;
        android = androidShell;
      };
    };
}
