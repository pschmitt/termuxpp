//! The commands, one module per implementation (`imp` of the command table).
//!
//! Every module has
//!   `pub const USAGE: &str`  the text `-h` prints (captured from the script),
//!   `pub fn run(args: &[String], io: &mut Io) -> i32`  the command, where
//!   `args` is what the script received as `"$@"`.

use crate::Io;

pub type Handler = fn(&[String], &mut Io) -> i32;

/// `show_usage`: print the usage text, exit status 0.
pub fn show_usage(io: &mut Io, usage: &str) -> i32 {
    io.out(usage);
    0
}

/// `?) echo "$SCRIPTNAME: illegal option -$OPTARG"; exit 1` (on standard
/// output, as the scripts do).
pub fn illegal_option(io: &mut Io, name: &str, opt: char) -> i32 {
    invalid_argument(io, &format!("{name}: illegal option -{opt}"))
}

pub(super) fn invalid_argument(io: &mut Io, message: &str) -> i32 {
    if io.strict_usage {
        io.errln(message);
        2
    } else {
        io.outln(message);
        1
    }
}

/// The options of a script that only knows `-h`
/// (`while getopts :h option ... done; shift $((OPTIND-1))`): `Ok(rest)` with
/// the operands, or `Err(status)` when `-h` or an illegal option ended the run.
pub fn only_help<'a>(
    args: &'a [String],
    io: &mut Io,
    name: &str,
    usage: &str,
) -> Result<&'a [String], i32> {
    use crate::getopts::{Getopts, Opt};
    let mut g = Getopts::new(args, ":h");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => return Err(show_usage(io, usage)),
            Opt::Illegal(c) | Opt::MissingArg(c) | Opt::Arg(c, _) => {
                return Err(illegal_option(io, name, c))
            }
            Opt::Flag(_) => {}
        }
    }
    Ok(g.rest())
}

/// `if [ $# != 0 ]; then echo "$SCRIPTNAME: too many arguments"; exit 1; fi`
pub fn no_operands(io: &mut Io, name: &str, rest: &[String]) -> Option<i32> {
    if rest.is_empty() {
        None
    } else {
        Some(invalid_argument(io, &format!("{name}: too many arguments")))
    }
}

/// The scripts of the form "-h, no operands, call a method": the whole command.
pub fn simple_call(
    args: &[String],
    io: &mut Io,
    name: &str,
    usage: &str,
    call: crate::Call,
) -> i32 {
    let rest = match only_help(args, io, name, usage) {
        Ok(r) => r,
        Err(status) => return status,
    };
    if let Some(status) = no_operands(io, name, rest) {
        return status;
    }
    io.run_call(call)
}

pub mod api_start;
pub mod api_stop;
pub mod audio_info;
pub mod battery_status;
pub mod brightness;
pub mod call_log;
pub mod camera_info;
pub mod camera_photo;
pub mod clipboard_get;
pub mod clipboard_set;
pub mod contact_list;
pub mod dialog;
pub mod download;
pub mod fingerprint;
pub mod infrared_frequencies;
pub mod infrared_transmit;
pub mod job_scheduler;
pub mod keystore;
pub mod location;
pub mod media_player;
pub mod media_scan;
pub mod microphone_record;
pub mod nfc;
pub mod notification;
pub mod notification_channel;
pub mod notification_list;
pub mod notification_remove;
pub mod saf_create;
pub mod saf_dirs;
pub mod saf_ls;
pub mod saf_managedir;
pub mod saf_mkdir;
pub mod saf_read;
pub mod saf_rm;
pub mod saf_stat;
pub mod saf_write;
pub mod sensor;
pub mod share;
pub mod sms_list;
pub mod sms_send;
pub mod speech_to_text;
pub mod storage_get;
pub mod telephony_call;
pub mod telephony_cellinfo;
pub mod telephony_deviceinfo;
pub mod toast;
pub mod torch;
pub mod tts_engines;
pub mod tts_speak;
pub mod usb;
pub mod vibrate;
pub mod volume;
pub mod wallpaper;
pub mod wifi_connectioninfo;
pub mod wifi_enable;
pub mod wifi_scaninfo;

/// Every implementation of the command table, ported or not.
pub const ALL: &[&str] = &[
    "api-start",
    "api-stop",
    "audio-info",
    "battery-status",
    "brightness",
    "call-log",
    "camera-info",
    "camera-photo",
    "clipboard-get",
    "clipboard-set",
    "contact-list",
    "dialog",
    "download",
    "fingerprint",
    "infrared-frequencies",
    "infrared-transmit",
    "job-scheduler",
    "keystore",
    "location",
    "media-player",
    "media-scan",
    "microphone-record",
    "nfc",
    "notification",
    "notification-channel",
    "notification-list",
    "notification-remove",
    "saf-create",
    "saf-dirs",
    "saf-ls",
    "saf-managedir",
    "saf-mkdir",
    "saf-read",
    "saf-rm",
    "saf-stat",
    "saf-write",
    "sensor",
    "share",
    "sms-list",
    "sms-send",
    "speech-to-text",
    "storage-get",
    "telephony-call",
    "telephony-cellinfo",
    "telephony-deviceinfo",
    "toast",
    "torch",
    "tts-engines",
    "tts-speak",
    "usb",
    "vibrate",
    "volume",
    "wallpaper",
    "wifi-connectioninfo",
    "wifi-enable",
    "wifi-scaninfo",
];

/// The implementations `tpp` dispatches to this crate: all of them.
pub const IMPLS: &[&str] = ALL;

/// The command of any module, ported or not (for tests).
pub fn lookup_any(imp: &str) -> Option<Handler> {
    Some(match imp {
        "api-start" => api_start::run,
        "api-stop" => api_stop::run,
        "audio-info" => audio_info::run,
        "battery-status" => battery_status::run,
        "brightness" => brightness::run,
        "call-log" => call_log::run,
        "camera-info" => camera_info::run,
        "camera-photo" => camera_photo::run,
        "clipboard-get" => clipboard_get::run,
        "clipboard-set" => clipboard_set::run,
        "contact-list" => contact_list::run,
        "dialog" => dialog::run,
        "download" => download::run,
        "fingerprint" => fingerprint::run,
        "infrared-frequencies" => infrared_frequencies::run,
        "infrared-transmit" => infrared_transmit::run,
        "job-scheduler" => job_scheduler::run,
        "keystore" => keystore::run,
        "location" => location::run,
        "media-player" => media_player::run,
        "media-scan" => media_scan::run,
        "microphone-record" => microphone_record::run,
        "nfc" => nfc::run,
        "notification" => notification::run,
        "notification-channel" => notification_channel::run,
        "notification-list" => notification_list::run,
        "notification-remove" => notification_remove::run,
        "saf-create" => saf_create::run,
        "saf-dirs" => saf_dirs::run,
        "saf-ls" => saf_ls::run,
        "saf-managedir" => saf_managedir::run,
        "saf-mkdir" => saf_mkdir::run,
        "saf-read" => saf_read::run,
        "saf-rm" => saf_rm::run,
        "saf-stat" => saf_stat::run,
        "saf-write" => saf_write::run,
        "sensor" => sensor::run,
        "share" => share::run,
        "sms-list" => sms_list::run,
        "sms-send" => sms_send::run,
        "speech-to-text" => speech_to_text::run,
        "storage-get" => storage_get::run,
        "telephony-call" => telephony_call::run,
        "telephony-cellinfo" => telephony_cellinfo::run,
        "telephony-deviceinfo" => telephony_deviceinfo::run,
        "toast" => toast::run,
        "torch" => torch::run,
        "tts-engines" => tts_engines::run,
        "tts-speak" => tts_speak::run,
        "usb" => usb::run,
        "vibrate" => vibrate::run,
        "volume" => volume::run,
        "wallpaper" => wallpaper::run,
        "wifi-connectioninfo" => wifi_connectioninfo::run,
        "wifi-enable" => wifi_enable::run,
        "wifi-scaninfo" => wifi_scaninfo::run,
        _ => return None,
    })
}

/// The command `imp`, if it is ported.
pub fn lookup(imp: &str) -> Option<Handler> {
    if IMPLS.contains(&imp) {
        lookup_any(imp)
    } else {
        None
    }
}

/// The usage text of any module.
pub fn usage(imp: &str) -> Option<&'static str> {
    Some(match imp {
        "api-start" => api_start::USAGE,
        "api-stop" => api_stop::USAGE,
        "audio-info" => audio_info::USAGE,
        "battery-status" => battery_status::USAGE,
        "brightness" => brightness::USAGE,
        "call-log" => call_log::USAGE,
        "camera-info" => camera_info::USAGE,
        "camera-photo" => camera_photo::USAGE,
        "clipboard-get" => clipboard_get::USAGE,
        "clipboard-set" => clipboard_set::USAGE,
        "contact-list" => contact_list::USAGE,
        "dialog" => dialog::USAGE,
        "download" => download::USAGE,
        "fingerprint" => fingerprint::USAGE,
        "infrared-frequencies" => infrared_frequencies::USAGE,
        "infrared-transmit" => infrared_transmit::USAGE,
        "job-scheduler" => job_scheduler::USAGE,
        "keystore" => keystore::USAGE,
        "location" => location::USAGE,
        "media-player" => media_player::USAGE,
        "media-scan" => media_scan::USAGE,
        "microphone-record" => microphone_record::USAGE,
        "nfc" => nfc::USAGE,
        "notification" => notification::USAGE,
        "notification-channel" => notification_channel::USAGE,
        "notification-list" => notification_list::USAGE,
        "notification-remove" => notification_remove::USAGE,
        "saf-create" => saf_create::USAGE,
        "saf-dirs" => saf_dirs::USAGE,
        "saf-ls" => saf_ls::USAGE,
        "saf-managedir" => saf_managedir::USAGE,
        "saf-mkdir" => saf_mkdir::USAGE,
        "saf-read" => saf_read::USAGE,
        "saf-rm" => saf_rm::USAGE,
        "saf-stat" => saf_stat::USAGE,
        "saf-write" => saf_write::USAGE,
        "sensor" => sensor::USAGE,
        "share" => share::USAGE,
        "sms-list" => sms_list::USAGE,
        "sms-send" => sms_send::USAGE,
        "speech-to-text" => speech_to_text::USAGE,
        "storage-get" => storage_get::USAGE,
        "telephony-call" => telephony_call::USAGE,
        "telephony-cellinfo" => telephony_cellinfo::USAGE,
        "telephony-deviceinfo" => telephony_deviceinfo::USAGE,
        "toast" => toast::USAGE,
        "torch" => torch::USAGE,
        "tts-engines" => tts_engines::USAGE,
        "tts-speak" => tts_speak::USAGE,
        "usb" => usb::USAGE,
        "vibrate" => vibrate::USAGE,
        "volume" => volume::USAGE,
        "wallpaper" => wallpaper::USAGE,
        "wifi-connectioninfo" => wifi_connectioninfo::USAGE,
        "wifi-enable" => wifi_enable::USAGE,
        "wifi-scaninfo" => wifi_scaninfo::USAGE,
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tables_are_consistent() {
        for imp in ALL {
            assert!(lookup_any(imp).is_some(), "{imp} has no module");
            assert!(usage(imp).is_some(), "{imp} has no usage");
        }
        for imp in IMPLS {
            assert!(ALL.contains(imp), "{imp} is not an implementation");
        }
        assert_eq!(ALL.len(), 56);
    }
}
