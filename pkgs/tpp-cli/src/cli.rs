//! The dispatcher: help, the command and plugin lookup, running the API
//! commands and plugins and printing their output.

use std::collections::hash_map::RandomState;
use std::env;
use std::ffi::OsString;
use std::fs::{self, File, OpenOptions};
use std::hash::BuildHasher;
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::os::raw::c_int;
use std::os::unix::ffi::{OsStrExt, OsStringExt};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicI32, Ordering};

use crate::api;
use crate::commands::long_options;
use crate::complete;
use crate::ctx::{self, Ctx};
use crate::native;
use crate::render::{self, Mode, RenderError};
use crate::table::{Entry, Table};

pub struct App {
    pub ctx: Ctx,
    pub table: Table,
}

fn is(arg: &OsString, text: &str) -> bool {
    arg.to_str() == Some(text)
}

fn lossy(arg: &OsString) -> String {
    arg.to_string_lossy().into_owned()
}

fn write_out(bytes: &[u8]) {
    let mut out = io::stdout().lock();
    let _ = out.write_all(bytes);
    let _ = out.flush();
}

fn write_err(text: &str) {
    let _ = io::stderr().write_all(text.as_bytes());
}

fn write_usage_error(text: &str) {
    write_err(&ctx::color_stderr_help(text));
}

fn write_help(text: &str) {
    write_out(ctx::color_help(text).as_bytes());
}

// ------------------------------------------------------------------ help

fn usage(app: &App) -> String {
    let mut s = String::from(
        "Usage: tpp [--json] COMMAND [ARGS...]\n\
         \x20      tpp help [COMMAND [SUBCOMMAND]]\n\
         \n\
         Control the Android device through the TermuxPP API app.\n\
         \n\
         Options:\n\
         \x20 -h, --help  Show this help. Every command also accepts -h/--help.\n\
         \x20 --json      Print the result as JSON instead of human-readable text. JSON is\n\
         \x20             colored on a terminal and plain when piped.\n\
         \n\
         Commands:\n",
    );
    for group in &app.table.groups {
        let mut actions = String::new();
        let mut desc = "";
        for e in app.table.rows(group) {
            if e.action == "-" {
                desc = &e.desc;
            } else {
                if !actions.is_empty() {
                    actions.push_str(", ");
                }
                actions.push_str(&e.action);
            }
        }
        let text = if actions.is_empty() { desc } else { &actions };
        s.push_str(&format!("  {group:<16} {text}\n"));
    }
    for (name, summary) in native::GROUPS {
        s.push_str(&format!("  {name:<16} {summary}\n"));
    }
    let plugins = app.ctx.list_plugins(&app.table);
    if !plugins.is_empty() {
        s.push_str(&format!("\nPlugins:\n  {}\n", plugins.join(" ")));
    }
    s.push_str(
        "\nOther commands:\n\
         \x20 help    Show help for a command\n",
    );
    s
}

fn group_usage(app: &App, group: &str) -> String {
    let mut s = format!("Usage: tpp [--json] {group} COMMAND [ARGS...]\n\nCommands:\n");
    for e in app.table.rows(group) {
        s.push_str(&format!("  {:<14} {}\n", e.action, e.desc));
    }
    s.push_str(&format!(
        "\nRun \"tpp {group} COMMAND --help\" for the options of a command.\n"
    ));
    s
}

// Enumerate command paths for the build's exhaustive help checks.
fn command_names(app: &App) -> String {
    app.table
        .entries
        .iter()
        .map(Entry::name)
        .chain(native::GROUPS.iter().map(|(name, _)| name.to_string()))
        .chain(app.ctx.list_plugins(&app.table))
        .map(|name| format!("{name}\n"))
        .collect()
}

pub(crate) fn group_names(app: &App) -> Vec<String> {
    app.table
        .groups
        .iter()
        .cloned()
        .chain(native::GROUPS.iter().map(|(name, _)| name.to_string()))
        .chain(["help".to_owned()])
        .chain(app.ctx.list_plugins(&app.table))
        .collect()
}

pub(crate) fn resolve_prefix<'a>(
    word: &str,
    names: impl IntoIterator<Item = &'a str>,
) -> Result<Option<&'a str>, Vec<&'a str>> {
    if word.is_empty() {
        return Ok(None);
    }
    let mut matches = Vec::new();
    for name in names {
        if name == word {
            return Ok(Some(name));
        }
        if name.starts_with(word) && !matches.contains(&name) {
            matches.push(name);
        }
    }
    match matches.as_slice() {
        [] => Ok(None),
        [name] => Ok(Some(name)),
        _ => {
            matches.sort_unstable();
            Err(matches)
        }
    }
}

fn resolve_group<'a>(names: &'a [String], word: &str) -> Result<&'a str, ()> {
    match resolve_prefix(word, names.iter().map(String::as_str)) {
        Ok(Some(name)) => Ok(name),
        Ok(None) => {
            err!("unknown command '{word}'. Try 'tpp help'.");
            Err(())
        }
        Err(matches) => {
            err!(
                "ambiguous command '{word}'. Matches: {}",
                matches.join(", ")
            );
            Err(())
        }
    }
}

fn resolve_action<'a>(app: &'a App, group: &'a str, word: &str) -> Option<&'a Entry> {
    match resolve_prefix(
        word,
        app.table.rows(group).map(|entry| entry.action.as_str()),
    ) {
        Ok(Some(action)) => return app.table.find(group, action),
        Ok(None) => err!("{group}: unknown command '{word}'"),
        Err(matches) => err!(
            "{group}: ambiguous command '{word}'. Matches: {}",
            matches.join(", ")
        ),
    }
    None
}

fn show_help(app: &App, args: &[OsString]) -> i32 {
    let Some(group) = args.first().map(lossy) else {
        write_help(&usage(app));
        return 0;
    };
    let names = group_names(app);
    let Ok(group) = resolve_group(&names, &group) else {
        return 2;
    };
    let action = args.get(1).map(lossy);
    if group == "help" {
        write_help("Usage: tpp help [COMMAND [ACTION]]\nShow help for a command or action.\n");
        return 0;
    }
    if native::is_native(group) {
        let mut help = args[1..].to_vec();
        help.push(OsString::from("--help"));
        return native::run(group, &help, false);
    }
    if app.table.is_group(group) {
        if let Some(e) = app.table.find(group, "-") {
            return run_entry(e, vec![OsString::from("-h")], false);
        }
        if let Some(action) = action {
            if let Some(e) = resolve_action(app, group, &action) {
                return run_entry(e, vec![OsString::from("-h")], false);
            }
            write_usage_error(&group_usage(app, group));
            return 2;
        }
        write_help(&group_usage(app, group));
        return 0;
    }
    if let Some(plugin) = app.ctx.find_plugin(group) {
        return plugin_help(&plugin, args[1..].to_vec());
    }
    err!("unknown command '{group}'");
    2
}

// ------------------------------------------------------- process helpers

/// Replace the process: only returns if that failed.
fn exec(program: &Path, args: &[OsString]) -> i32 {
    let e = Command::new(program).args(args).exec();
    err!("cannot execute {}: {e}", program.display());
    if e.kind() == io::ErrorKind::NotFound {
        127
    } else {
        126
    }
}

fn plugin_help(plugin: &Path, mut args: Vec<OsString>) -> i32 {
    if !args.iter().any(|arg| is(arg, "--help") || is(arg, "-h")) {
        args.push(OsString::from("--help"));
    }
    match Command::new(plugin).args(args).output() {
        Ok(output) => {
            write_help(&String::from_utf8_lossy(&output.stdout));
            write_usage_error(&String::from_utf8_lossy(&output.stderr));
            output.status.code().unwrap_or(1)
        }
        Err(error) => {
            err!("cannot execute {}: {error}", plugin.display());
            126
        }
    }
}

static SIGNAL: AtomicI32 = AtomicI32::new(0);

extern "C" fn on_signal(sig: c_int) {
    SIGNAL.store(sig, Ordering::SeqCst);
}

extern "C" {
    fn signal(sig: c_int, handler: extern "C" fn(c_int)) -> usize;
}

const SIGINT: c_int = 2;
const SIGTERM: c_int = 15;

/// Remember INT and TERM while a command runs, so that the temporary file is
/// removed and the status is 130 or 143 after the command is done.
fn catch_signals() {
    // SAFETY: the handler only stores into an atomic.
    unsafe {
        signal(SIGINT, on_signal);
        signal(SIGTERM, on_signal);
    }
}

fn signal_status() -> Option<i32> {
    match SIGNAL.load(Ordering::SeqCst) {
        0 => None,
        SIGINT => Some(130),
        _ => Some(143),
    }
}

/// A private file for the output of a command, removed when dropped.
struct Temp {
    path: PathBuf,
    file: File,
}

impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

fn temp_dir() -> PathBuf {
    match env::var_os("TMPDIR") {
        Some(d) if !d.is_empty() => PathBuf::from(d),
        _ => PathBuf::from("/tmp"),
    }
}

fn make_temp(dir: &Path) -> io::Result<Temp> {
    let random = RandomState::new();
    let mut last = io::Error::from(io::ErrorKind::AlreadyExists);
    for attempt in 0..100u32 {
        let path = dir.join(format!("tpp.{:08x}", random.hash_one(attempt) as u32));
        match OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
        {
            Ok(file) => return Ok(Temp { path, file }),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => last = e,
            Err(e) => return Err(e),
        }
    }
    Err(last)
}

// ---------------------------------------------------------------- output

/// The first byte of the output that is not white space, looking at the first
/// 4096 bytes only.
fn first_significant(data: &[u8]) -> Option<u8> {
    data.iter()
        .take(4096)
        .copied()
        .find(|b| !matches!(b, b' ' | b'\t' | b'\r' | b'\n'))
}

/// Print the captured output of a command. Binary data is never altered: it
/// is copied as is, or --json refuses it.
fn render_output(class: &str, data: &[u8], json: bool) -> i32 {
    if data.is_empty() {
        if json {
            write_out(b"null\n");
        }
        return 0;
    }
    if data.contains(&0) {
        if json {
            err!("refusing --json: the output is binary (it contains NUL bytes)");
            return 3;
        }
        write_out(data);
        return 0;
    }

    let mode = if json { Mode::Json } else { Mode::Human };
    let color = ctx::use_color();
    if class == "auto" && matches!(first_significant(data), Some(b'{' | b'[')) {
        match render::render(mode, color, data) {
            Ok(out) => {
                write_out(&out);
                return 0;
            }
            // Not valid JSON after all: plain text
            Err(RenderError::Invalid) => {}
            Err(RenderError::Utf8) => {
                err!("refusing --json: the output is not valid UTF-8 text");
                return 3;
            }
        }
    }

    if !json {
        write_out(data);
        return 0;
    }
    match render::render(Mode::Wrap, color, data) {
        Ok(out) => {
            write_out(&out);
            0
        }
        Err(RenderError::Utf8) => {
            err!("refusing --json: the output is not valid UTF-8 text");
            3
        }
        Err(RenderError::Invalid) => {
            err!("could not render the output as JSON");
            1
        }
    }
}

fn api_error(data: &[u8]) -> Option<String> {
    let text = std::str::from_utf8(data).ok()?;
    let value = tpp_session::json::parse(text).ok()?;
    match value.get("API_ERROR")? {
        tpp_session::json::Value::String(message) => Some(message.clone()),
        _ => None,
    }
}

fn report_api_error(message: &str) {
    if ctx::use_color() {
        eprintln!("\x1b[31mtpp: error: {message}\x1b[0m");
    } else {
        eprintln!("tpp: error: {message}");
    }
}

/// Render complete JSON objects from a live stream without waiting for EOF.
struct StreamRenderer<W: Write> {
    out: W,
    pending: Vec<u8>,
    depth: usize,
    quoted: bool,
    escaped: bool,
    structured: bool,
    mode: Mode,
    color: bool,
    invalid_text: bool,
    api_failed: bool,
    unwrap_array: bool,
    array_started: bool,
    array_has_value: bool,
}

impl<W: Write> StreamRenderer<W> {
    fn new(out: W, mode: Mode, color: bool) -> Self {
        Self {
            out,
            pending: Vec::new(),
            depth: 0,
            quoted: false,
            escaped: false,
            structured: false,
            mode,
            color,
            invalid_text: false,
            api_failed: false,
            unwrap_array: false,
            array_started: false,
            array_has_value: false,
        }
    }

    fn emit(&mut self) -> io::Result<()> {
        if self.structured {
            if let Some(message) = api_error(&self.pending) {
                self.api_failed = true;
                if self.mode == Mode::Human {
                    report_api_error(&message);
                    self.pending.clear();
                    self.depth = 0;
                    self.quoted = false;
                    self.escaped = false;
                    self.structured = false;
                    return Ok(());
                }
            }
        }
        if self.mode == Mode::Json
            && (self.pending.contains(&0) || std::str::from_utf8(&self.pending).is_err())
        {
            self.invalid_text = true;
        } else if self.structured {
            match render::render(self.mode, self.color, &self.pending) {
                Ok(rendered) => self.out.write_all(&rendered)?,
                Err(_) if self.mode == Mode::Json => {
                    let wrapped =
                        render::render(Mode::Wrap, self.color, &self.pending).map_err(|_| {
                            io::Error::new(io::ErrorKind::InvalidData, "invalid output")
                        })?;
                    self.out.write_all(&wrapped)?;
                }
                Err(_) => self.out.write_all(&self.pending)?,
            }
        } else if self.mode == Mode::Json && !self.pending.iter().all(u8::is_ascii_whitespace) {
            let wrapped = render::render(Mode::Wrap, self.color, &self.pending)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid output"))?;
            self.out.write_all(&wrapped)?;
        } else {
            self.out.write_all(&self.pending)?;
        }
        self.pending.clear();
        self.depth = 0;
        self.quoted = false;
        self.escaped = false;
        self.structured = false;
        self.out.flush()
    }

    fn finish(&mut self) -> io::Result<()> {
        if !self.pending.is_empty() {
            self.emit()?;
        }
        self.out.flush()
    }
}

impl<W: Write> Write for StreamRenderer<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        for &b in buf {
            if self.unwrap_array && self.pending.is_empty() {
                if !self.array_started && b.is_ascii_whitespace() {
                    continue;
                }
                if !self.array_started && b == b'[' {
                    self.array_started = true;
                    continue;
                }
                if self.array_started && b == b']' {
                    if !self.array_has_value {
                        self.out.write_all(
                            &render::render(Mode::Human, self.color, b"[]").map_err(|_| {
                                io::Error::new(io::ErrorKind::InvalidData, "invalid output")
                            })?,
                        )?;
                        self.out.flush()?;
                    }
                    continue;
                }
                if self.array_started && (b.is_ascii_whitespace() || b == b',') {
                    continue;
                }
                if self.array_started {
                    self.array_has_value = true;
                }
            }
            if !self.structured && self.pending.iter().all(u8::is_ascii_whitespace) {
                self.structured = matches!(b, b'{' | b'[');
            }
            self.pending.push(b);
            if self.structured {
                if self.quoted {
                    if self.escaped {
                        self.escaped = false;
                    } else if b == b'\\' {
                        self.escaped = true;
                    } else if b == b'"' {
                        self.quoted = false;
                    }
                } else {
                    match b {
                        b'"' => self.quoted = true,
                        b'{' | b'[' => self.depth += 1,
                        b'}' | b']' => self.depth = self.depth.saturating_sub(1),
                        _ => {}
                    }
                    if self.depth == 0 {
                        self.emit()?;
                    }
                }
            } else if b == b'\n' {
                self.emit()?;
            }
            // A malformed or unbounded record must not consume unlimited memory.
            if self.pending.len() >= 1024 * 1024 {
                self.structured = false;
                self.emit()?;
            }
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        self.out.flush()
    }
}

#[cfg(test)]
mod stream_tests {
    use super::*;

    #[test]
    fn api_error_is_detected_in_a_stream() {
        let mut stream = StreamRenderer::new(Vec::new(), Mode::Json, false);
        stream
            .write_all(br#"{"API_ERROR":"location denied"}"#)
            .unwrap();
        stream.finish().unwrap();
        assert!(stream.api_failed);
        assert!(String::from_utf8(stream.out)
            .unwrap()
            .contains("location denied"));
        assert_eq!(api_error(br#"[{"API_ERROR":"ordinary data"}]"#), None);
    }

    #[test]
    fn complete_records_are_emitted_without_waiting_for_eof() {
        let mut stream = StreamRenderer::new(Vec::new(), Mode::Human, false);
        stream
            .write_all(b"{\"name\":\"brace } and \\\"quote\\\"\",\"values\":[1,")
            .unwrap();
        assert!(stream.out.is_empty());
        stream.write_all(b"2]}\n").unwrap();
        let text = std::str::from_utf8(&stream.out).unwrap();
        assert!(text.contains("name: brace } and \"quote\""), "{text}");
        stream.write_all(b"  {\"next\":3}").unwrap();
        assert!(std::str::from_utf8(&stream.out)
            .unwrap()
            .contains("next: 3"));
    }

    #[test]
    fn location_array_emits_updates_before_array_closes() {
        let mut stream = StreamRenderer::new(Vec::new(), Mode::Human, false);
        stream.unwrap_array = true;
        stream.write_all(b"[\n {\"latitude\":1},\n").unwrap();
        assert!(std::str::from_utf8(&stream.out)
            .unwrap()
            .contains("latitude: 1"));
        stream.write_all(b"{\"latitude\":2}\n]").unwrap();
        let output = std::str::from_utf8(&stream.out).unwrap();
        assert!(output.contains("latitude: 2"));
        assert!(!output.contains('['));
    }

    #[test]
    fn empty_location_array_is_readable() {
        let mut stream = StreamRenderer::new(Vec::new(), Mode::Human, false);
        stream.unwrap_array = true;
        stream.write_all(b"[ ").unwrap();
        assert!(stream.out.is_empty());
        stream.write_all(b"]").unwrap();
        stream.finish().unwrap();
        assert_eq!(stream.out, b"No results\n");
    }

    #[test]
    fn json_stream_wraps_text_and_rejects_binary() {
        let mut stream = StreamRenderer::new(Vec::new(), Mode::Json, false);
        stream.write_all(b"message\n").unwrap();
        assert!(std::str::from_utf8(&stream.out)
            .unwrap()
            .contains("\"output\": \"message\""));
        stream.write_all(b"bad\0data\n").unwrap();
        assert!(stream.invalid_text);
        assert!(!stream.out.contains(&0));
    }
}

fn run_stream(imp: &str, args: &[OsString], json: bool) -> i32 {
    let mode = if json { Mode::Json } else { Mode::Human };
    let mut output = StreamRenderer::new(io::stdout(), mode, ctx::use_color());
    output.unwrap_array = imp == "location" && !json;
    let status = api::run_to(imp, args, &mut output, !json);
    if let Err(e) = output.finish() {
        err!("cannot write output: {e}");
        if status == 0 {
            return 1;
        }
    }
    if output.api_failed && status == 0 {
        return 1;
    }
    if output.invalid_text {
        err!("refusing --json: the output is binary or not valid UTF-8");
        if status == 0 {
            return 3;
        }
    }
    status
}

/// Run a command with its standard output captured, then print it rendered.
fn run_captured(class: &str, imp: &str, args: &[OsString], json: bool, acknowledge: bool) -> i32 {
    let dir = temp_dir();
    let mut tmp = match make_temp(&dir) {
        Ok(t) => t,
        Err(_) => {
            err!("cannot create a temporary file in {}", dir.display());
            return 1;
        }
    };
    let stdout = match tmp.file.try_clone() {
        Ok(f) => f,
        Err(e) => {
            err!("cannot use the temporary file {}: {e}", tmp.path.display());
            return 1;
        }
    };
    catch_signals();
    let mut stdout = stdout;
    let status = api::run_to(imp, args, &mut stdout, !json);
    if let Some(code) = signal_status() {
        return code;
    }
    let mut data = Vec::new();
    let read = tmp
        .file
        .seek(SeekFrom::Start(0))
        .and_then(|_| tmp.file.read_to_end(&mut data));
    if let Err(e) = read {
        err!("cannot read the output of tpp-api {imp}: {e}");
        return if status != 0 { status } else { 1 };
    }
    if status != 0 && data.is_empty() {
        return status;
    }
    if status == 0 && data.is_empty() && acknowledge {
        if json {
            return render_output("auto", br#"{"status":"request sent"}"#, true);
        }
        let message = if imp == "toast" {
            "Toast requested"
        } else {
            "Request sent"
        };
        if ctx::use_color() {
            write_out(format!("\x1b[32m{message}\x1b[0m\n").as_bytes());
        } else {
            write_out(format!("{message}\n").as_bytes());
        }
        return 0;
    }
    if status == 0 && data.is_empty() && class == "auto" && !json {
        if ctx::use_color() {
            write_out(b"\x1b[2mNo output\x1b[0m\n");
        } else {
            write_out(b"No output\n");
        }
        return 0;
    }
    if class == "auto" {
        if let Some(message) = api_error(&data) {
            if json {
                let _ = render_output(class, &data, true);
            } else {
                report_api_error(&message);
            }
            return if status == 0 { 1 } else { status };
        }
    }
    let rc = render_output(class, &data, json);
    if let Some(code) = signal_status() {
        return code;
    }
    if status != 0 {
        status
    } else {
        rc
    }
}

// -------------------------------------------------------------- commands

fn public_help(entry: &Entry, text: &str) -> String {
    let mut result = String::new();
    for part in text.split_inclusive(|c: char| !c.is_ascii_alphanumeric() && c != '-') {
        let name = part.trim_end_matches(|c: char| !c.is_ascii_alphanumeric() && c != '-');
        let replacement = long_options(&entry.imp)
            .iter()
            .find_map(|(short, long)| (*short == name).then_some(*long))
            .unwrap_or(name);
        result.push_str(replacement);
        result.push_str(&part[name.len()..]);
    }
    result
        .replace("tpp saf ls", "tpp saf list")
        .replace("tpp saf rm", "tpp saf remove")
        .replace(
            "tpp notification channel.",
            "tpp notification create-channel.",
        )
}

fn normalize_args(entry: &Entry, args: Vec<OsString>) -> Result<(Vec<OsString>, bool), String> {
    let reserved: &[(&str, &str)] = match (entry.group.as_str(), entry.action.as_str()) {
        ("dialog", "show") => &[("-l", "list")],
        ("audio", "record") => &[("-i", "recording"), ("-q", "stop")],
        ("job-scheduler", "schedule") => &[
            ("-p", "list"),
            ("--pending", "list"),
            ("--cancel", "cancel"),
            ("--cancel-all", "cancel-all"),
        ],
        ("notification", "create-channel") => &[("-d", "delete-channel")],
        ("nfc", "write") => &[("-r", "read")],
        ("sms", "list") => &[
            ("-c", "conversations"),
            ("--conversation-list", "conversations"),
        ],
        ("location", _) => &[("-r", "watch"), ("--request", "watch")],
        _ => &[],
    };
    let options = crate::commands::OPTIONS
        .iter()
        .find(|row| row.group == entry.group && row.action == entry.action)
        .map_or("", |row| row.words);
    let mut json = false;
    let mut normalized = Vec::with_capacity(args.len());
    let mut args = args.into_iter();
    while let Some(arg) = args.next() {
        if is(&arg, "--") {
            normalized.push(arg);
            normalized.extend(args);
            break;
        }
        if is(&arg, "--json") {
            json = true;
            continue;
        }
        let bytes = arg.as_bytes();
        let equals = bytes.iter().position(|&byte| byte == b'=');
        let name = equals.map_or(bytes, |at| &bytes[..at]);
        if let Some((_, action)) = reserved.iter().find(|(flag, _)| flag.as_bytes() == name) {
            return Err(format!("use 'tpp {} {action}'", entry.group));
        }
        let alias = long_options(&entry.imp)
            .iter()
            .find(|(_, long)| long.as_bytes() == name);
        let backend = alias.map_or(name, |(short, _)| short.as_bytes());
        let takes_value = options.split_whitespace().any(|word| {
            word.split_once(':')
                .is_some_and(|(option, _)| option.as_bytes() == backend)
        });
        if alias.is_some() {
            normalized.push(OsString::from_vec(backend.to_vec()));
        } else {
            normalized.push(arg.clone());
        }
        if let Some(at) = equals {
            if alias.is_some() {
                if !takes_value {
                    return Err(format!(
                        "option '{}' does not take a value",
                        String::from_utf8_lossy(name)
                    ));
                }
                normalized.push(OsString::from_vec(bytes[at + 1..].to_vec()));
            }
        } else if takes_value {
            normalized.push(args.next().ok_or_else(|| {
                format!(
                    "option '{}' requires a value",
                    String::from_utf8_lossy(name)
                )
            })?);
        }
    }
    Ok((normalized, json))
}

fn write_location_help(action: &str) -> i32 {
    let description = match action {
        "get" => "Get a current location fix.",
        "last" => "Show the last known location.",
        _ => "Stream location updates until interrupted.",
    };
    write_help(&format!(
        "Usage: tpp location {action} [--provider PROVIDER] [--json]\n{description}\n\nOptions:\n  --provider PROVIDER  gps, network, or passive (default: gps).\n"
    ));
    0
}

fn run_entry(e: &Entry, args: Vec<OsString>, json: bool) -> i32 {
    let mut fixed: Vec<OsString> = if e.args == "-" {
        Vec::new()
    } else {
        e.args.split_whitespace().map(OsString::from).collect()
    };
    if args
        .first()
        .is_some_and(|arg| is(arg, "--help") || is(arg, "-h") || is(arg, "help"))
    {
        let text = match (e.group.as_str(), e.action.as_str()) {
            ("toast", "-") => "Usage: tpp toast [OPTIONS] TEXT...\n       COMMAND | tpp toast [OPTIONS]\n\nShow a short popup with text from arguments or piped standard input.\nPiped input has a three-second read limit.\n\nOptions:\n  -b COLOR     Set the background color.\n  -c COLOR     Set the text color.\n  -g POSITION  Place the popup at top, middle, or bottom.\n  -s           Use the shorter display duration.\n\nExamples:\n  tpp toast \"Build finished\"\n  printf '%s\\n' \"Build finished\" | tpp toast\n",
            ("usb", "list") => "Usage: tpp usb list\nList USB devices.\n",
            ("usb", "permission") => "Usage: tpp usb permission DEVICE | VENDOR_ID PRODUCT_ID\nCheck USB device permission.\n",
            ("usb", "request") => "Usage: tpp usb request DEVICE | VENDOR_ID PRODUCT_ID\nRequest USB device permission.\n",
            ("usb", "open") => "Usage: tpp usb open COMMAND DEVICE | COMMAND VENDOR_ID PRODUCT_ID\nOpen a device and run COMMAND with its file descriptor.\n  -E  Export the descriptor in TERMUX_USB_FD.\n",
            ("sensor", "list") => "Usage: tpp sensor list\nList available sensors.\n",
            ("sensor", "cleanup") => "Usage: tpp sensor cleanup\nStop sensor listeners.\n",
            ("sensor", "read") => "Usage: tpp sensor read [-a | -s SENSORS] [-d MS] [-n COUNT]\nRead sensor values.\n  -a  Read all sensors.\n  -s SENSORS  Read selected sensors.\n  -d MS  Set the delay.\n  -n COUNT  Limit readings.\n",
            ("dialog", "show") => "Usage: tpp dialog show [WIDGET] [OPTIONS]\nOpen a dialog and wait for the answer. Defaults to the text widget.\nRun tpp dialog list for supported widgets.\n\nOptions:\n  -t TITLE       Set the dialog title.\n  -i HINT        Set the input hint.\n  -m             Allow multiple lines.\n  -p             Hide password input.\n  -n             Accept numeric input.\n  -d FORMAT      Format a date result.\n  -v VALUES      Set comma-separated choices.\n  -r MIN,MAX,START  Set the counter range.\n",
            ("dialog", "list") => "Usage: tpp dialog list\nList supported widgets and their options.\n",
            ("audio", "record") => "Usage: tpp audio record [OPTIONS]\nStart recording audio.\n\nOptions:\n  -f FILE      Save the recording to FILE.\n  -l SECONDS   Limit the recording duration. Use 0 for unlimited.\n  -e ENCODER   Set the encoder (aac, amr_wb, amr_nb, opus).\n  -b KBPS      Set the bitrate.\n  -r HZ        Set the sample rate.\n  -c COUNT     Set the number of channels.\n",
            ("audio", "recording") => "Usage: tpp audio recording\nShow information about the current recording.\n",
            ("audio", "stop") => "Usage: tpp audio stop\nStop the current recording.\n",
            ("job-scheduler", "list") => "Usage: tpp job-scheduler list\nList pending jobs.\n",
            ("job-scheduler", "cancel") => "Usage: tpp job-scheduler cancel ID\nCancel a pending job by ID.\n",
            ("job-scheduler", "cancel-all") => "Usage: tpp job-scheduler cancel-all\nCancel all pending jobs.\n",
            ("notification", "create-channel") => "Usage: tpp notification create-channel ID NAME\nCreate or rename a notification channel. Use its ID with tpp notification send --channel.\n",
            ("notification", "delete-channel") => "Usage: tpp notification delete-channel ID\nDelete a notification channel.\n",
            ("location", action) => {
                return write_location_help(action);
            }
            ("media", "info") => "Usage: tpp media info\nShow current media playback information.\n",
            ("media", "play") => "Usage: tpp media play [FILE]\nPlay a file or resume playback.\n",
            ("media", "pause") => "Usage: tpp media pause\nPause playback.\n",
            ("media", "stop") => "Usage: tpp media stop\nStop playback.\n",
            ("nfc", "read") => "Usage: tpp nfc read [short | full]\nRead an NFC tag. Defaults to short output.\n",
            ("nfc", "write") => "Usage: tpp nfc write -t TEXT\nWrite text to an NFC tag.\n",
            ("volume", "list") => "Usage: tpp volume list\nShow volume levels for all audio streams.\n",
            ("volume", "set") => "Usage: tpp volume set STREAM LEVEL\nSet the volume for alarm, music, notification, ring, system or call.\n",
            _ => tpp_api::usage(&e.imp).unwrap_or("Help unavailable.\n"),
        };
        let text = if e.action == "schedule" && e.imp == "job-scheduler" {
            let options = text
                .split_once("Options for scheduling:")
                .map_or("", |(_, options)| options);
            format!("Usage: tpp job-scheduler schedule --script PATH [OPTIONS]\nSchedule a script to run on Android.\n\nOptions:{options}")
        } else {
            text.to_owned()
        };
        let text = if e.imp == "sms-list" {
            let mut lines = text.lines().peekable();
            let mut help = String::new();
            while let Some(line) = lines.next() {
                if line.contains("--conversation-list") {
                    while lines
                        .peek()
                        .is_some_and(|line| !line.trim_start().starts_with('['))
                    {
                        lines.next();
                    }
                    continue;
                }
                if e.action == "list" && line.trim_start().starts_with("[ --conversation-") {
                    while lines
                        .peek()
                        .is_some_and(|line| !line.trim_start().starts_with('['))
                    {
                        lines.next();
                    }
                    continue;
                }
                help.push_str(line);
                help.push('\n');
            }
            help.replace("tpp sms list", &format!("tpp sms {}", e.action))
        } else {
            text
        };
        write_help(&public_help(e, &text));
        return 0;
    }
    let (mut args, json_flag) = match normalize_args(e, args) {
        Ok(parsed) => parsed,
        Err(message) => {
            err!("{message}");
            return 2;
        }
    };
    let json = json || json_flag;
    let expected = match (e.group.as_str(), e.action.as_str()) {
        ("dialog" | "job-scheduler" | "volume", "list")
        | ("audio", "recording" | "stop")
        | ("job-scheduler", "cancel-all") => Some(0),
        ("job-scheduler", "cancel") | ("notification", "delete-channel") => Some(1),
        ("notification", "create-channel") | ("volume", "set") => Some(2),
        _ => None,
    };
    if expected.is_some_and(|count| args.len() != count) {
        err!(
            "invalid arguments for 'tpp {}'. Try 'tpp help {}'.",
            e.name(),
            e.name()
        );
        return 2;
    }
    if e.group == "sensor" && e.action == "read" && args.iter().any(|a| is(a, "-l") || is(a, "-c"))
    {
        err!("sensor: use 'tpp sensor list' or 'tpp sensor cleanup'");
        return 2;
    }
    if e.group == "usb"
        && matches!(e.action.as_str(), "permission" | "request")
        && args.first().is_some_and(|a| lossy(a).starts_with('-'))
    {
        err!("usb: use 'tpp usb list', 'permission', 'request', or 'open'");
        return 2;
    }
    let typed: Vec<String> = args.iter().map(lossy).collect();
    let class = e.effective_class(&typed);

    // --json is refused for binary or streamed output, but never for help
    if class == "raw" && json {
        let mut name = e.name();
        if e.class != "raw" {
            name.push_str(" with these arguments");
        }
        err!("--json is not supported by 'tpp {name}': it prints binary or streamed data");
        return 2;
    }
    if e.group == "usb" && e.action == "open" && args.first().is_some_and(|a| is(a, "-E")) {
        fixed.insert(0, args.remove(0));
    }
    if e.group == "nfc" && e.action == "read" {
        match args.as_slice() {
            [] => args.push(OsString::from("short")),
            [mode] if is(mode, "short") || is(mode, "full") => {}
            _ => {
                err!("usage: tpp nfc read [short | full]");
                return 2;
            }
        }
    }
    if e.group == "nfc"
        && e.action == "write"
        && !matches!(args.as_slice(), [option, _] if is(option, "-t"))
    {
        err!("usage: tpp nfc write --text TEXT");
        return 2;
    }
    fixed.extend(args);
    if (e.imp == "notification" && fixed.first().is_some_and(|arg| is(arg, "--help-actions")))
        || (e.group == "dialog" && e.action == "list" && !json)
    {
        let mut output = Vec::new();
        let status = api::run_to(&e.imp, &fixed, &mut output, false);
        write_help(&public_help(e, &String::from_utf8_lossy(&output)));
        return status;
    }
    if class == "stream" || (e.imp == "location" && !json) {
        return run_stream(&e.imp, &fixed, json);
    }
    if e.imp == "speech-to-text" && !json {
        return api::run_to(&e.imp, &fixed, &mut io::stdout(), true);
    }
    if class == "raw" || (class == "text" && !json) {
        return api::run(&e.imp, &fixed);
    }
    let acknowledge = matches!(
        e.imp.as_str(),
        "vibrate"
            | "toast"
            | "torch"
            | "notification"
            | "notification-remove"
            | "brightness"
            | "clipboard-set"
            | "wifi-enable"
            | "telephony-call"
            | "media-scan"
            | "sms-send"
            | "infrared-transmit"
            | "tts-speak"
            | "download"
            | "share"
            | "wallpaper"
            | "notification-channel"
            | "dialog"
            | "saf-write"
            | "saf-rm"
            | "api-start"
            | "api-stop"
    ) || matches!(
        (e.group.as_str(), e.action.as_str()),
        ("audio", "record" | "stop")
            | ("job-scheduler", "schedule" | "cancel" | "cancel-all")
            | ("volume", "set")
    ) || (e.imp == "media-player"
        && matches!(e.action.as_str(), "play" | "pause" | "stop"));
    run_captured(&class, &e.imp, &fixed, json, acknowledge)
}

/// Run a plugin. The plugin owns its arguments, --json included, so they are
/// passed on exactly as given. A leading "tpp --json" is added at the end, in
/// front of a "--" if there is one.
fn run_plugin(plugin: &Path, lead_json: bool, mut args: Vec<OsString>) -> i32 {
    if args
        .first()
        .is_some_and(|arg| is(arg, "--help") || is(arg, "-h"))
    {
        return plugin_help(plugin, args);
    }
    if lead_json {
        let at = args.iter().position(|a| is(a, "--")).unwrap_or(args.len());
        args.insert(at, OsString::from("--json"));
    }
    exec(plugin, &args)
}

/// Run a native group as the program, `tpp-session ARGS` for `tpp session ARGS`.
pub fn run_native(group: &str, args: Vec<OsString>) -> i32 {
    native::run(group, &args, false)
}

pub fn run(mut args: Vec<OsString>) -> i32 {
    let ctx = Ctx::from_env();
    let app = App {
        ctx,
        table: Table::builtin(),
    };

    if args.first().is_some_and(|a| is(a, "--complete")) {
        return complete::run(&app, &args[1..]);
    }

    if args.len() == 1 && is(&args[0], "--command-names") {
        write_out(command_names(&app).as_bytes());
        return 0;
    }

    let mut lead_json = false;
    let mut i = 0;
    while i < args.len() {
        let arg = lossy(&args[i]);
        match arg.as_str() {
            "-h" | "--help" => {
                write_help(&usage(&app));
                return 0;
            }
            "--json" => {
                lead_json = true;
                i += 1;
            }
            "--" => {
                i += 1;
                break;
            }
            s if s.starts_with('-') => {
                err!("unknown option '{s}'");
                write_usage_error(&usage(&app));
                return 2;
            }
            _ => break,
        }
    }
    let mut rest = args.split_off(i);
    if rest.is_empty() {
        write_usage_error(&usage(&app));
        return 2;
    }
    let word = lossy(&rest.remove(0));
    let names = group_names(&app);
    let Ok(group) = resolve_group(&names, &word) else {
        return 2;
    };

    if group == "help" {
        if rest
            .first()
            .is_some_and(|arg| is(arg, "--help") || is(arg, "-h"))
        {
            return show_help(&app, &[OsString::from("help")]);
        }
        return show_help(&app, &rest);
    }

    if native::is_native(group) {
        return native::run(group, &rest, lead_json);
    }

    if app.table.is_group(group) {
        let mut argv = rest;
        let before_action = argv.iter().take_while(|arg| is(arg, "--json")).count();
        argv.drain(..before_action);
        let json = lead_json || before_action > 0;
        if let Some(e) = app.table.find(group, "-") {
            return run_entry(e, argv, json);
        }
        if group == "dialog" && argv.is_empty() {
            return run_entry(app.table.find("dialog", "show").unwrap(), argv, json);
        }
        if argv.is_empty() {
            if let Some(e) = app
                .table
                .find(&group, "list")
                .or_else(|| app.table.find(group, "get"))
            {
                return run_entry(e, argv, json);
            }
            write_usage_error(&group_usage(&app, group));
            return 2;
        }
        let first = lossy(&argv[0]);
        if matches!(first.as_str(), "-h" | "--help" | "help") {
            write_help(&group_usage(&app, group));
            return 0;
        }
        if let Some(e) = resolve_action(&app, group, &first) {
            argv.remove(0);
            return run_entry(e, argv, json);
        }
        write_usage_error(&group_usage(&app, group));
        return 2;
    }

    if let Some(plugin) = app.ctx.find_plugin(group) {
        return run_plugin(&plugin, lead_json, rest);
    }

    err!("unknown command '{group}'. Try 'tpp help'.");
    2
}
