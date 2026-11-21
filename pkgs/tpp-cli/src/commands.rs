//! The built-in command and option tables of `tpp`.
//!
//! One row per command: the words that select it (`group`, `action`), the
//! implementation, how its standard output is treated (`class`), the fixed
//! leading arguments and the description for `tpp list`. An action of `-`
//! stands for a command without one.
//!
//! `class` is `auto`, `text` or `raw`, optionally followed by overrides for
//! some arguments, as in "auto,sign=raw" (raw if the first argument is `sign`)
//! and "auto,-e=raw" (raw if the leading options contain -e):
//!   auto: JSON is rendered (human text by default, pretty JSON with --json),
//!         anything else is passed through unchanged, or wrapped as
//!         {"output": TEXT} with --json
//!   text: plain text, never parsed as JSON: passed through unchanged and
//!         streamed, or wrapped as {"output": TEXT} with --json
//!   raw:  binary or streamed data, always passed through unchanged: --json is
//!         refused (but not for --help)
//!
//! The option words are what `tpp --complete` suggests after a command. They
//! must match the usage text of the command (a test checks that every
//! option appears in `tpp COMMAND --help`):
//!   -x or --long            an option without a value
//!   -x: or --long:          an option with a value, nothing suggested for it
//!   -x:A,B or --long:A,B    an option with a value, suggested values
//!   --long=                 an option written with an attached value
//!   word                    a word that may follow the command (no dash)

pub struct Command {
    pub group: &'static str,
    pub action: &'static str,
    /// the command of the crate `tpp-api` that implements it
    pub imp: &'static str,
    pub class: &'static str,
    pub args: &'static str,
    pub desc: &'static str,
}

pub struct Options {
    pub group: &'static str,
    pub action: &'static str,
    pub words: &'static str,
}

#[rustfmt::skip]
pub const COMMANDS: &[Command] = &[
    Command { group: "api", action: "start", imp: "api-start", class: "auto", args: "-", desc: "Start the TermuxPP API keep-alive service" },
    Command { group: "api", action: "stop", imp: "api-stop", class: "auto", args: "-", desc: "Stop the TermuxPP API keep-alive service" },
    Command { group: "audio", action: "info", imp: "audio-info", class: "auto", args: "-", desc: "Show audio device information" },
    Command { group: "audio", action: "record", imp: "microphone-record", class: "auto", args: "-", desc: "Record audio from the microphone" },
    Command { group: "battery", action: "-", imp: "battery-status", class: "auto", args: "-", desc: "Show the battery status" },
    Command { group: "brightness", action: "-", imp: "brightness", class: "auto", args: "-", desc: "Set the screen brightness" },
    Command { group: "call-log", action: "-", imp: "call-log", class: "auto", args: "-", desc: "List the call log" },
    Command { group: "camera", action: "info", imp: "camera-info", class: "auto", args: "-", desc: "List the available cameras" },
    Command { group: "camera", action: "photo", imp: "camera-photo", class: "auto", args: "-", desc: "Take a photo" },
    Command { group: "clipboard", action: "get", imp: "clipboard-get", class: "text", args: "-", desc: "Print the clipboard contents" },
    Command { group: "clipboard", action: "set", imp: "clipboard-set", class: "auto", args: "-", desc: "Set the clipboard contents" },
    Command { group: "contacts", action: "list", imp: "contact-list", class: "auto", args: "-", desc: "List contacts" },
    Command { group: "dialog", action: "-", imp: "dialog", class: "auto", args: "-", desc: "Show an input dialog and print the answer" },
    Command { group: "download", action: "-", imp: "download", class: "auto", args: "-", desc: "Download a file with the system download manager" },
    Command { group: "fingerprint", action: "-", imp: "fingerprint", class: "auto", args: "-", desc: "Authenticate with the fingerprint sensor" },
    Command { group: "infrared", action: "frequencies", imp: "infrared-frequencies", class: "auto", args: "-", desc: "List the supported infrared carrier frequencies" },
    Command { group: "infrared", action: "transmit", imp: "infrared-transmit", class: "auto", args: "-", desc: "Transmit an infrared pattern" },
    Command { group: "job-scheduler", action: "-", imp: "job-scheduler", class: "auto", args: "-", desc: "Schedule a script to run as a job" },
    Command { group: "keystore", action: "-", imp: "keystore", class: "auto,sign=raw", args: "-", desc: "Manage keys in the Android keystore" },
    Command { group: "location", action: "-", imp: "location", class: "auto", args: "-", desc: "Show the device location" },
    Command { group: "media", action: "player", imp: "media-player", class: "auto", args: "-", desc: "Control the media player" },
    Command { group: "media", action: "scan", imp: "media-scan", class: "auto", args: "-", desc: "Add files to the media library" },
    Command { group: "nfc", action: "-", imp: "nfc", class: "auto", args: "-", desc: "Read or write NFC tags" },
    Command { group: "notification", action: "send", imp: "notification", class: "auto", args: "-", desc: "Show a notification" },
    Command { group: "notification", action: "channel", imp: "notification-channel", class: "auto", args: "-", desc: "Create or delete a notification channel" },
    Command { group: "notification", action: "list", imp: "notification-list", class: "auto", args: "-", desc: "List the active notifications" },
    Command { group: "notification", action: "remove", imp: "notification-remove", class: "auto", args: "-", desc: "Remove a notification" },
    Command { group: "saf", action: "create", imp: "saf-create", class: "auto", args: "-", desc: "Create a file in a managed directory" },
    Command { group: "saf", action: "dirs", imp: "saf-dirs", class: "auto", args: "-", desc: "List the managed directories" },
    Command { group: "saf", action: "ls", imp: "saf-ls", class: "auto", args: "-", desc: "List the contents of a directory" },
    Command { group: "saf", action: "managedir", imp: "saf-managedir", class: "auto", args: "-", desc: "Grant access to a directory" },
    Command { group: "saf", action: "mkdir", imp: "saf-mkdir", class: "auto", args: "-", desc: "Create a directory" },
    Command { group: "saf", action: "read", imp: "saf-read", class: "raw", args: "-", desc: "Read a file to standard output" },
    Command { group: "saf", action: "rm", imp: "saf-rm", class: "auto", args: "-", desc: "Remove a file or directory" },
    Command { group: "saf", action: "stat", imp: "saf-stat", class: "auto", args: "-", desc: "Show information about a file or directory" },
    Command { group: "saf", action: "write", imp: "saf-write", class: "auto", args: "-", desc: "Write standard input to a file" },
    Command { group: "sensor", action: "-", imp: "sensor", class: "raw", args: "-", desc: "List sensors or read sensor values" },
    Command { group: "share", action: "-", imp: "share", class: "auto", args: "-", desc: "Share a file or text with another app" },
    Command { group: "sms", action: "list", imp: "sms-list", class: "auto", args: "-", desc: "List text messages" },
    Command { group: "sms", action: "send", imp: "sms-send", class: "auto", args: "-", desc: "Send a text message" },
    Command { group: "speech-to-text", action: "-", imp: "speech-to-text", class: "text", args: "-", desc: "Recognise speech from the microphone" },
    Command { group: "storage", action: "get", imp: "storage-get", class: "auto", args: "-", desc: "Pick a file with the system file picker" },
    Command { group: "telephony", action: "call", imp: "telephony-call", class: "auto", args: "-", desc: "Place a phone call" },
    Command { group: "telephony", action: "cell-info", imp: "telephony-cellinfo", class: "auto", args: "-", desc: "Show cell tower information" },
    Command { group: "telephony", action: "device-info", imp: "telephony-deviceinfo", class: "auto", args: "-", desc: "Show telephony device information" },
    Command { group: "toast", action: "-", imp: "toast", class: "auto", args: "-", desc: "Show a toast message" },
    Command { group: "torch", action: "-", imp: "torch", class: "auto", args: "-", desc: "Turn the torch on or off" },
    Command { group: "tts", action: "engines", imp: "tts-engines", class: "auto", args: "-", desc: "List the text-to-speech engines" },
    Command { group: "tts", action: "speak", imp: "tts-speak", class: "auto", args: "-", desc: "Speak text aloud" },
    Command { group: "usb", action: "-", imp: "usb", class: "auto,-e=raw", args: "-", desc: "List or access USB devices" },
    Command { group: "vibrate", action: "-", imp: "vibrate", class: "auto", args: "-", desc: "Vibrate the device" },
    Command { group: "volume", action: "-", imp: "volume", class: "auto", args: "-", desc: "Show or set the volume" },
    Command { group: "wallpaper", action: "-", imp: "wallpaper", class: "auto", args: "-", desc: "Set the wallpaper" },
    Command { group: "wifi", action: "connection", imp: "wifi-connectioninfo", class: "auto", args: "-", desc: "Show the current Wi-Fi connection" },
    Command { group: "wifi", action: "scan", imp: "wifi-scaninfo", class: "auto", args: "-", desc: "Show the results of the last Wi-Fi scan" },
    Command { group: "wifi", action: "enable", imp: "wifi-enable", class: "auto", args: "true", desc: "Turn Wi-Fi on" },
    Command { group: "wifi", action: "disable", imp: "wifi-enable", class: "auto", args: "false", desc: "Turn Wi-Fi off" },
];

#[rustfmt::skip]
pub const OPTIONS: &[Options] = &[
    Options { group: "audio", action: "record", words: "-d -f: -l: -e:aac,amr_wb,amr_nb,opus -b: -r: -c: -i -q" },
    Options { group: "brightness", action: "-", words: "auto" },
    Options { group: "call-log", action: "-", words: "-l: -o:" },
    Options { group: "camera", action: "photo", words: "-c:" },
    Options { group: "dialog", action: "-", words: "confirm checkbox counter date radio sheet spinner speech text time -l -t: -i: -m -p -n -d: -v: -r:" },
    Options { group: "download", action: "-", words: "-d: -t: -p:" },
    Options { group: "fingerprint", action: "-", words: "-t: -d: -s: -c:" },
    Options { group: "infrared", action: "transmit", words: "-f:" },
    Options { group: "job-scheduler", action: "-", words: "-p --pending --cancel --cancel-all -s: --script: --job-id: --period-ms: --network:any,unmetered,cellular,not_roaming,none --battery-not-low:true,false --storage-not-low:true,false --charging:true,false --persisted:true,false --trigger-content-uri: --trigger-content-flag:" },
    Options { group: "keystore", action: "-", words: "list delete generate sign verify -d -a:RSA,EC -s:2048,3072,4096,256,384,521 -u:" },
    Options { group: "location", action: "-", words: "-p:gps,network,passive -r:once,last,updates" },
    Options { group: "media", action: "player", words: "info play pause stop" },
    Options { group: "media", action: "scan", words: "-r -v" },
    Options { group: "nfc", action: "-", words: "-r:short,full -w -t:" },
    Options { group: "notification", action: "send", words: "-c: --content: -t: --title: -i: --id: --action: --alert-once --button1: --button1-action: --button2: --button2-action: --button3: --button3-action: --channel: --group: --help-actions --icon: --image-path: --led-color: --led-off: --led-on: --on-delete: --ongoing --priority:high,low,max,min,default --sound --type:default,media --vibrate: --media-next: --media-pause: --media-play: --media-previous:" },
    Options { group: "notification", action: "channel", words: "-d:" },
    Options { group: "saf", action: "create", words: "-t:" },
    Options { group: "sensor", action: "-", words: "-a -c -l -s: -d: -n:" },
    Options { group: "share", action: "-", words: "-a:edit,send,view -c: -d -t:" },
    Options { group: "sms", action: "list", words: "-q --quiet -v -c --conversation-list -f: -l: -o: -t:all,inbox,sent,draft,outbox,failed,queued --conversation-limit= --conversation-offset= --conversation-return-multiple-messages --conversation-return-nested-view --conversation-return-no-order-reverse --conversation-selection= --conversation-sort-order= --message-address= --message-limit= --message-offset= --message-return-no-order-reverse --message-selection= --message-sort-order= --message-type=" },
    Options { group: "sms", action: "send", words: "-n: -s:" },
    Options { group: "toast", action: "-", words: "-b: -c: -g:top,middle,bottom -s" },
    Options { group: "torch", action: "-", words: "on off" },
    Options { group: "tts", action: "speak", words: "-e: -l: -n: -v: -p: -r: -s:ALARM,MUSIC,NOTIFICATION,RING,SYSTEM,VOICE_CALL" },
    Options { group: "usb", action: "-", words: "-l -r -e: -E" },
    Options { group: "vibrate", action: "-", words: "-d: -f" },
    Options { group: "volume", action: "-", words: "alarm music notification ring system call" },
    Options { group: "wallpaper", action: "-", words: "-f: -u: -l" },
];
