//! The command and option tables (see `commands.rs`) as the dispatcher uses
//! them. An action of `-` stands for a command without one.

use crate::commands::{long_options, Command, Options};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub group: String,
    pub action: String,
    pub imp: String,
    pub class: String,
    pub args: String,
    pub desc: String,
}

impl Entry {
    /// The words that select the command: "wifi scan" or "battery".
    pub fn name(&self) -> String {
        if self.action == "-" {
            self.group.clone()
        } else {
            format!("{} {}", self.group, self.action)
        }
    }

    /// How the output of the command is treated for the given arguments.
    ///
    /// The class field is a class, or a class followed by overrides: in
    /// "auto,sign=raw" the class is auto, but raw if the first argument is
    /// "sign".
    pub fn effective_class(&self, args: &[String]) -> String {
        let mut parts = self.class.split(',');
        let mut class = parts.next().unwrap_or("").to_string();
        for part in parts {
            let (sel, value) = part.split_once('=').unwrap_or((part, part));
            if selector_matches(sel, args) {
                class = value.to_string();
            }
        }
        class
    }
}

/// Whether the table selector `sel` applies to the arguments. A selector is
/// either a word (the first argument is that word) or a short option "-X" (an
/// option cluster in the leading options contains X, as in -X or -rX).
pub fn selector_matches(sel: &str, args: &[String]) -> bool {
    let Some(letter) = sel.strip_prefix('-') else {
        return args.first().is_some_and(|a| a == sel);
    };
    for arg in args {
        if arg == "--" || !arg.starts_with('-') || arg.len() < 2 {
            return false;
        }
        if !arg.starts_with("--") && arg[1..].contains(letter) {
            return true;
        }
    }
    false
}

struct OptionRow {
    group: String,
    action: String,
    words: Vec<String>,
}

pub struct Table {
    pub entries: Vec<Entry>,
    /// the groups in the order of their first row
    pub groups: Vec<String>,
    options: Vec<OptionRow>,
}

impl Table {
    /// The tables built into the binary.
    pub fn builtin() -> Table {
        Table::new(crate::commands::COMMANDS, crate::commands::OPTIONS)
    }

    pub fn new(commands: &[Command], options: &[Options]) -> Table {
        let mut entries = Vec::new();
        let mut groups: Vec<String> = Vec::new();
        for c in commands {
            let entry = Entry {
                group: c.group.to_string(),
                action: c.action.to_string(),
                imp: c.imp.to_string(),
                class: c.class.to_string(),
                args: c.args.to_string(),
                desc: c.desc.to_string(),
            };
            if !groups.contains(&entry.group) {
                groups.push(entry.group.clone());
            }
            entries.push(entry);
        }
        let rows = options
            .iter()
            .map(|o| OptionRow {
                group: o.group.to_string(),
                action: o.action.to_string(),
                words: o
                    .words
                    .split_whitespace()
                    .map(|word| {
                        let (flag, suffix) = word.split_once(':').unwrap_or((word, ""));
                        let mappings = commands
                            .iter()
                            .find(|c| c.group == o.group && c.action == o.action)
                            .map_or(&[][..], |c| long_options(c.imp));
                        if let Some((_, name)) = mappings.iter().find(|(short, _)| *short == flag) {
                            let suffix = if word.contains(':') {
                                format!(":{suffix}")
                            } else {
                                String::new()
                            };
                            format!("{name}{suffix}")
                        } else {
                            word.to_string()
                        }
                    })
                    .collect(),
            })
            .collect();
        Table {
            entries,
            groups,
            options: rows,
        }
    }

    pub fn is_group(&self, name: &str) -> bool {
        self.groups.iter().any(|g| g == name)
    }

    pub fn find(&self, group: &str, action: &str) -> Option<&Entry> {
        self.entries
            .iter()
            .find(|e| e.group == group && e.action == action)
    }

    /// The rows of a group.
    pub fn rows<'a>(&'a self, group: &'a str) -> impl Iterator<Item = &'a Entry> + 'a {
        self.entries.iter().filter(move |e| e.group == group)
    }

    /// The option words of a command; empty without a row.
    pub fn option_words(&self, group: &str, action: &str) -> &[String] {
        self.options
            .iter()
            .find(|r| r.group == group && r.action == action)
            .map_or(&[], |r| r.words.as_slice())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(args: &[&str]) -> Vec<String> {
        args.iter().map(|a| a.to_string()).collect()
    }

    fn cmd(
        group: &'static str,
        action: &'static str,
        imp: &'static str,
        args: &'static str,
        desc: &'static str,
    ) -> Command {
        Command {
            group,
            action,
            imp,
            class: "auto",
            args,
            desc,
        }
    }

    #[test]
    fn builds_rows_and_groups() {
        let t = Table::new(
            &[
                cmd("wifi", "scan", "wifi-scan", "-", "Scan"),
                cmd("wifi", "enable", "wifi-enable", "true", "On|off"),
                cmd("battery", "-", "battery-status", "-", "Battery"),
            ],
            &[
                Options {
                    group: "wifi",
                    action: "scan",
                    words: "-x --y=",
                },
                Options {
                    group: "battery",
                    action: "-",
                    words: "-z:a,b",
                },
            ],
        );
        assert_eq!(t.groups, ["wifi", "battery"]);
        assert_eq!(t.entries.len(), 3);
        assert_eq!(t.find("wifi", "enable").unwrap().desc, "On|off");
        assert_eq!(t.find("battery", "-").unwrap().name(), "battery");
        assert_eq!(t.find("wifi", "scan").unwrap().name(), "wifi scan");
        assert!(t.find("wifi", "nope").is_none());
        assert_eq!(t.option_words("wifi", "scan"), ["-x", "--y="]);
        assert_eq!(t.option_words("battery", "-"), ["-z:a,b"]);
        assert!(t.option_words("wifi", "enable").is_empty());
        assert_eq!(t.rows("wifi").count(), 2);
    }

    #[test]
    fn the_built_in_table_is_well_formed() {
        let t = Table::builtin();
        assert!(t.entries.len() > 50);
        let mut seen = Vec::new();
        for e in &t.entries {
            for field in [&e.group, &e.action, &e.imp, &e.class, &e.args, &e.desc] {
                assert!(!field.is_empty(), "{e:?}");
            }
            let mut parts = e.class.split(',');
            assert!(
                ["auto", "text", "stream", "raw"].contains(&parts.next().unwrap()),
                "{e:?}"
            );
            for o in parts {
                let (sel, value) = o.split_once('=').expect("override is SEL=CLASS");
                assert!(["auto", "text", "stream", "raw"].contains(&value), "{e:?}");
                let ok = match sel.strip_prefix('-') {
                    Some(l) => l.len() == 1 && l.chars().all(|c| c.is_ascii_alphabetic()),
                    None => sel
                        .chars()
                        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-'),
                };
                assert!(ok, "{e:?}");
            }
            let key = (e.group.clone(), e.action.clone());
            assert!(!seen.contains(&key), "duplicate {key:?}");
            seen.push(key);
        }
        // A group is either one plain command or a set of actions
        for g in &t.groups {
            let actions: Vec<_> = t.rows(g).map(|e| e.action.as_str()).collect();
            if actions.contains(&"-") {
                assert_eq!(actions, ["-"], "{g}");
            }
        }
        for o in crate::commands::OPTIONS {
            let e = t.find(o.group, o.action).unwrap_or_else(|| {
                panic!("options for an unknown command {} {}", o.group, o.action)
            });
            let words = t.option_words(o.group, o.action);
            assert!(!words.is_empty());
            let names: Vec<&str> = words
                .iter()
                .filter(|w| w.starts_with('-'))
                .map(|w| w.split([':', '=']).next().unwrap())
                .collect();
            for (i, n) in names.iter().enumerate() {
                assert!(
                    !names[..i].contains(n),
                    "repeated option {n} in {}",
                    e.name()
                );
                assert!(!["-h", "--help", "--json"].contains(n));
            }
            if e.class.split(',').next() == Some("raw") {
                assert!(!names.contains(&"--json"));
            }
        }
    }

    #[test]
    fn every_command_is_provided_by_the_binary() {
        let t = Table::builtin();
        for e in &t.entries {
            assert!(tpp_api::has(&e.imp), "{} has no implementation", e.imp);
        }
        for imp in tpp_api::IMPLS {
            assert!(
                t.entries.iter().any(|e| e.imp == *imp),
                "{imp} is not in the command table"
            );
        }
    }

    #[test]
    fn word_selectors_look_at_the_first_argument() {
        assert!(selector_matches("sign", &s(&["sign", "x"])));
        assert!(!selector_matches("sign", &s(&["x", "sign"])));
        assert!(!selector_matches("sign", &s(&[])));
    }

    #[test]
    fn option_selectors_look_at_the_leading_options() {
        for yes in [
            &["-e", "cmd"][..],
            &["-r", "-e", "x"],
            &["-re", "x"],
            &["-Ee", "x"],
        ] {
            assert!(selector_matches("-e", &s(yes)), "{yes:?}");
        }
        for no in [
            &["/dev/x", "-e"][..],
            &["--", "-e"],
            &["--e"],
            &["-r", "x", "-e"],
            &["-"],
            &[],
        ] {
            assert!(!selector_matches("-e", &s(no)), "{no:?}");
        }
    }

    #[test]
    fn effective_class_applies_the_last_matching_override() {
        let e = |class: &str| Entry {
            group: "g".into(),
            action: "-".into(),
            imp: "i".into(),
            class: class.into(),
            args: "-".into(),
            desc: "d".into(),
        };
        assert_eq!(e("auto").effective_class(&s(&["sign"])), "auto");
        assert_eq!(e("auto,sign=raw").effective_class(&s(&["sign"])), "raw");
        assert_eq!(e("auto,sign=raw").effective_class(&s(&["x"])), "auto");
        assert_eq!(e("auto,-e=raw").effective_class(&s(&["-re", "x"])), "raw");
        assert_eq!(
            e("auto,-e=raw,-l=text").effective_class(&s(&["-el"])),
            "text"
        );
    }
}
