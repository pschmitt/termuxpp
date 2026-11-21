//! `notification`: `tpp notification send [options]`.
//!
//! The script parses its options with util-linux `getopt` (see
//! [`crate::getopt_long`]), builds the `Notification` call from the
//! non-empty options and pipes the content on its standard input (the
//! `--content` argument, else standard input with a 3 s timeout).
//!
//! Deviations: none apart from those of [`crate::getopt_long`].
use crate::getopt_long::getopt_long;
use crate::{Call, Io};
use std::time::Duration;

pub const USAGE: &str = include_str!("../help/notification.txt");
const NAME: &str = "tpp notification send";

const SHORT: &str = "hc:i:t:";
const LONG: &[&str] = &[
    "action:",
    "alert-once",
    "button1:",
    "button1-action:",
    "button2:",
    "button2-action:",
    "button3:",
    "button3-action:",
    "content:",
    "channel:",
    "group:",
    "help",
    "help-actions",
    "id:",
    "icon:",
    "image-path:",
    "led-color:",
    "led-on:",
    "led-off:",
    "media-previous:",
    "media-next:",
    "media-play:",
    "media-pause:",
    "on-delete:",
    "ongoing",
    "priority:",
    "sound",
    "title:",
    "type:",
    "vibrate:",
];

const HELP_ACTIONS: &str = r##"This help refers to the arguments to options like --action, --on-delete, --button-1-action and --media-next.

All these commands take an action string as their argument, which is fed to `dash -c`.
A few important things must be kept in mind when using actions:

You should use actions that do things outside of the terminal, like --action "tpp toast hello".
Anything that outputs to the terminal is useless, so the output should either be redirected (--action "ls > ~/ls.txt") or shown to the user in a different way (--action "ls|tpp toast").

Running more than one command in a single action is as easy as
--action "command1; command2; command3"
or
--action "if [ -e file ]; then tpp toast yes; else tpp toast no; fi".

For anything more complex, you should put your script in a file, make it executable, and use that as the action:
--action ~/bin/script

The action is run in a different environment (not a subshell). Thus your environment is lost (most notably $PATH), and ~/.profile is not sourced either. So if you need your $PATH you should either:
 - if the action is a script, set it explicitly in the script (e.g. export PATH="$HOME/bin:$PATH")
 - or use something like --action "bash -l -c 'command1; command2'").

On Android N or above, you can use the special variable $REPLY in your actions to use Android's Direct Reply feature.
This prompts the user to enter some text directly in the notification, which is then substituted into your action.
 - tpp notification send --button1 "Answer" --button1-action "tpp toast \$REPLY"
will call the action:
 - tpp toast "Some text entered by the user"
Be careful to escape shell commands correctly for single or double quotes, e.g.
  --button1-action 'something $REPLY' or --button1-action "something \$REPLY"
"##;

/// bash's `echo` takes `-n`, `-e` and `-E` (and clusters of them) as options.
fn is_echo_option(s: &[u8]) -> bool {
    s.len() > 1 && s[0] == b'-' && s[1..].iter().all(|c| matches!(c, b'n' | b'e' | b'E'))
}

/// What `echo "$text"` prints.
fn echo_line(text: &[u8]) -> Vec<u8> {
    if is_echo_option(text) {
        // the whole argument is an option: nothing to print; `-n` also drops
        // the newline
        return if text.contains(&b'n') {
            Vec::new()
        } else {
            b"\n".to_vec()
        };
    }
    let mut out = text.to_vec();
    out.push(b'\n');
    out
}

/// `$(echo "$text")`: the same without the trailing newlines.
fn echo_substituted(text: &[u8]) -> Vec<u8> {
    let mut out = echo_line(text);
    while out.last() == Some(&b'\n') {
        out.pop();
    }
    out
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let posix = io.env.get("POSIXLY_CORRECT").is_some();
    let parsed = match getopt_long(NAME, args, SHORT, LONG, posix) {
        Ok(p) => p,
        Err(stderr) => {
            io.err(&stderr);
            return 1;
        }
    };

    let mut action = String::new();
    let mut alert_once = String::new();
    let mut button_action = [String::new(), String::new(), String::new()];
    let mut button_text = [String::new(), String::new(), String::new()];
    let mut content = String::new();
    let mut content_passed = false;
    let mut channel = String::new();
    let mut group = String::new();
    let mut id = String::new();
    let mut icon = String::new();
    let mut image_path = String::new();
    let mut led_color = String::new();
    let mut led_off = String::new();
    let mut led_on = String::new();
    let mut media_next = String::new();
    let mut media_pause = String::new();
    let mut media_play = String::new();
    let mut media_previous = String::new();
    let mut ongoing = String::new();
    let mut on_delete = String::new();
    let mut priority = String::new();
    let mut sound = String::new();
    let mut title = String::new();
    let mut kind = String::new();
    let mut vibrate = String::new();

    for (opt, value) in &parsed.options {
        let v = value.clone().unwrap_or_default();
        match opt.as_str() {
            "--action" => action = v,
            "--alert-once" => alert_once = "true".to_string(),
            "--button1" => button_text[0] = v,
            "--button1-action" => button_action[0] = v,
            "--button2" => button_text[1] = v,
            "--button2-action" => button_action[1] = v,
            "--button3" => button_text[2] = v,
            "--button3-action" => button_action[2] = v,
            "-c" | "--content" => {
                content_passed = true;
                content = v;
            }
            "--channel" => channel = v,
            "--group" => group = v,
            "-h" | "--help" => return super::show_usage(io, USAGE),
            "--help-actions" => {
                io.out(HELP_ACTIONS);
                return 0;
            }
            "-i" | "--id" => id = v,
            "--icon" => icon = v,
            "--image-path" => image_path = v,
            "--led-color" => led_color = v,
            "--led-off" => led_off = v,
            "--led-on" => led_on = v,
            "--media-next" => media_next = v,
            "--media-pause" => media_pause = v,
            "--media-play" => media_play = v,
            "--media-previous" => media_previous = v,
            "--on-delete" => on_delete = v,
            "--ongoing" => ongoing = "true".to_string(),
            "--priority" => priority = v,
            "--sound" => sound = "true".to_string(),
            "-t" | "--title" => title = v,
            "--type" => kind = v,
            "--vibrate" => vibrate = v,
            _ => {}
        }
    }

    if !parsed.operands.is_empty() {
        io.outln(&format!("{NAME}: too many arguments"));
        return 1;
    }

    if ongoing == "true" && id.is_empty() {
        io.outln("Ongoing notifications without an ID are not removable.");
        io.outln("Please set an id with --id \"string\".");
        return 1;
    }

    let mut call = Call::new("Notification");
    let es = |call: Call, key: &str, value: &str| {
        if value.is_empty() {
            call
        } else {
            call.es(key, value)
        }
    };
    call = es(call, "action", &action);
    if !alert_once.is_empty() {
        call = call.ez("alert-once", &alert_once);
    }
    for n in 0..3 {
        call = es(call, &format!("button_action_{}", n + 1), &button_action[n]);
        call = es(call, &format!("button_text_{}", n + 1), &button_text[n]);
    }
    call = es(call, "channel", &channel);
    call = es(call, "group", &group);
    call = es(call, "id", &id);
    call = es(call, "icon", &icon);
    call = es(call, "image-path", &image_path);
    call = es(call, "led-color", &led_color);
    if !led_off.is_empty() {
        call = call.ei("led-off", &led_off);
    }
    if !led_on.is_empty() {
        call = call.ei("led-on", &led_on);
    }
    call = es(call, "media-next", &media_next);
    call = es(call, "media-pause", &media_pause);
    call = es(call, "media-play", &media_play);
    call = es(call, "media-previous", &media_previous);
    if !ongoing.is_empty() {
        call = call.ez("ongoing", &ongoing);
    }
    call = es(call, "on_delete_action", &on_delete);
    call = es(call, "priority", &priority);
    if !sound.is_empty() {
        call = call.ez("sound", &sound);
    }
    call = es(call, "title", &title);
    call = es(call, "type", &kind);
    if !vibrate.is_empty() {
        call = call.args(["--ela", "vibrate", vibrate.as_str()]);
    }

    // Without --content the text comes from standard input (3 s timeout):
    //   IFS= read -t 3 -r -d '' OPT_CONTENT
    let mut text: Vec<u8> = if content_passed {
        content.into_bytes()
    } else {
        io.read_stdin_until_nul(Duration::from_secs(3))
    };
    // OPT_CONTENT="$(echo "$OPT_CONTENT")"
    text = echo_substituted(&text);
    // echo "$OPT_CONTENT" | termux-api Notification "$@"
    io.run_call(call.stdin_bytes(echo_line(&text)))
}
