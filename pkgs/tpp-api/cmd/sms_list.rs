//! `sms-list`: `tpp sms list`. Lists SMS conversations and messages.
//!
//! Reproduces the script's quirks: the long options are read through the
//! `getopts` `-:` trick (so only the `--name=value` form carries a value, and
//! `--name value` is "No argument set" for `--name`), unknown options print
//! the help text and exit with 64, the conversation-only options are passed
//! only together with `--conversation-list`, and
//! `conversation-return-no-order-reverse` is keyed on the conversation sort
//! order, not on its own flag.
//!
//! The log level starts at `TERMUX_API__LOG_LEVEL` (0..5, else 1); at 2 or
//! more the script prints its progress to standard output.
//!
//! Deviations:
//! - Operands make the script call the undefined function `logger__log_error`;
//!   bash prints `SCRIPT: line 377: logger__log_error: command not found`, where
//!   SCRIPT is `$0`. Here `SCRIPT` is `tpp sms list`. The exit status (64) is
//!   the same.
//! - A `TERMUX_API__LOG_LEVEL` with leading zeros and an 8 or 9 (an invalid
//!   octal number for bash arithmetic) is treated as 1.
//! - A transport failure of the call is reported as exit status 1 (the script
//!   passes on the exit status of the C client, which this crate has no
//!   equivalent of).
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/sms-list.txt");
const NAME: &str = "tpp sms list";
const CONTROLLER: &str = "SmsInbox";
const MAX_LOG_LEVEL: u32 = 5;
const SUPPORTED_TYPES: &str = "all|inbox|sent|draft|outbox|failed|queued";

/// The global variables of the script.
struct State {
    log_level: u32,
    conversation_list: String,
    conversation_limit: String,
    conversation_offset: String,
    conversation_selection: String,
    conversation_sort_order: String,
    conversation_multiple: String,
    conversation_nested: String,
    conversation_no_order_reverse: String,
    limit: String,
    offset: String,
    selection: String,
    address: String,
    sort_order: String,
    type_name: String,
    no_order_reverse: String,
    show_dates: String,
    show_phone_numbers: String,
}

impl State {
    fn new(log_level: u32) -> Self {
        State {
            log_level,
            conversation_list: String::new(),
            conversation_limit: String::new(),
            conversation_offset: String::new(),
            conversation_selection: String::new(),
            conversation_sort_order: String::new(),
            conversation_multiple: String::new(),
            conversation_nested: String::new(),
            conversation_no_order_reverse: String::new(),
            limit: "10".to_string(),
            offset: "0".to_string(),
            selection: String::new(),
            address: String::new(),
            sort_order: String::new(),
            type_name: "all".to_string(),
            no_order_reverse: String::new(),
            show_dates: String::new(),
            show_phone_numbers: String::new(),
        }
    }

    /// The variable a `--name=value` option sets.
    fn value_option(&mut self, name: &str) -> Option<&mut String> {
        Some(match name {
            "conversation-limit" => &mut self.conversation_limit,
            "conversation-offset" => &mut self.conversation_offset,
            "conversation-selection" => &mut self.conversation_selection,
            "conversation-sort-order" => &mut self.conversation_sort_order,
            "message-address" => &mut self.address,
            "message-limit" => &mut self.limit,
            "message-offset" => &mut self.offset,
            "message-selection" => &mut self.selection,
            "message-sort-order" => &mut self.sort_order,
            "message-type" => &mut self.type_name,
            _ => return None,
        })
    }

    fn flag_option(&mut self, name: &str) -> Option<&mut String> {
        Some(match name {
            "conversation-list" => &mut self.conversation_list,
            "conversation-return-multiple-messages" => &mut self.conversation_multiple,
            "conversation-return-nested-view" => &mut self.conversation_nested,
            "conversation-return-no-order-reverse" => &mut self.conversation_no_order_reverse,
            "message-return-no-order-reverse" => &mut self.no_order_reverse,
            _ => return None,
        })
    }
}

/// `TERMUX_API__LOG_LEVEL` the way the script's `init` validates it.
fn initial_log_level(io: &Io) -> u32 {
    let v = io.env.get("TERMUX_API__LOG_LEVEL").unwrap_or("");
    if v.is_empty() || !v.bytes().all(|b| b.is_ascii_digit()) {
        return 1;
    }
    let n = if v.len() > 1 && v.starts_with('0') {
        u64::from_str_radix(v, 8).ok()
    } else {
        v.parse::<u64>().ok()
    };
    match n {
        Some(n) if n <= u64::from(MAX_LOG_LEVEL) => n as u32,
        _ => 1,
    }
}

/// `termux_api__log LEVEL MESSAGE`
fn log(io: &mut Io, st: &State, level: u32, message: &str) {
    if st.log_level >= level {
        io.outln(&format!("{NAME}: {message}"));
    }
}

/// `termux_api__log_error`
fn log_error(io: &mut Io, message: &str) {
    io.errln(&format!("{NAME}: {message}"));
}

fn show_help(io: &mut Io) {
    io.out(USAGE);
}

/// `termux_api__sms_list__exit_on_args_error`
fn exit_on_args_error(io: &mut Io) -> i32 {
    show_help(io);
    64
}

/// A `--name...` option (what `getopts` hands over as `OPTARG` of `-`).
/// Err(status) ends the command.
fn long_option(io: &mut Io, st: &mut State, optarg: &str) -> Result<LongResult, i32> {
    if optarg == "help" {
        show_help(io);
        return Err(0);
    }
    if optarg == "quiet" {
        st.log_level = 0;
        return Ok(LongResult::Next);
    }
    if let Some(var) = st.flag_option(optarg) {
        *var = "true".to_string();
        return Ok(LongResult::Next);
    }
    // NAME=?* sets, NAME and NAME= complain
    let (name, has_value) = match optarg.split_once('=') {
        Some((n, v)) => (n, Some(v)),
        None => (optarg, None),
    };
    if st.value_option(name).is_some() {
        match has_value {
            Some(v) if !v.is_empty() => {
                if let Some(var) = st.value_option(name) {
                    *var = v.to_string();
                }
                return Ok(LongResult::Next);
            }
            _ => {
                log_error(io, &format!("No argument set for arg option '--{name}'."));
                return Err(64);
            }
        }
    }
    if optarg.is_empty() {
        // End of options `--`.
        return Ok(LongResult::Break);
    }
    log_error(io, &format!("Unknown option: '--{optarg}'."));
    Err(exit_on_args_error(io))
}

enum LongResult {
    Next,
    Break,
}

/// `termux_api__sms_list__process_script_arguments`: Err(status) when the
/// command is over (help, an error).
fn process_args(args: &[String], io: &mut Io, st: &mut State) -> Result<(), i32> {
    let mut g = Getopts::new(args, ":hqvcdf:l:no:t:-:");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Arg('-', optarg) => match long_option(io, st, &optarg)? {
                LongResult::Next => {}
                LongResult::Break => break,
            },
            Opt::Flag('h') => {
                show_help(io);
                return Err(0);
            }
            Opt::Flag('q') => st.log_level = 0,
            Opt::Flag('v') => {
                if st.log_level < MAX_LOG_LEVEL {
                    st.log_level += 1;
                } else {
                    log_error(
                        io,
                        &format!("Invalid option, max log level is {MAX_LOG_LEVEL}."),
                    );
                    return Err(64);
                }
            }
            Opt::Flag('c') => st.conversation_list = "true".to_string(),
            Opt::Flag('d') => st.show_dates = "true".to_string(),
            Opt::Flag('n') => st.show_phone_numbers = "true".to_string(),
            Opt::Arg('f', v) => st.address = v,
            Opt::Arg('l', v) => st.limit = v,
            Opt::Arg('o', v) => st.offset = v,
            Opt::Arg('t', v) => st.type_name = v,
            Opt::MissingArg(c) => {
                log_error(io, &format!("No argument passed for arg option '-{c}'."));
                return Err(exit_on_args_error(io));
            }
            Opt::Illegal(c) => {
                log_error(io, &format!("Unknown option: '-{c}'."));
                return Err(exit_on_args_error(io));
            }
            Opt::Flag(_) | Opt::Arg(_, _) => {}
        }
    }
    let rest = g.rest();
    if !rest.is_empty() {
        // The script calls the undefined function `logger__log_error`.
        io.errln(&format!(
            "{NAME}: line 377: logger__log_error: command not found"
        ));
        return Err(64);
    }
    Ok(())
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut st = State::new(initial_log_level(io));
    if let Err(status) = process_args(args, io, &mut st) {
        return status;
    }

    log(io, &st, 2, "Running command");

    let type_column = match st.type_name.as_str() {
        "all" => 0,
        "inbox" => 1,
        "sent" => 2,
        "draft" => 3,
        "outbox" => 4,
        "failed" => 5,
        "queued" => 6,
        other => {
            log_error(
                io,
                &format!(
                    "Unsupported message type '{other}'. It must one of following: {SUPPORTED_TYPES}"
                ),
            );
            return 1;
        }
    };

    let mut call = Call::new(CONTROLLER);
    if !st.conversation_list.is_empty() {
        call = call.ez("conversation-list", &st.conversation_list);
        if st.conversation_list == "true" {
            if !st.conversation_limit.is_empty() {
                call = call.ei("conversation-limit", &st.conversation_limit);
            }
            if !st.conversation_offset.is_empty() {
                call = call.ei("conversation-offset", &st.conversation_offset);
            }
            if !st.conversation_selection.is_empty() {
                call = call.es("conversation-selection", &st.conversation_selection);
            }
            if !st.conversation_sort_order.is_empty() {
                call = call.es("conversation-sort-order", &st.conversation_sort_order);
            }
            if !st.conversation_multiple.is_empty() {
                call = call.ez(
                    "conversation-return-multiple-messages",
                    &st.conversation_multiple,
                );
            }
            if !st.conversation_nested.is_empty() {
                call = call.ez("conversation-return-nested-view", &st.conversation_nested);
            }
            // sic: the script tests the sort order here
            if !st.conversation_sort_order.is_empty() {
                call = call.ez(
                    "conversation-return-no-order-reverse",
                    &st.conversation_no_order_reverse,
                );
            }
        }
    }

    call = call
        .ei("limit", &st.limit)
        .ei("offset", &st.offset)
        .ei("type", type_column);

    if !st.selection.is_empty() {
        call = call.es("message-selection", &st.selection);
    }
    if !st.address.is_empty() {
        call = call.es("from", &st.address);
    }
    if !st.sort_order.is_empty() {
        call = call.es("message-sort-order", &st.sort_order);
    }
    if !st.no_order_reverse.is_empty() {
        call = call.ez("message-return-no-order-reverse", &st.no_order_reverse);
    }
    if !st.show_dates.is_empty() {
        call = call.ez("show-dates", &st.show_dates);
    }
    if !st.show_phone_numbers.is_empty() {
        call = call.ez("show-phone-numbers", &st.show_phone_numbers);
    }

    if st.log_level >= 2 {
        let mut line = format!("termux-api {CONTROLLER}");
        for a in &call.args {
            line.push_str(&format!(" `{a}`"));
        }
        log(io, &st, 2, &line);
    }

    match io.call(call) {
        Ok(mut reply) => {
            reply.close_fd();
            log(io, &st, 2, "Command successful");
            0
        }
        Err(e) => {
            io.errln(&format!("tpp: {e}"));
            log_error(io, "Command failed with exit code 1");
            1
        }
    }
}
