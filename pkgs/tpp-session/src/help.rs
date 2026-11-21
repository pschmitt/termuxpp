//! The help texts, exactly as Python's argparse printed them at 80 columns.
//!
//! The first paragraph of each text is the usage message, which is also printed in front of
//! usage errors. `tests/fixtures/python-help.txt` holds the same texts as the golden file the
//! unit tests compare with.

pub const MAIN: &str = concat!(
    "usage: tpp-session [-h] [--json]\n",
    "                   {list,close,focus,kill,move,rename,completions,reload-settings} ...\n",
    "\n",
    "Manage TermuxPP terminal sessions.\n",
    "\n",
    "positional arguments:\n",
    "  {list,close,focus,kill,move,rename,completions,reload-settings}\n",
    "    list                list sessions and their IDs\n",
    "    close               hang up a shell or dismiss an exited tab\n",
    "    focus               switch to a session\n",
    "    kill                force-kill a session shell\n",
    "    move                move a session to a one-based position\n",
    "    rename              change the session name\n",
    "    completions         print completion source\n",
    "    reload-settings     reload app properties and styling\n",
    "\n",
    "options:\n",
    "  -h, --help            show this help message and exit\n",
    "  --json                output JSON\n",
    "\n",
    "Every command accepts --json, also in front of the command (tpp --json session\n",
    "...). list prints the sessions as a JSON array. The other commands print a\n",
    "JSON object that names the action and its arguments, for example {\"ok\": true,\n",
    "\"action\": \"close\", \"id\": \"ID\"}; completions prints {\"output\": SCRIPT}.\n",
    "Failures print a message on standard error and exit with a non-zero status,\n",
    "with or without --json.\n",
);

pub const LIST: &str = concat!(
    "usage: tpp-session list [-h] [--json]\n",
    "\n",
    "options:\n",
    "  -h, --help  show this help message and exit\n",
    "  --json      output JSON\n",
);

pub const CLOSE: &str = concat!(
    "usage: tpp-session close [-h] [--json] id\n",
    "\n",
    "positional arguments:\n",
    "  id\n",
    "\n",
    "options:\n",
    "  -h, --help  show this help message and exit\n",
    "  --json      output JSON\n",
);

pub const FOCUS: &str = concat!(
    "usage: tpp-session focus [-h] [--json] id\n",
    "\n",
    "positional arguments:\n",
    "  id\n",
    "\n",
    "options:\n",
    "  -h, --help  show this help message and exit\n",
    "  --json      output JSON\n",
);

pub const KILL: &str = concat!(
    "usage: tpp-session kill [-h] [--json] id\n",
    "\n",
    "positional arguments:\n",
    "  id\n",
    "\n",
    "options:\n",
    "  -h, --help  show this help message and exit\n",
    "  --json      output JSON\n",
);

pub const MOVE: &str = concat!(
    "usage: tpp-session move [-h] [--json] id position\n",
    "\n",
    "positional arguments:\n",
    "  id\n",
    "  position\n",
    "\n",
    "options:\n",
    "  -h, --help  show this help message and exit\n",
    "  --json      output JSON\n",
);

pub const RENAME: &str = concat!(
    "usage: tpp-session rename [-h] [--json] id name\n",
    "\n",
    "positional arguments:\n",
    "  id\n",
    "  name\n",
    "\n",
    "options:\n",
    "  -h, --help  show this help message and exit\n",
    "  --json      output JSON\n",
);

pub const COMPLETIONS: &str = concat!(
    "usage: tpp-session completions [-h] [--json] {bash,fish,zsh}\n",
    "\n",
    "positional arguments:\n",
    "  {bash,fish,zsh}\n",
    "\n",
    "options:\n",
    "  -h, --help       show this help message and exit\n",
    "  --json           output JSON\n",
);

pub const RELOAD_SETTINGS: &str = concat!(
    "usage: tpp-session reload-settings [-h] [--json]\n",
    "\n",
    "options:\n",
    "  -h, --help  show this help message and exit\n",
    "  --json      output JSON\n",
);
