#compdef tpp
# zsh completion for tpp. The candidates come from "tpp --complete", which
# knows the commands, the table and the plugins (it asks a plugin with
# "PLUGIN __complete WORD...").

_tpp() {
  local out rc
  local -a candidates plain attached
  out="$(tpp --complete "${(@)words[2,CURRENT]}" 2> /dev/null)"
  rc=$?
  if (( rc != 0 )) || [[ -z "$out" ]]
  then
    # Nothing to suggest: complete file names
    _files
    return
  fi
  candidates=("${(@f)out}")
  # Options that take an attached value (--name=) must not get a space
  attached=("${(@M)candidates:#*=}")
  plain=("${(@)candidates:#*=}")
  if (( ${#plain} ))
  then
    compadd -a plain
  fi
  if (( ${#attached} ))
  then
    compadd -S '' -a attached
  fi
}

_tpp "$@"

# vim: set ft=zsh et ts=2 sw=2 :
