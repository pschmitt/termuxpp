{
  lib,
  runCommand,
  fetchurl,
  dpkg,
  patchelf,
  runtimeShell,
}:

let
  version = "5.3.20";
  deb =
    path: sha256:
    fetchurl {
      url = "https://packages.termux.dev/apt/termux-main/pool/main/${path}_aarch64.deb";
      inherit sha256;
    };

  # Bash and its shared libraries from the same Termux repository snapshot.
  # termux-tools provides the native prefix integration and startup helpers.
  packages = [
    (deb "b/bash/bash_${version}" "26b5b3ab5b0ac0e3b372ac1e7ddd89270519c1eb25b88fe4175485c2de8086b2")
    (deb "liba/libandroid-support/libandroid-support_29-1" "f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517")
    (deb "libi/libiconv/libiconv_1.19.1" "a1c6323eff4f660e16ae6f5832356444b3269c19a4f61146355a49e7c4be5ed1")
    (deb "r/readline/readline_8.3.6" "616cc73009bc0223f61caed8dd09455714455aa5ea5e180126e2f69c4c775136")
    (deb "n/ncurses/ncurses_6.6.20260307+really6.5.20250830" "f44bbfdc3d42ec0217bffa978309390e59cea5a48a9a83226d4a496c42ad0b99")
  ];
in
runCommand "termux-bash-${version}"
  {
    nativeBuildInputs = [
      dpkg
      patchelf
    ];
    inherit version;
    meta = {
      description = "Termux Bash for aarch64 Android guests";
      homepage = "https://github.com/termux/termux-packages/tree/master/packages/bash";
      license = lib.licenses.gpl3Plus;
      mainProgram = "bash";
      platforms = [ "aarch64-linux" ];
    };
  }
  ''
    for package in ${lib.escapeShellArgs packages}
    do
      dpkg-deb --extract "$package" root
    done

    mkdir -p "$out/bin" "$out/libexec"
    prefix="$out/libexec/termux"
    mv root/data/data/com.termux/files/usr "$prefix"

    # Keep Bionic libraries out of LD_LIBRARY_PATH for commands Bash starts.
    patchelf --page-size 16384 --set-rpath "$prefix/lib" "$prefix/bin/bash"
    for library in android-support iconv ncursesw readline
    do
      patchelf --page-size 16384 --set-rpath "$prefix/lib" \
        "$(readlink -f "$prefix/lib/lib$library.so")"
    done

    cat > "$out/bin/bash" <<EOF
    #!${runtimeShell}
    exec /system/bin/linker64 "$prefix/bin/bash" --noprofile --norc "\$@"
    EOF
    chmod +x "$out/bin/bash"
  ''
