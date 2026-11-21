# bash completion for tpp. The candidates come from "tpp --complete", which
# knows the commands, the table and the plugins (it asks a plugin with
# "PLUGIN __complete WORD...").

_tpp() {
  local out rc
  COMPREPLY=()
  out="$(tpp --complete "${COMP_WORDS[@]:1:COMP_CWORD}" 2> /dev/null)"
  rc=$?
  if (( rc != 0 )) || [[ -z "$out" ]]
  then
    # Nothing to suggest: complete file names
    compopt -o default 2> /dev/null
    return 0
  fi
  mapfile -t COMPREPLY <<< "$out"
  if (( ${#COMPREPLY[@]} == 1 )) && [[ "${COMPREPLY[0]}" == *= ]]
  then
    # An option that takes an attached value: do not add a space
    compopt -o nospace 2> /dev/null
  fi
}

complete -F _tpp tpp

# vim: set ft=bash et ts=2 sw=2 :
