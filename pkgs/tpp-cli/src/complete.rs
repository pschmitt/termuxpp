//! `tpp --complete WORD...`: the candidates for the last WORD, the words typed
//! after "tpp" in front of it being the others. The shell completion scripts
//! call it. Status 1 or no output means there is nothing to suggest: the shell
//! should complete file names. A candidate that ends in `=` is an option that
//! takes an attached value (no space is added after it).

use std::collections::{HashMap, HashSet};
use std::ffi::OsString;
use std::io::{self, Write};
use std::process::{Command, Stdio};

use crate::cli::{group_names, resolve_prefix, App};
use crate::native;

/// The names that start with `cur`.
fn matching<S: AsRef<str>>(cur: &str, names: &[S]) -> Vec<String> {
    names
        .iter()
        .map(AsRef::as_ref)
        .filter(|n| n.starts_with(cur))
        .map(str::to_string)
        .collect()
}

/// A completion result: the status and the candidates.
type Answer = (i32, Vec<String>);

const NOTHING: Answer = (1, Vec::new());

/// Complete the arguments of the command `group` `action`. `json_seen` is set
/// if --json was typed already. `args` are the words typed after the command;
/// the last one is the word to complete.
fn complete_arguments(
    app: &App,
    json_seen: bool,
    group: &str,
    action: &str,
    args: &[String],
) -> Answer {
    let n = args.len();
    let cur = args[n - 1].as_str();
    let prev = if n >= 2 { args[n - 2].as_str() } else { "" };

    let mut opts: Vec<String> = Vec::new();
    let mut takes: HashMap<String, String> = HashMap::new();
    let mut positionals: Vec<String> = Vec::new();
    for word in app.table.option_words(group, action) {
        if !word.starts_with('-') {
            positionals.push(word.clone());
            continue;
        }
        let name = word.split([':', '=']).next().unwrap_or("");
        if let Some((name, choices)) = word.split_once(':') {
            takes.insert(name.to_string(), choices.to_string());
            opts.push(name.to_string());
        } else if word.ends_with('=') {
            opts.push(format!("{name}="));
        } else {
            opts.push(name.to_string());
        }
    }

    // The arguments typed so far (not the one to complete) may make it raw
    let has_json = app
        .table
        .find(group, action)
        .is_none_or(|e| e.effective_class(&args[..n - 1]) != "raw");

    // Words typed before the one to complete: skip option values, count the rest
    let mut used: HashSet<&str> = HashSet::new();
    let mut positional = 0;
    let mut skip = false;
    for word in &args[..n - 1] {
        if skip {
            skip = false;
        } else if word == "--" {
            return NOTHING;
        } else if word.starts_with('-') {
            used.insert(word.split('=').next().unwrap_or(""));
            if takes.contains_key(word) {
                skip = true;
            }
        } else {
            positional += 1;
        }
    }

    if skip {
        // The word to complete is the value of an option
        return match takes.get(prev).filter(|c| !c.is_empty()) {
            Some(choices) => {
                let list: Vec<&str> = choices.split(',').collect();
                (0, matching(cur, &list))
            }
            None => NOTHING,
        };
    }

    if cur.starts_with('-') {
        let mut flags: Vec<String> = opts
            .iter()
            .filter(|w| !used.contains(w.strip_suffix('=').unwrap_or(w)))
            .cloned()
            .collect();
        flags.push("--help".to_string());
        if has_json && !json_seen && !used.contains("--json") {
            flags.push("--json".to_string());
        }
        return (0, matching(cur, &flags));
    }
    if positional == 0 && !positionals.is_empty() {
        return (0, matching(cur, &positionals));
    }
    NOTHING
}

/// The candidates for the last of `words`; the text goes to `out`.
fn complete_words(app: &App, words: &[String]) -> Answer {
    let n = words.len();
    if n == 0 {
        return NOTHING;
    }
    let cur = words[n - 1].as_str();
    let gi = words[..n - 1].iter().take_while(|w| *w == "--json").count();
    if gi == n - 1 {
        if cur.starts_with('-') {
            return (0, matching(cur, &["--help", "--json"]));
        }
        return (0, matching(cur, &group_names(app)));
    }

    let names = group_names(app);
    let Ok(Some(group)) = resolve_prefix(&words[gi], names.iter().map(String::as_str)) else {
        return NOTHING;
    };
    let rest = &words[gi + 1..];
    if native::is_native(group) {
        return (0, native::complete(group, rest));
    }
    if app.table.is_group(group) {
        let leading = rest[..rest.len() - 1]
            .iter()
            .take_while(|word| *word == "--json")
            .count();
        let json_seen = gi > 0 || leading > 0;
        let mut prior = rest[leading..rest.len() - 1].to_vec();
        if app.table.find(group, "-").is_some() {
            prior.push(cur.to_string());
            return complete_arguments(app, json_seen, group, "-", &prior);
        }
        if prior.is_empty() {
            if cur.starts_with('-') {
                let mut flags = vec!["--help"];
                if !json_seen {
                    flags.push("--json");
                }
                return (0, matching(cur, &flags));
            }
            let actions: Vec<&str> = app.table.rows(group).map(|e| e.action.as_str()).collect();
            return (0, matching(cur, &actions));
        }
        let typed = prior.remove(0);
        let Ok(Some(action)) = resolve_prefix(
            &typed,
            app.table.rows(group).map(|entry| entry.action.as_str()),
        ) else {
            return NOTHING;
        };
        prior.push(cur.to_string());
        return complete_arguments(app, json_seen, group, action, &prior);
    }
    if group == "help" {
        if rest.len() == 1 {
            return (0, matching(cur, &names));
        }
        if let [typed, _] = rest {
            if let Ok(Some(group)) = resolve_prefix(typed, names.iter().map(String::as_str)) {
                if native::is_native(group) {
                    return (0, native::complete(group, &[cur.to_owned()]));
                }
                let actions: Vec<&str> = app
                    .table
                    .rows(group)
                    .filter(|entry| entry.action != "-")
                    .map(|entry| entry.action.as_str())
                    .collect();
                return (0, matching(cur, &actions));
            }
        }
        return NOTHING;
    }
    if let Some(plugin) = app.ctx.find_plugin(group) {
        // The plugin completes its own words: PLUGIN __complete WORD...
        // Its output is ours and so is its status.
        let status = Command::new(plugin)
            .arg("__complete")
            .args(rest)
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .status();
        return (status.map_or(1, |s| s.code().unwrap_or(1)), Vec::new());
    }
    NOTHING
}

pub fn run(app: &App, args: &[OsString]) -> i32 {
    let words: Vec<String> = args
        .iter()
        .map(|a| a.to_string_lossy().into_owned())
        .collect();
    let (rc, lines) = complete_words(app, &words);
    let mut out = io::stdout().lock();
    for line in lines {
        let _ = writeln!(out, "{line}");
    }
    let _ = out.flush();
    rc
}
