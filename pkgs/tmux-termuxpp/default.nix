{ lib, tmuxPlugins }:
# The TermuxPP tmux plugin: load `${plugin}/share/tmux-plugins/termuxpp/termuxpp.tmux`,
# or list it in Home Manager's `programs.tmux.plugins`.
tmuxPlugins.mkTmuxPlugin {
  pluginName = "termuxpp";
  rtpFilePath = "termuxpp.tmux";
  version = "1";
  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./termuxpp.tmux
      ./scripts
    ];
  };
  meta = {
    description = "Report tmux windows to the TermuxPP Android terminal and select them from it";
    license = lib.licenses.gpl3Only;
    platforms = lib.platforms.unix;
  };
}
