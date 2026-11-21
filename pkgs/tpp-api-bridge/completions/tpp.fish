# fish completion for tpp. The candidates come from "tpp --complete", which
# knows the commands, the table and the plugins (it asks a plugin with
# "PLUGIN __complete WORD...").

function __tpp_complete
  set -l words (commandline -opc)
  set -l current (commandline -ct)
  set -e words[1]
  set -l out (tpp --complete $words $current 2> /dev/null)
  if test $status -ne 0; or test (count $out) -eq 0
    # Nothing to suggest: complete file names
    __fish_complete_path $current
    return
  end
  printf '%s\n' $out
end

complete -c tpp -f -a '(__tpp_complete)'
