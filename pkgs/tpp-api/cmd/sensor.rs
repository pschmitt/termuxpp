//! `sensor`: `tpp sensor [-h] [-a] [-c] [-l] [-s sensors] [-d ms] [-n num]`.
//!
//! Deviations:
//! - SIGINT and SIGTERM during the call are handled as the script's trap does:
//!   "Caught interrupt.. Finishing...", then "Performing sensor cleanup" and the
//!   `cleanup` call when sensors were being listened to (else "No cleanup
//!   necessary"), then the end with status 128 plus the signal number (what the
//!   script ends with when the terminal's ^C also killed the client). The script
//!   only runs its trap once the client has ended, so a signal sent to the
//!   script alone waits for the stream to end; here it ends the stream at once.
//!   A second signal during the cleanup call cuts the cleanup short.
//! - Option `-,` (the script's option string contains commas, which makes `,` a
//!   valid option) ends the script with bash's "OPTARG: unbound variable" error;
//!   the text of that stderr message differs (it names the script and line).
use crate::getopts::{Getopts, Opt};
use crate::sys::{self, InterruptGuard};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/sensor.txt");
const NAME: &str = "tpp sensor";

const SENSOR_FLAG: u32 = 1;
const ALL_SENSORS_FLAG: u32 = 2;
const LIST_FLAG: u32 = 4;
const CLEANUP_FLAG: u32 = 8;
const DELAY_FLAG: u32 = 16;
const LIMIT_FLAG: u32 = 32;

/// `usage_error`: the message, then `show_usage`, which exits with status 0.
fn usage_error(io: &mut Io, msg: &str) -> i32 {
    io.outln(&format!("ERROR: {msg}"));
    io.out(USAGE);
    0
}

/// `[[ $1 =~ ^[0-9]+$ ]]`
fn is_number(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    if args.is_empty() {
        io.outln("No arguments supplied!");
        io.out(USAGE);
        return 0; // show_usage exits before the script's `exit 1`
    }

    let mut flags = 0u32;
    let mut params: Vec<String> = Vec::new();
    let push = |p: &mut Vec<String>, items: &[&str]| p.extend(items.iter().map(|s| s.to_string()));

    let mut g = Getopts::new(args, ":h,a,c,l,s:d:n:");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => {
                io.out(USAGE);
                return 0;
            }
            Opt::Flag('a') => {
                flags |= ALL_SENSORS_FLAG;
                push(&mut params, &["-a", "sensors", "--ez", "all", "true"]);
            }
            Opt::Flag('c') => {
                flags |= CLEANUP_FLAG;
                params.clear();
                push(&mut params, &["-a", "cleanup"]);
            }
            Opt::Flag('l') => {
                flags |= LIST_FLAG;
                params.clear();
                push(&mut params, &["-a", "list"]);
            }
            Opt::Arg('s', v) => {
                flags |= SENSOR_FLAG;
                push(&mut params, &["-a", "sensors", "--es", "sensors", &v]);
            }
            Opt::Arg('d', v) => {
                flags |= DELAY_FLAG;
                if !is_number(&v) {
                    return usage_error(io, "Illegal argument! -d delay arg should be a number!");
                }
                push(&mut params, &["--ei", "delay", &v]);
            }
            Opt::Arg('n', v) => {
                flags |= LIMIT_FLAG;
                if !is_number(&v) {
                    return usage_error(io, "Illegal argument! -n limit arg should be a number!");
                }
                push(&mut params, &["--ei", "limit", &v]);
            }
            Opt::Flag(',') => {
                // `?)` matches the one-character "," and expands the unset
                // OPTARG under `set -u`
                io.errln(&format!("{NAME}: OPTARG: unbound variable"));
                return 1;
            }
            Opt::Illegal(c) | Opt::MissingArg(c) => return super::illegal_option(io, NAME, c),
            Opt::Flag(_) | Opt::Arg(..) => {}
        }
    }

    if flags & CLEANUP_FLAG != 0 && flags != CLEANUP_FLAG {
        return usage_error(io, "No other options should be specified with -c cleanup!");
    } else if flags & LIST_FLAG != 0 && flags != LIST_FLAG {
        return usage_error(io, "No other options should be specified with -l list!");
    } else if flags & DELAY_FLAG != 0 && flags & (ALL_SENSORS_FLAG | SENSOR_FLAG) == 0 {
        return usage_error(io, "Use -s <sensors> or -a option when using -d delay!");
    } else if flags & ALL_SENSORS_FLAG != 0 && flags & SENSOR_FLAG != 0 {
        return usage_error(io, "Listed sensors will be ignored with -a all option!");
    }

    stream(io, params, flags)
}

/// The final `call_api "$@"` with the script's trap in place.
fn stream(io: &mut Io, params: Vec<String>, flags: u32) -> i32 {
    let guard = InterruptGuard::install();
    let result = io.call(Call::new("Sensor").args(params));
    let signal = sys::take_interrupt();
    let status = match result {
        Ok(mut reply) => {
            reply.close_fd();
            0
        }
        Err(e) => {
            if signal.is_none() {
                io.errln(&format!("tpp: {e}"));
            }
            1
        }
    };
    let Some(signal) = signal else {
        return status;
    };

    // handle_interrupt
    io.outln("Caught interrupt.. Finishing...");
    let cleanup = if flags & (ALL_SENSORS_FLAG | SENSOR_FLAG) != 0 {
        io.outln("Performing sensor cleanup");
        io.run_call(Call::new("Sensor").args(["-a", "cleanup"]))
    } else {
        io.outln("No cleanup necessary");
        0
    };
    drop(guard);
    if cleanup != 0 {
        cleanup
    } else {
        128 + signal
    }
}
