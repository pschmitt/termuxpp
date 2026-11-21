{
  coreutils,
  termux-am,
  writeShellApplication,
  callPackage,
}:
writeShellApplication {
  name = "tpp-setup-storage";
  runtimeInputs = [
    coreutils
    termux-am
  ];
  text = ''
    exec -a tpp-setup-storage ${callPackage ./tpp.nix { }}/bin/tpp "$@"
  '';
}
