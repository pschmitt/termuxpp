//! Command-line parsing that behaves like the argparse setup of the Python implementation.
//!
//! The Python version had one parser with the global `--json` flag and one subparser per command.
//! The port follows argparse's own algorithm (Python 3.14) step by step, so everything scripts and
//! users could observe stays the same: the usage line in front of every usage error, option
//! abbreviations (`--js`), `-h` glued to other characters, `--` handling, negative numbers as
//! positionals, the error texts and the exit statuses (0 for help, 2 for usage errors).

use crate::help;
use crate::printable::{decimal_value, is_printable, is_space};

/// What the command line asks for.
#[derive(Debug, PartialEq)]
pub enum Outcome {
    /// Run a command.
    Run(Parsed),
    /// Print this on standard output and exit with status 0.
    Help(String),
    /// Print this on standard error and exit with status 2.
    Error(String),
}

#[derive(Debug, PartialEq)]
pub struct Parsed {
    pub json: bool,
    pub command: Command,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Shell {
    Bash,
    Fish,
    Zsh,
}

impl Shell {
    pub fn name(self) -> &'static str {
        match self {
            Shell::Bash => "bash",
            Shell::Fish => "fish",
            Shell::Zsh => "zsh",
        }
    }
}

#[derive(Debug, PartialEq)]
pub enum Command {
    List,
    Close {
        id: String,
    },
    Focus {
        id: String,
    },
    Kill {
        id: String,
    },
    /// `position` is the decimal text of the integer, normalized the way Python prints an `int`.
    Move {
        id: String,
        position: String,
    },
    Rename {
        id: String,
        name: String,
    },
    Completions {
        shell: Shell,
    },
    ReloadSettings,
    Switcher,
}

impl Command {
    /// The command word, which is also the `action` of the JSON result.
    pub fn name(&self) -> &'static str {
        match self {
            Command::List => "list",
            Command::Close { .. } => "close",
            Command::Focus { .. } => "focus",
            Command::Kill { .. } => "kill",
            Command::Move { .. } => "move",
            Command::Rename { .. } => "rename",
            Command::Completions { .. } => "completions",
            Command::ReloadSettings => "reload-settings",
            Command::Switcher => "switcher",
        }
    }
}

/// Parses the arguments after the program name.
pub fn parse(args: &[String]) -> Outcome {
    let namespace = match parse_known(&MAIN, args) {
        Ok(namespace) => namespace,
        Err(Exit::Help(text)) => return Outcome::Help(text),
        Err(Exit::Error(text)) => return Outcome::Error(text),
    };
    if !namespace.unrecognized.is_empty() {
        return Outcome::Error(usage_error(
            &MAIN,
            &format!(
                "unrecognized arguments: {}",
                namespace.unrecognized.join(" ")
            ),
        ));
    }
    let value = |name: &str| namespace.value(name).unwrap_or_default().to_string();
    let command = match namespace.command {
        Some("list") => Command::List,
        Some("close") => Command::Close { id: value("id") },
        Some("focus") => Command::Focus { id: value("id") },
        Some("kill") => Command::Kill { id: value("id") },
        Some("move") => Command::Move {
            id: value("id"),
            position: value("position"),
        },
        Some("rename") => Command::Rename {
            id: value("id"),
            name: value("name"),
        },
        Some("completions") => Command::Completions {
            shell: match namespace.value("shell") {
                Some("fish") => Shell::Fish,
                Some("zsh") => Shell::Zsh,
                _ => Shell::Bash,
            },
        },
        Some("switcher") => Command::Switcher,
        _ => Command::ReloadSettings,
    };
    Outcome::Run(Parsed {
        json: namespace.json,
        command,
    })
}

/// A parser: its program name for messages, its help text and its positional arguments.
struct Spec {
    prog: &'static str,
    help: &'static str,
    positionals: &'static [Positional],
}

struct Positional {
    name: &'static str,
    kind: Kind,
}

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Text,
    Int,
    Shell,
    /// The subcommand: it takes the rest of the command line.
    Command,
}

const ID: Positional = Positional {
    name: "id",
    kind: Kind::Text,
};
const POSITION: Positional = Positional {
    name: "position",
    kind: Kind::Int,
};
const NAME: Positional = Positional {
    name: "name",
    kind: Kind::Text,
};
const SHELL: Positional = Positional {
    name: "shell",
    kind: Kind::Shell,
};
const COMMAND: Positional = Positional {
    name: "command",
    kind: Kind::Command,
};

static MAIN: Spec = Spec {
    prog: "tpp-session",
    help: help::MAIN,
    positionals: &[COMMAND],
};

/// The commands in the order argparse lists them in its error messages.
static COMMANDS: [(&str, Spec); 9] = [
    (
        "list",
        Spec {
            prog: "tpp-session list",
            help: help::LIST,
            positionals: &[],
        },
    ),
    (
        "close",
        Spec {
            prog: "tpp-session close",
            help: help::CLOSE,
            positionals: &[ID],
        },
    ),
    (
        "focus",
        Spec {
            prog: "tpp-session focus",
            help: help::FOCUS,
            positionals: &[ID],
        },
    ),
    (
        "kill",
        Spec {
            prog: "tpp-session kill",
            help: help::KILL,
            positionals: &[ID],
        },
    ),
    (
        "move",
        Spec {
            prog: "tpp-session move",
            help: help::MOVE,
            positionals: &[ID, POSITION],
        },
    ),
    (
        "rename",
        Spec {
            prog: "tpp-session rename",
            help: help::RENAME,
            positionals: &[ID, NAME],
        },
    ),
    (
        "completions",
        Spec {
            prog: "tpp-session completions",
            help: help::COMPLETIONS,
            positionals: &[SHELL],
        },
    ),
    (
        "reload-settings",
        Spec {
            prog: "tpp-session reload-settings",
            help: help::RELOAD_SETTINGS,
            positionals: &[],
        },
    ),
    (
        "switcher",
        Spec {
            prog: "tpp-session switcher",
            help: help::SWITCHER,
            positionals: &[],
        },
    ),
];

const SHELLS: [&str; 3] = ["bash", "fish", "zsh"];
/// Every parser accepts these, in argparse's registration order.
const OPTION_STRINGS: [&str; 3] = ["-h", "--help", "--json"];

#[derive(Clone, Copy, Debug, PartialEq)]
enum Action {
    Help,
    Json,
}

impl Action {
    fn name(self) -> &'static str {
        match self {
            Action::Help => "-h/--help",
            Action::Json => "--json",
        }
    }
}

fn lookup(option: &str) -> Option<Action> {
    match option {
        "-h" | "--help" => Some(Action::Help),
        "--json" => Some(Action::Json),
        _ => None,
    }
}

/// How a parser run ended early.
enum Exit {
    Help(String),
    /// The complete message, usage included.
    Error(String),
}

/// Inside the parser an error is only a message until it is known which parser formats it.
enum Stop {
    Exit(Exit),
    Message(String),
}

/// The first paragraph of the help text.
fn usage(spec: &Spec) -> String {
    format!("{}\n", spec.help.split("\n\n").next().unwrap_or(""))
}

fn usage_error(spec: &Spec, message: &str) -> String {
    format!("{}{}: error: {}\n", usage(spec), spec.prog, message)
}

#[derive(Default)]
struct Namespace {
    json: bool,
    command: Option<&'static str>,
    values: Vec<(&'static str, String)>,
    /// Arguments the subcommand did not know; the main parser reports them.
    unrecognized: Vec<String>,
}

impl Namespace {
    fn value(&self, name: &str) -> Option<&str> {
        self.values
            .iter()
            .find(|(key, _)| *key == name)
            .map(|(_, value)| value.as_str())
    }
}

/// One way to read an argument that starts with `-`.
#[derive(Clone, Debug)]
struct Opt {
    /// `None` for an option no parser knows.
    action: Option<Action>,
    option_string: String,
    /// The explicit argument was attached with `=`.
    equals: bool,
    explicit: Option<String>,
}

/// Parses `args` for `spec` and returns what the parser knows, or how it ended early.
fn parse_known(spec: &'static Spec, args: &[String]) -> Result<Namespace, Exit> {
    parse_args(spec, args).map_err(|stop| match stop {
        Stop::Exit(exit) => exit,
        Stop::Message(message) => Exit::Error(usage_error(spec, &message)),
    })
}

struct State<'a> {
    spec: &'static Spec,
    args: &'a [String],
    /// `O` for an option, `A` for an argument and `-` for the first `--`; everything after it is `A`.
    pattern: Vec<u8>,
    options: Vec<Option<Vec<Opt>>>,
    /// How many positionals are consumed.
    next: usize,
    namespace: Namespace,
    extras: Vec<String>,
}

fn parse_args(spec: &'static Spec, args: &[String]) -> Result<Namespace, Stop> {
    let mut pattern = Vec::with_capacity(args.len());
    let mut options: Vec<Option<Vec<Opt>>> = Vec::with_capacity(args.len());
    let mut separator = false;
    for arg in args {
        if separator {
            pattern.push(b'A');
            options.push(None);
        } else if arg == "--" {
            separator = true;
            pattern.push(b'-');
            options.push(None);
        } else {
            match classify(arg) {
                None => {
                    pattern.push(b'A');
                    options.push(None);
                }
                Some(found) => {
                    pattern.push(b'O');
                    options.push(Some(found));
                }
            }
        }
    }
    let mut state = State {
        spec,
        args,
        pattern,
        options,
        next: 0,
        namespace: Namespace::default(),
        extras: Vec::new(),
    };

    // Consume positionals and options alternately until the last option is passed
    let mut start = 0;
    if let Some(last_option) = (0..args.len())
        .rev()
        .find(|&index| state.options[index].is_some())
    {
        while start <= last_option {
            let mut next_option = start;
            while next_option <= last_option && state.options[next_option].is_none() {
                next_option += 1;
            }
            if start != next_option {
                let end = state.consume_positionals(start)?;
                if end > start {
                    start = end;
                    continue;
                }
                start = end;
            }
            if state.options[start].is_none() {
                state
                    .extras
                    .extend(args[start..next_option].iter().cloned());
                start = next_option;
            }
            start = state.consume_optional(start)?;
        }
    }
    let stop = state.consume_positionals(start)?;
    state.extras.extend(args[stop..].iter().cloned());

    let missing: Vec<&str> = spec.positionals[state.next..]
        .iter()
        .map(|positional| positional.name)
        .collect();
    if !missing.is_empty() {
        return Err(Stop::Message(format!(
            "the following arguments are required: {}",
            missing.join(", ")
        )));
    }
    let mut namespace = state.namespace;
    let mut extras = state.extras;
    extras.append(&mut namespace.unrecognized);
    namespace.unrecognized = extras;
    Ok(namespace)
}

impl State<'_> {
    fn consume_positionals(&mut self, start: usize) -> Result<usize, Stop> {
        let remaining = &self.spec.positionals[self.next..];
        let kinds: Vec<Kind> = remaining.iter().map(|positional| positional.kind).collect();
        let counts = match_partial(&kinds, &self.pattern[start..]);
        let mut index = start;
        for (positional, &count) in remaining.iter().zip(&counts) {
            let mut values: Vec<String> = self.args[index..index + count].to_vec();
            // Strip out the first "--"
            if positional.kind == Kind::Command {
                if self.pattern[index] == b'-' {
                    values.remove(0);
                }
            } else if self.pattern[index..index + count].contains(&b'-') {
                if let Some(at) = values.iter().position(|value| value == "--") {
                    values.remove(at);
                }
            }
            index += count;
            self.take_positional(positional, values)?;
        }
        self.next += counts.len();
        Ok(index)
    }

    fn take_positional(
        &mut self,
        positional: &'static Positional,
        values: Vec<String>,
    ) -> Result<(), Stop> {
        let Some(first) = values.first() else {
            return Ok(());
        };
        match positional.kind {
            Kind::Text => self.namespace.values.push((positional.name, first.clone())),
            Kind::Int => match parse_int(first) {
                Some(number) => self.namespace.values.push((positional.name, number)),
                None => {
                    return Err(Stop::Message(format!(
                        "argument {}: invalid int value: {}",
                        positional.name,
                        py_repr(first)
                    )))
                }
            },
            Kind::Shell => {
                if !SHELLS.contains(&first.as_str()) {
                    return Err(invalid_choice(
                        positional.name,
                        first,
                        SHELLS.iter().copied(),
                    ));
                }
                self.namespace.values.push((positional.name, first.clone()));
            }
            Kind::Command => {
                let Some((name, sub)) = COMMANDS.iter().find(|(name, _)| name == first) else {
                    return Err(invalid_choice(
                        positional.name,
                        first,
                        COMMANDS.iter().map(|(name, _)| *name),
                    ));
                };
                let sub = parse_known(sub, &values[1..]).map_err(Stop::Exit)?;
                self.namespace.command = Some(name);
                self.namespace.json |= sub.json;
                self.namespace.values.extend(sub.values);
                self.namespace.unrecognized.extend(sub.unrecognized);
            }
        }
        Ok(())
    }

    fn consume_optional(&mut self, start: usize) -> Result<usize, Stop> {
        let mut tuples = self.options[start].clone().unwrap_or_default();
        if tuples.len() > 1 {
            let matches: Vec<&str> = tuples
                .iter()
                .map(|tuple| tuple.option_string.as_str())
                .collect();
            return Err(Stop::Message(format!(
                "ambiguous option: {} could match {}",
                self.args[start],
                matches.join(", ")
            )));
        }
        let Some(Opt {
            mut action,
            mut option_string,
            mut equals,
            mut explicit,
        }) = tuples.pop()
        else {
            return Ok(start + 1);
        };
        // Several single-dash flags can share one argument (-hx): collect them all first
        let mut pending = Vec::new();
        let stop;
        loop {
            let Some(current) = action else {
                self.extras.push(self.args[start].clone());
                return Ok(start + 1);
            };
            let Some(argument) = explicit.clone() else {
                pending.push(current);
                stop = start + 1;
                break;
            };
            // None of the options takes an argument
            let single_dash = option_string.chars().nth(1) != Some('-');
            if !(single_dash && !argument.is_empty()) {
                return Err(ignored(current, &argument));
            }
            if equals || argument.starts_with('-') {
                return Err(ignored(current, &argument));
            }
            pending.push(current);
            let letter = argument.chars().next().unwrap_or('-');
            option_string = format!("-{letter}");
            match lookup(&option_string) {
                Some(next) => {
                    action = Some(next);
                    let rest = &argument[letter.len_utf8()..];
                    if rest.is_empty() {
                        equals = false;
                        explicit = None;
                    } else if let Some(after) = rest.strip_prefix('=') {
                        equals = true;
                        explicit = Some(after.to_string());
                    } else {
                        equals = false;
                        explicit = Some(rest.to_string());
                    }
                }
                None => {
                    self.extras.push(format!("-{argument}"));
                    stop = start + 1;
                    break;
                }
            }
        }
        for action in pending {
            match action {
                Action::Help => return Err(Stop::Exit(Exit::Help(self.spec.help.to_string()))),
                Action::Json => self.namespace.json = true,
            }
        }
        Ok(stop)
    }
}

fn ignored(action: Action, argument: &str) -> Stop {
    Stop::Message(format!(
        "argument {}: ignored explicit argument {}",
        action.name(),
        py_repr(argument)
    ))
}

fn invalid_choice<'a>(name: &str, value: &str, choices: impl Iterator<Item = &'a str>) -> Stop {
    let choices: Vec<String> = choices.map(py_repr).collect();
    Stop::Message(format!(
        "argument {name}: invalid choice: {} (choose from {})",
        py_repr(value),
        choices.join(", ")
    ))
}

/// argparse's `_parse_optional`: how an argument is read when it starts with `-`.
/// `None` means that it is a positional.
fn classify(arg: &str) -> Option<Vec<Opt>> {
    if !arg.starts_with('-') {
        return None;
    }
    if let Some(action) = lookup(arg) {
        return Some(vec![Opt {
            action: Some(action),
            option_string: arg.to_string(),
            equals: false,
            explicit: None,
        }]);
    }
    if arg.chars().count() == 1 {
        return None;
    }
    if let Some((option, explicit)) = arg.split_once('=') {
        if let Some(action) = lookup(option) {
            return Some(vec![Opt {
                action: Some(action),
                option_string: option.to_string(),
                equals: true,
                explicit: Some(explicit.to_string()),
            }]);
        }
    }
    let tuples = option_tuples(arg);
    if !tuples.is_empty() {
        return Some(tuples);
    }
    if looks_like_negative_number(arg) {
        return None;
    }
    if arg.contains(' ') {
        return None;
    }
    Some(vec![Opt {
        action: None,
        option_string: arg.to_string(),
        equals: false,
        explicit: None,
    }])
}

/// argparse's `_get_option_tuples`: abbreviations of long options and glued short options.
fn option_tuples(arg: &str) -> Vec<Opt> {
    let (prefix, equals, explicit) = match arg.split_once('=') {
        Some((prefix, explicit)) => (prefix, true, Some(explicit.to_string())),
        None => (arg, false, None),
    };
    let mut result = Vec::new();
    if arg.chars().nth(1) == Some('-') {
        for option in OPTION_STRINGS {
            if option.starts_with(prefix) {
                result.push(Opt {
                    action: lookup(option),
                    option_string: option.to_string(),
                    equals,
                    explicit: explicit.clone(),
                });
            }
        }
    } else {
        let mut letters = arg.chars();
        let short_prefix: String = letters.by_ref().take(2).collect();
        let short_explicit: String = letters.collect();
        for option in OPTION_STRINGS {
            if option == short_prefix {
                result.push(Opt {
                    action: lookup(option),
                    option_string: option.to_string(),
                    equals: false,
                    explicit: Some(short_explicit.clone()),
                });
            } else if option.starts_with(prefix) {
                result.push(Opt {
                    action: lookup(option),
                    option_string: option.to_string(),
                    equals,
                    explicit: explicit.clone(),
                });
            }
        }
    }
    result
}

/// argparse treats `-` followed by an optional dot and a digit as a negative number: `-\.?\d`.
fn looks_like_negative_number(arg: &str) -> bool {
    let mut letters = arg.chars();
    if letters.next() != Some('-') {
        return false;
    }
    match letters.next() {
        Some('.') => letters.next().and_then(decimal_value).is_some(),
        Some(letter) => decimal_value(letter).is_some(),
        None => false,
    }
}

/// argparse's `_match_arguments_partial`: how many arguments each of the leading positionals
/// takes, for the largest leading set of positionals that fits the pattern.
fn match_partial(kinds: &[Kind], pattern: &[u8]) -> Vec<usize> {
    for count in (1..=kinds.len()).rev() {
        if let Some(sizes) = match_groups(&kinds[..count], pattern, 0) {
            return sizes;
        }
    }
    Vec::new()
}

/// Matches the regular expression that argparse builds: `(-*A-*)` for a single argument and
/// `(-*A[-AO]*)` for the subcommand with the rest of the line.
fn match_groups(kinds: &[Kind], pattern: &[u8], from: usize) -> Option<Vec<usize>> {
    let Some((kind, rest)) = kinds.split_first() else {
        return Some(Vec::new());
    };
    let mut at = from;
    while pattern.get(at) == Some(&b'-') {
        at += 1;
    }
    if pattern.get(at) != Some(&b'A') {
        return None;
    }
    at += 1;
    let longest = if *kind == Kind::Command {
        pattern.len()
    } else {
        let mut end = at;
        while pattern.get(end) == Some(&b'-') {
            end += 1;
        }
        end
    };
    for end in (at..=longest).rev() {
        if let Some(mut sizes) = match_groups(rest, pattern, end) {
            sizes.insert(0, end - from);
            return Some(sizes);
        }
    }
    None
}

/// Python's `int(text)` for a decimal string, as the text Python would print for the result.
fn parse_int(text: &str) -> Option<String> {
    let text = text.trim_matches(is_space);
    let (negative, digits) = match text.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, text.strip_prefix('+').unwrap_or(text)),
    };
    let mut ascii = String::with_capacity(digits.len());
    let mut after_digit = false;
    for letter in digits.chars() {
        match decimal_value(letter) {
            Some(value) => {
                ascii.push(char::from_digit(value, 10)?);
                after_digit = true;
            }
            // A single underscore is allowed between digits
            None if letter == '_' && after_digit => after_digit = false,
            None => return None,
        }
    }
    if ascii.is_empty() || !after_digit {
        return None;
    }
    let significant = ascii.trim_start_matches('0');
    // Python refuses to convert integers of more than 4300 digits
    if ascii.len() > 4300 && significant.len() > 4300 {
        return None;
    }
    if significant.is_empty() {
        return Some("0".to_string());
    }
    Some(if negative {
        format!("-{significant}")
    } else {
        significant.to_string()
    })
}

/// Python's `repr(str)`, which argparse puts into its messages.
pub fn py_repr(text: &str) -> String {
    let quote = if text.contains('\'') && !text.contains('"') {
        '"'
    } else {
        '\''
    };
    let mut result = String::with_capacity(text.len() + 2);
    result.push(quote);
    for letter in text.chars() {
        match letter {
            '\\' => result.push_str("\\\\"),
            '\n' => result.push_str("\\n"),
            '\r' => result.push_str("\\r"),
            '\t' => result.push_str("\\t"),
            letter if letter == quote => {
                result.push('\\');
                result.push(letter);
            }
            letter if is_printable(letter) => result.push(letter),
            letter if (letter as u32) < 0x100 => {
                result.push_str(&format!("\\x{:02x}", letter as u32))
            }
            letter if (letter as u32) < 0x10000 => {
                result.push_str(&format!("\\u{:04x}", letter as u32))
            }
            letter => result.push_str(&format!("\\U{:08x}", letter as u32)),
        }
    }
    result.push(quote);
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(line: &str) -> Vec<String> {
        // Words separated by single spaces; "''" stands for the empty argument
        if line.is_empty() {
            return Vec::new();
        }
        line.split(' ')
            .map(|word| {
                if word == "''" {
                    String::new()
                } else {
                    word.to_string()
                }
            })
            .collect()
    }

    fn run(line: &str) -> Parsed {
        match parse(&args(line)) {
            Outcome::Run(parsed) => parsed,
            other => panic!("{line:?}: {other:?}"),
        }
    }

    fn error(line: &str) -> String {
        match parse(&args(line)) {
            Outcome::Error(text) => text,
            other => panic!("{line:?}: {other:?}"),
        }
    }

    fn help_text(line: &str) -> String {
        match parse(&args(line)) {
            Outcome::Help(text) => text,
            other => panic!("{line:?}: {other:?}"),
        }
    }

    fn message(line: &str) -> String {
        let text = error(line);
        text.lines().last().unwrap().to_string()
    }

    const MAIN_USAGE: &str = "usage: tpp-session [-h] [--json]\n                   {list,close,focus,kill,move,rename,completions,reload-settings,switcher} ...\n";

    #[test]
    fn commands_parse() {
        assert_eq!(
            run("list"),
            Parsed {
                json: false,
                command: Command::List
            }
        );
        assert_eq!(
            run("close abc").command,
            Command::Close { id: "abc".into() }
        );
        assert_eq!(
            run("focus abc").command,
            Command::Focus { id: "abc".into() }
        );
        assert_eq!(run("kill abc").command, Command::Kill { id: "abc".into() });
        assert_eq!(
            run("move abc 3").command,
            Command::Move {
                id: "abc".into(),
                position: "3".into()
            }
        );
        assert_eq!(
            run("rename abc new").command,
            Command::Rename {
                id: "abc".into(),
                name: "new".into()
            }
        );
        assert_eq!(
            run("completions zsh").command,
            Command::Completions { shell: Shell::Zsh }
        );
        assert_eq!(run("reload-settings").command, Command::ReloadSettings);
        assert_eq!(
            run("rename abc ''").command,
            Command::Rename {
                id: "abc".into(),
                name: String::new()
            }
        );
    }

    #[test]
    fn json_goes_before_or_after_the_command() {
        assert!(run("--json list").json);
        assert!(run("list --json").json);
        assert!(run("--json list --json").json);
        assert!(run("close --json abc").json);
        assert!(run("close abc --json").json);
        assert!(!run("close abc").json);
    }

    #[test]
    fn options_can_be_abbreviated() {
        assert!(run("--j list").json);
        assert!(run("list --js").json);
        assert!(run("list --jso").json);
        assert_eq!(help_text("--he"), help::MAIN);
        assert_eq!(help_text("list --he"), help::LIST);
    }

    #[test]
    fn help_goes_first() {
        assert_eq!(help_text("-h"), help::MAIN);
        assert_eq!(help_text("--help"), help::MAIN);
        assert_eq!(help_text("close -h"), help::CLOSE);
        assert_eq!(help_text("move abc 1 --help"), help::MOVE);
        // Errors on the way come first
        assert!(error("move abc x -h").contains("invalid int value: 'x'"));
        assert_eq!(help_text("list -h --foo"), help::LIST);
        // Glued to other letters, -h is still help
        assert_eq!(help_text("list -hx"), help::LIST);
        assert_eq!(help_text("-hj"), help::MAIN);
    }

    #[test]
    fn usage_errors_name_the_parser_that_found_them() {
        assert_eq!(
            error(""),
            format!(
                "{MAIN_USAGE}tpp-session: error: the following arguments are required: command\n"
            )
        );
        assert_eq!(
            error("close"),
            "usage: tpp-session close [-h] [--json] id\ntpp-session close: error: the following arguments are required: id\n"
        );
        assert_eq!(
            message("rename a"),
            "tpp-session rename: error: the following arguments are required: name"
        );
        assert_eq!(
            message("rename"),
            "tpp-session rename: error: the following arguments are required: id, name"
        );
        assert_eq!(
            message("move abc x"),
            "tpp-session move: error: argument position: invalid int value: 'x'"
        );
        assert_eq!(
            message("completions zzz"),
            "tpp-session completions: error: argument shell: invalid choice: 'zzz' (choose from 'bash', 'fish', 'zsh')"
        );
        assert_eq!(
            message("bogus"),
            "tpp-session: error: argument command: invalid choice: 'bogus' (choose from 'list', 'close', 'focus', 'kill', 'move', 'rename', 'completions', 'reload-settings', 'switcher')"
        );
    }

    #[test]
    fn unrecognized_arguments_are_reported_by_the_main_parser() {
        assert_eq!(
            error("close a b"),
            format!("{MAIN_USAGE}tpp-session: error: unrecognized arguments: b\n")
        );
        assert_eq!(
            message("list --foo"),
            "tpp-session: error: unrecognized arguments: --foo"
        );
        assert_eq!(
            message("--foo list"),
            "tpp-session: error: unrecognized arguments: --foo"
        );
        assert_eq!(
            message("close abc --foo"),
            "tpp-session: error: unrecognized arguments: --foo"
        );
        assert_eq!(
            message("close -x abc"),
            "tpp-session: error: unrecognized arguments: -x"
        );
        assert_eq!(
            message("list -j"),
            "tpp-session: error: unrecognized arguments: -j"
        );
        assert_eq!(
            message("list -"),
            "tpp-session: error: unrecognized arguments: -"
        );
        assert_eq!(
            message("--foo list a"),
            "tpp-session: error: unrecognized arguments: --foo a"
        );
        // The main parser's own findings come first, then those of the command
        assert_eq!(
            message("--foo close a b -x"),
            "tpp-session: error: unrecognized arguments: --foo b -x"
        );
        assert_eq!(
            message("--foo"),
            "tpp-session: error: the following arguments are required: command"
        );
    }

    #[test]
    fn explicit_arguments_to_flags_are_refused() {
        assert_eq!(
            message("list --json=x"),
            "tpp-session list: error: argument --json: ignored explicit argument 'x'"
        );
        assert_eq!(
            message("list --help=x"),
            "tpp-session list: error: argument -h/--help: ignored explicit argument 'x'"
        );
        assert_eq!(
            message("--json=1 list"),
            "tpp-session: error: argument --json: ignored explicit argument '1'"
        );
        assert_eq!(
            message("close a --json="),
            "tpp-session close: error: argument --json: ignored explicit argument ''"
        );
        assert_eq!(
            message("list -h=x"),
            "tpp-session list: error: argument -h/--help: ignored explicit argument 'x'"
        );
        assert_eq!(
            message("list -h-x"),
            "tpp-session list: error: argument -h/--help: ignored explicit argument '-x'"
        );
        assert_eq!(
            message("list -hh=x"),
            "tpp-session list: error: argument -h/--help: ignored explicit argument 'x'"
        );
        assert_eq!(
            message("--=x list"),
            "tpp-session: error: ambiguous option: --=x could match --help, --json"
        );
    }

    #[test]
    fn double_dash_ends_the_options() {
        assert_eq!(run("-- list").command, Command::List);
        assert_eq!(
            run("--json -- list"),
            Parsed {
                json: true,
                command: Command::List
            }
        );
        assert_eq!(
            run("close -- --json").command,
            Command::Close {
                id: "--json".into()
            }
        );
        assert_eq!(
            run("close abc --").command,
            Command::Close { id: "abc".into() }
        );
        assert_eq!(
            run("close -- --").command,
            Command::Close { id: "--".into() }
        );
        assert_eq!(
            run("move abc -- -5").command,
            Command::Move {
                id: "abc".into(),
                position: "-5".into()
            }
        );
        assert_eq!(
            run("move abc --json -- 5").command,
            Command::Move {
                id: "abc".into(),
                position: "5".into()
            }
        );
        assert_eq!(
            message("close -- a b"),
            "tpp-session: error: unrecognized arguments: b"
        );
        assert_eq!(
            message("close abc -- x"),
            "tpp-session: error: unrecognized arguments: x"
        );
        assert_eq!(
            message("list --"),
            "tpp-session: error: unrecognized arguments: --"
        );
        assert!(message("-- -- list")
            .starts_with("tpp-session: error: argument command: invalid choice: '--'"));
        assert_eq!(
            message("--"),
            "tpp-session: error: the following arguments are required: command"
        );
    }

    #[test]
    fn dash_arguments_that_are_values() {
        assert_eq!(run("close -").command, Command::Close { id: "-".into() });
        assert_eq!(run("close -5").command, Command::Close { id: "-5".into() });
        assert_eq!(
            run("close -.5x").command,
            Command::Close { id: "-.5x".into() }
        );
        let spaced = ["close".to_string(), "-x y".to_string()];
        assert_eq!(
            parse(&spaced),
            Outcome::Run(Parsed {
                json: false,
                command: Command::Close { id: "-x y".into() }
            })
        );
        assert_eq!(
            run("rename a -1").command,
            Command::Rename {
                id: "a".into(),
                name: "-1".into()
            }
        );
        assert_eq!(
            message("move a --5"),
            "tpp-session move: error: the following arguments are required: position"
        );
        assert_eq!(
            message("move a -5x"),
            "tpp-session move: error: argument position: invalid int value: '-5x'"
        );
        assert_eq!(message("-"), "tpp-session: error: argument command: invalid choice: '-' (choose from 'list', 'close', 'focus', 'kill', 'move', 'rename', 'completions', 'reload-settings', 'switcher')");
    }

    #[test]
    fn options_between_positionals() {
        assert_eq!(
            run("move --json abc 2").command,
            Command::Move {
                id: "abc".into(),
                position: "2".into()
            }
        );
        assert_eq!(
            run("move abc --json 2").command,
            Command::Move {
                id: "abc".into(),
                position: "2".into()
            }
        );
        assert!(run("move abc --json 2").json);
    }

    #[test]
    fn integers_follow_python() {
        for (text, expected) in [
            ("5", "5"),
            ("+5", "5"),
            ("-5", "-5"),
            ("007", "7"),
            ("-0", "0"),
            ("0", "0"),
            ("1_0", "10"),
            (" 5 ", "5"),
            ("\u{661}\u{662}", "12"),
            ("99999999999999999999", "99999999999999999999"),
        ] {
            assert_eq!(parse_int(text).as_deref(), Some(expected), "{text:?}");
        }
        for text in [
            "", "x", "1.5", "0x10", "1__0", "_1", "1_", "+", "-", "- 5", "5x", "1e3", "\u{b2}",
        ] {
            assert_eq!(parse_int(text), None, "{text:?}");
        }
        assert_eq!(parse_int(&"1".repeat(4300)), Some("1".repeat(4300)));
        assert_eq!(parse_int(&"1".repeat(4301)), None);
    }

    #[test]
    fn repr_follows_python() {
        assert_eq!(py_repr("x"), "'x'");
        assert_eq!(py_repr("it's"), "\"it's\"");
        assert_eq!(py_repr("a\"b'c"), "'a\"b\\'c'");
        assert_eq!(
            py_repr("a\\b\n\t\u{1}\u{80}\u{ff}\u{200b}\u{10ffff}é"),
            "'a\\\\b\\n\\t\\x01\\x80ÿ\\u200b\\U0010ffffé'"
        );
    }

    #[test]
    fn every_help_text_starts_with_its_usage() {
        for (name, spec) in &COMMANDS {
            assert!(
                spec.help
                    .starts_with(&format!("usage: tpp-session {name} [-h] [--json]")),
                "{name}"
            );
        }
        assert!(MAIN.help.starts_with("usage: tpp-session [-h] [--json]\n"));
    }
}
