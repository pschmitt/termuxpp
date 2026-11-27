# The slim outer environment: the native TermuxPP prefix as a bootstrap
# archive in the format the app's installer reads (paths relative to $PREFIX,
# a SYMLINKS.txt of "target←path" lines). It holds TermuxPP's own files only;
# everything else lives in the Nix guest, and generations keep these files
# current (native/prefix.json), so the outer shell never needs a reinstall.
#
# setupConfig: the private etc/nixpp/setup.conf of `nixpp setup`, for private
# builds only (it carries the rworker controller and cache settings).
{
  lib,
  runCommand,
  zip,
  cacert,
  runtime,
  seed,
  rbw,
  setupConfig ? null,
}:
runCommand "termuxpp-outer-bootstrap" { nativeBuildInputs = [ zip ]; } ''
  tree=$PWD/tree
  mkdir -p "$tree"/{bin,lib,tmp,etc/tls,share/nixpp,share/licenses/rbw}
  install -m 0700 ${runtime}/bin/nixpp "$tree/bin/nixpp"
  printf '#!/system/bin/sh\nexec nixpp setup "$@"\n' > "$tree/bin/nixpp-setup"
  chmod 0700 "$tree/bin/nixpp-setup"
  install -m 0700 ${rbw}/bin/rbw ${rbw}/bin/rbw-agent "$tree/bin/"
  install -m 0644 ${rbw}/share/licenses/rbw/LICENSE "$tree/share/licenses/rbw/LICENSE"
  install -m 0700 ${seed}/share/nixpp/launcher "$tree/share/nixpp/launcher"
  install -m 0644 ${seed}/share/nixpp/manifest.json ${seed}/share/nixpp/bootstrap-aarch64.zip \
    "$tree/share/nixpp/"
  install -m 0644 ${cacert}/etc/ssl/certs/ca-bundle.crt "$tree/etc/tls/cert.pem"
  ${lib.optionalString (setupConfig != null) ''
    install -D -m 0600 ${setupConfig} "$tree/etc/nixpp/setup.conf"
  ''}
  # The installer requires one symlink; Android's shell is the prefix's sh.
  printf '%s\n' '/system/bin/sh←bin/sh' > "$tree/SYMLINKS.txt"
  mkdir -p "$out"
  # Reproducible: one timestamp for every entry.
  find "$tree" -exec touch -h -d @315532800 {} +
  (cd "$tree" && find . -mindepth 1 | LC_ALL=C sort | TZ=UTC zip -q -X -9 -@ "$out/bootstrap-aarch64.zip")
  sha256sum "$out/bootstrap-aarch64.zip" | cut -d' ' -f1 > "$out/bootstrap-aarch64.zip.sha256"
''
