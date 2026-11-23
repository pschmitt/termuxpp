{
  lib,
  runCommand,
  fetchurl,
  dpkg,
  patchelf,
  python3,
  runtimeShell,
}:

let
  catalog = lib.importJSON ./catalog.json;
  # The Bash launcher skips startup files, so it only needs its linked libraries.
  runtimeDependencies.bash = lib.unique (
    [
      "libandroid-support"
      "libiconv"
      "readline"
    ]
    ++ catalog.packages.readline.dependencies
  );
  fetch =
    name:
    let
      entry = catalog.packages.${name};
    in
    fetchurl {
      url = "${catalog.mirror}/${entry.file}";
      inherit (entry) sha256;
    };
in
lib.mapAttrs (
  name: entry:
  runCommand "termux-${name}-${lib.strings.sanitizeDerivationName entry.version}"
    {
      nativeBuildInputs = [
        dpkg
        patchelf
        python3
      ];
      meta.platforms = [ "aarch64-linux" ];
    }
    (
      if entry.dependencies == null then
        ''
          echo ${lib.escapeShellArg "APT could not resolve ${name} from the pinned Termux catalog."} >&2
          exit 1
        ''
      else
        ''
          python3 ${./assemble.py} "$out" ${runtimeShell} ${fetch name} \
            ${lib.escapeShellArgs (map fetch (runtimeDependencies.${name} or entry.dependencies))}
        ''
    )
) catalog.packages
