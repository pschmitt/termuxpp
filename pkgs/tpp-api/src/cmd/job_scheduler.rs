//! `job-scheduler`: `tpp job-scheduler`. Schedules a script through the app's
//! job scheduler.
//!
//! The script parses its options with util-linux `getopt` (`-o hs:p`, GNU long
//! options), so this module has a small private copy of that behavior:
//! options and operands may be mixed (operands are collected, unless
//! `POSIXLY_CORRECT` is set), long options may be abbreviated, `--name=value`
//! and `--name value` both work, and `getopt` itself reports the errors
//! (`tpp job-scheduler: invalid option -- 'x'` and so on, on standard error,
//! after which the script exits with status 1 without doing anything else).
//!
//! Deviations:
//! - The error texts and the treatment of abbreviations (an abbreviation that
//!   fits several long options is ambiguous) are those of util-linux 2.42
//!   `getopt` with a current GNU C library; other versions word a few of the
//!   messages differently.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/job-scheduler.txt");
const NAME: &str = "tpp job-scheduler";

const SHORT: &[(char, bool)] = &[('h', false), ('s', true), ('p', false)];

/// The long options, in the order of the `getopt --long` list (the order
/// decides which of several abbreviation matches is taken).
const LONG: &[(&str, bool)] = &[
    ("script", true),
    ("job-id", true),
    ("pending", false),
    ("cancel", false),
    ("cancel-all", false),
    ("period-ms", true),
    ("network", true),
    ("battery-not-low", true),
    ("storage-not-low", true),
    ("charging", true),
    ("persisted", true),
    ("help", false),
    ("trigger-content-flag", true),
    ("trigger-content-uri", true),
];

/// One parsed option.
#[derive(Debug, PartialEq, Eq)]
enum Item {
    Short(char, Option<String>),
    Long(&'static str, Option<String>),
}

/// What `getopt` gives: the options, the operands, and the error messages it
/// printed (without the program name prefix).
struct Parsed {
    items: Vec<Item>,
    operands: Vec<String>,
    errors: Vec<String>,
}

/// `getopt -o hs:p --long ... -- "$@"` (GNU `getopt_long`, the arguments
/// permuted unless `posixly_correct`).
fn getopt(args: &[String], posixly_correct: bool) -> Parsed {
    let mut p = Parsed {
        items: Vec::new(),
        operands: Vec::new(),
        errors: Vec::new(),
    };
    let mut i = 0;
    while i < args.len() {
        let arg = &args[i];
        i += 1;
        if arg == "--" {
            p.operands.extend(args[i..].iter().cloned());
            return p;
        }
        if !arg.starts_with('-') || arg == "-" {
            if posixly_correct {
                p.operands.push(arg.clone());
                p.operands.extend(args[i..].iter().cloned());
                return p;
            }
            p.operands.push(arg.clone());
            continue;
        }
        if let Some(long) = arg.strip_prefix("--") {
            long_option(&mut p, args, &mut i, long);
        } else {
            short_options(&mut p, args, &mut i, &arg[1..]);
        }
    }
    p
}

fn short_options(p: &mut Parsed, args: &[String], i: &mut usize, cluster: &str) {
    for (at, c) in cluster.char_indices() {
        let spec = SHORT.iter().find(|(o, _)| *o == c);
        let Some((_, takes)) = spec else {
            p.errors.push(format!("invalid option -- '{c}'"));
            continue;
        };
        if !takes {
            p.items.push(Item::Short(c, None));
            continue;
        }
        let rest = &cluster[at + c.len_utf8()..];
        if !rest.is_empty() {
            p.items.push(Item::Short(c, Some(rest.to_string())));
        } else if let Some(value) = args.get(*i) {
            *i += 1;
            p.items.push(Item::Short(c, Some(value.clone())));
        } else {
            p.errors
                .push(format!("option requires an argument -- '{c}'"));
        }
        return;
    }
}

fn long_option(p: &mut Parsed, args: &[String], i: &mut usize, long: &str) {
    let name_len = long.find('=').unwrap_or(long.len());
    let typed = &long[..name_len];
    let mut found: Option<usize> = None;
    let mut matches: Vec<usize> = Vec::new();
    for (n, (name, _)) in LONG.iter().enumerate() {
        if !name.starts_with(typed) {
            continue;
        }
        if name.len() == typed.len() {
            found = Some(n);
            matches.clear();
            break;
        }
        matches.push(n);
    }
    // an abbreviation that fits several options is ambiguous
    let ambiguous: &[usize] = if matches.len() > 1 { &matches } else { &[] };
    if found.is_none() && matches.len() == 1 {
        found = Some(matches[0]);
    }
    if !ambiguous.is_empty() {
        let mut msg = format!("option '--{long}' is ambiguous; possibilities:");
        for n in ambiguous {
            msg.push_str(&format!(" '--{}'", LONG[*n].0));
        }
        p.errors.push(msg);
        return;
    }
    let Some(n) = found else {
        p.errors.push(format!("unrecognized option '--{long}'"));
        return;
    };
    let (name, takes) = LONG[n];
    if name_len < long.len() {
        if takes {
            p.items
                .push(Item::Long(name, Some(long[name_len + 1..].to_string())));
        } else {
            p.errors
                .push(format!("option '--{name}' doesn't allow an argument"));
        }
    } else if takes {
        if let Some(value) = args.get(*i) {
            *i += 1;
            p.items.push(Item::Long(name, Some(value.clone())));
        } else {
            p.errors
                .push(format!("option '--{name}' requires an argument"));
        }
    } else {
        p.items.push(Item::Long(name, None));
    }
}

#[derive(Default)]
struct Opts {
    script: String,
    job_id: String,
    pending: String,
    cancel: String,
    cancel_all: String,
    period_ms: String,
    network: String,
    battery_not_low: String,
    storage_not_low: String,
    charging: String,
    persisted: String,
    trigger_content_uri: String,
    trigger_content_flag: String,
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let posixly_correct = io.env.get("POSIXLY_CORRECT").is_some();
    let parsed = getopt(args, posixly_correct);
    for e in &parsed.errors {
        io.errln(&format!("{NAME}: {e}"));
    }
    if !parsed.errors.is_empty() {
        // TEMP=`getopt ...` failed under `set -e`
        return 1;
    }

    let mut o = Opts::default();
    for item in parsed.items {
        let (key, value) = match item {
            Item::Short('s', v) => ("script", v),
            Item::Short('p', _) => ("pending", None),
            Item::Short(_, _) => {
                // -h
                io.out(USAGE);
                return 0;
            }
            Item::Long(name, v) => (name, v),
        };
        let value = value.unwrap_or_default();
        match key {
            "script" => o.script = value,
            "job-id" => o.job_id = value,
            "pending" => o.pending = "1".to_string(),
            "cancel" => o.cancel = "1".to_string(),
            "cancel-all" => o.cancel_all = "1".to_string(),
            "period-ms" => o.period_ms = value,
            "network" => o.network = value,
            "battery-not-low" => o.battery_not_low = value,
            "storage-not-low" => o.storage_not_low = value,
            "charging" => o.charging = value,
            "persisted" => o.persisted = value,
            "trigger-content-flag" => o.trigger_content_flag = value,
            "trigger-content-uri" => o.trigger_content_uri = value,
            _ => {
                // help
                io.out(USAGE);
                return 0;
            }
        }
    }

    if !parsed.operands.is_empty() {
        io.outln(&format!("{NAME}: too many arguments"));
        return 1;
    }

    let mut call = Call::new("JobScheduler");
    if !o.script.is_empty() {
        call = call.es("script", &o.script);
    }
    if !o.job_id.is_empty() {
        call = call.ei("job_id", &o.job_id);
    }
    if !o.pending.is_empty() {
        call = call.ez("pending", &o.pending);
    }
    if !o.cancel.is_empty() {
        call = call.ez("cancel", &o.cancel);
    }
    if !o.cancel_all.is_empty() {
        call = call.ez("cancel_all", &o.cancel_all);
    }
    if !o.period_ms.is_empty() {
        call = call.ei("period_ms", &o.period_ms);
    }
    if !o.network.is_empty() {
        call = call.es("network", &o.network);
    }
    if !o.battery_not_low.is_empty() {
        call = call.ez("battery_not_low", &o.battery_not_low);
    }
    if !o.storage_not_low.is_empty() {
        call = call.ez("storage_not_low", &o.storage_not_low);
    }
    if !o.charging.is_empty() {
        call = call.ez("charging", &o.charging);
    }
    if !o.persisted.is_empty() {
        call = call.ez("persisted", &o.persisted);
    }
    if !o.trigger_content_uri.is_empty() {
        call = call.es("trigger_content_uri", &o.trigger_content_uri);
    }
    if !o.trigger_content_flag.is_empty() {
        call = call.ez("trigger_content_flag", &o.trigger_content_flag);
    }
    io.run_call(call)
}
