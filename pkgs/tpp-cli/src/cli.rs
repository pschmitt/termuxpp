//! The dispatcher: help, the command and plugin lookup, running the API
//! commands and plugins and printing their output.

use std::collections::hash_map::RandomState;
use std::env;
use std::ffi::OsString;
use std::fs::{self, File, OpenOptions};
use std::hash::BuildHasher;
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::os::raw::c_int;
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicI32, Ordering};

use crate::api;
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
         \x20 help    Show help for a command\n\
         \x20 list    List every command with its description\n",
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

/// `tpp list --names`: only the words that select each command, one per line,
/// for scripts (the build checks every command's help with it).
fn list_names(app: &App) -> String {
    let mut s = String::new();
    for e in &app.table.entries {
        s.push_str(&e.name());
        s.push('\n');
    }
    for (name, _) in native::GROUPS {
        s.push_str(name);
        s.push('\n');
    }
    for name in app.ctx.list_plugins(&app.table) {
        s.push_str(&name);
        s.push('\n');
    }
    s
}

fn list_commands(app: &App) -> String {
    let mut s = String::new();
    for e in &app.table.entries {
        s.push_str(&format!("{:<28} {}\n", format!("tpp {}", e.name()), e.desc));
    }
    for (name, summary) in native::GROUPS {
        s.push_str(&format!("{:<28} {}\n", format!("tpp {name}"), summary));
    }
    for name in app.ctx.list_plugins(&app.table) {
        s.push_str(&format!("{:<28} Plugin\n", format!("tpp {name}")));
    }
    s
}

fn show_help(app: &App, args: &[OsString]) -> i32 {
    let Some(group) = args.first().map(lossy) else {
        write_out(usage(app).as_bytes());
        return 0;
    };
    let action = args.get(1).map(lossy);
    if native::is_native(&group) {
        return native::run(&group, &[OsString::from("--help")], false);
    }
    if app.table.is_group(&group) {
        if let Some(e) = app.table.find(&group, "-") {
            return run_entry(e, vec![OsString::from("-h")], false);
        }
        if let Some(action) = action {
            if let Some(e) = app.table.find(&group, &action) {
                return run_entry(e, vec![OsString::from("-h")], false);
            }
            err!("{group}: unknown command '{action}'");
            write_err(&group_usage(app, &group));
            return 2;
        }
        write_out(group_usage(app, &group).as_bytes());
        return 0;
    }
    if let Some(plugin) = app.ctx.find_plugin(&group) {
        return exec(&plugin, &[OsString::from("--help")]);
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
    let status = api::run_to(imp, args, &mut output);
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
    let status = api::run_to(imp, args, &mut stdout);
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
        } else if ctx::use_color() {
            write_out(b"\x1b[32mRequest sent\x1b[0m\n");
        } else {
            write_out(b"Request sent\n");
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

fn run_entry(e: &Entry, mut args: Vec<OsString>, json: bool) -> i32 {
    let mut fixed: Vec<OsString> = if e.args == "-" {
        Vec::new()
    } else {
        e.args.split_whitespace().map(OsString::from).collect()
    };
    let mut help = false;
    if args.first().is_some_and(|a| is(a, "--help") || is(a, "-h")) {
        // Help never takes the fixed arguments of the table entry
        fixed.clear();
        help = true;
        args[0] = OsString::from("-h");
    }
    let typed: Vec<String> = args.iter().map(lossy).collect();
    let class = e.effective_class(&typed);

    // --json is refused for binary or streamed output, but never for help
    if class == "raw" && json && !help {
        let mut name = e.name();
        if e.class != "raw" {
            name.push_str(" with these arguments");
        }
        err!("--json is not supported by 'tpp {name}': it prints binary or streamed data");
        return 2;
    }
    fixed.extend(args);
    if (class == "stream" || (e.imp == "location" && !json)) && !help {
        return run_stream(&e.imp, &fixed, json);
    }
    if class == "raw" || (class == "text" && !json) {
        return api::run(&e.imp, &fixed);
    }
    let acknowledge = !help
        && matches!(
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
        )
        || (!help
            && e.imp == "media-player"
            && typed
                .first()
                .is_some_and(|arg| matches!(arg.as_str(), "play" | "pause" | "stop")));
    run_captured(&class, &e.imp, &fixed, json, acknowledge)
}

/// Run a plugin. The plugin owns its arguments, --json included, so they are
/// passed on exactly as given. A leading "tpp --json" is added at the end, in
/// front of a "--" if there is one.
fn run_plugin(plugin: &Path, lead_json: bool, mut args: Vec<OsString>) -> i32 {
    if lead_json {
        let at = args.iter().position(|a| is(a, "--")).unwrap_or(args.len());
        args.insert(at, OsString::from("--json"));
    }
    exec(plugin, &args)
}

/// Remove --json (up to a "--") from the arguments.
fn take_json(args: Vec<OsString>) -> (Vec<OsString>, bool) {
    let mut found = false;
    let mut stop = false;
    let mut out = Vec::with_capacity(args.len());
    for arg in args {
        if !stop && is(&arg, "--json") {
            found = true;
            continue;
        }
        if is(&arg, "--") {
            stop = true;
        }
        out.push(arg);
    }
    (out, found)
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

    let mut lead_json = false;
    let mut i = 0;
    while i < args.len() {
        let arg = lossy(&args[i]);
        match arg.as_str() {
            "-h" | "--help" => {
                write_out(usage(&app).as_bytes());
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
                write_err(&usage(&app));
                return 2;
            }
            _ => break,
        }
    }
    let mut rest = args.split_off(i);
    if rest.is_empty() {
        write_err(&usage(&app));
        return 2;
    }
    let group = lossy(&rest.remove(0));

    match group.as_str() {
        "help" => return show_help(&app, &rest),
        "list" => {
            if rest.first().is_some_and(|a| is(a, "--names")) {
                write_out(list_names(&app).as_bytes());
            } else {
                write_out(list_commands(&app).as_bytes());
            }
            return 0;
        }
        _ => {}
    }

    if native::is_native(&group) {
        return native::run(&group, &rest, lead_json);
    }

    if app.table.is_group(&group) {
        let (mut argv, found) = take_json(rest);
        let json = lead_json || found;
        if let Some(e) = app.table.find(&group, "-") {
            return run_entry(e, argv, json);
        }
        if argv.is_empty() {
            if let Some(e) = app
                .table
                .find(&group, "list")
                .or_else(|| app.table.find(&group, "get"))
            {
                return run_entry(e, argv, json);
            }
            write_err(&group_usage(&app, &group));
            return 2;
        }
        let first = lossy(&argv[0]);
        if matches!(first.as_str(), "-h" | "--help" | "help") {
            write_out(group_usage(&app, &group).as_bytes());
            return 0;
        }
        if let Some(e) = app.table.find(&group, &first) {
            argv.remove(0);
            return run_entry(e, argv, json);
        }
        err!("{group}: unknown command '{first}'");
        write_err(&group_usage(&app, &group));
        return 2;
    }

    if let Some(plugin) = app.ctx.find_plugin(&group) {
        return run_plugin(&plugin, lead_json, rest);
    }

    err!("unknown command '{group}'. Try 'tpp help'.");
    2
}
