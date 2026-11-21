//! Output renderer: JSON documents as human text or pretty JSON, plain text
//! wrapped as {"output": TEXT}.
//!
//! The semantics:
//!
//! * `Mode::Human` prints JSON documents as indented YAML-like text.
//! * `Mode::Json` pretty-prints JSON documents; several documents become an
//!   array.
//! * `Mode::Wrap` wraps plain text as `{"output": "..."}`.
//!
//! Nothing is returned unless the whole input was handled, so the caller can
//! fall back to the unmodified input. Errors: `Invalid` (the input is not valid
//! JSON, exit status 3 in the awk script) and `Utf8` (the input is not valid
//! UTF-8, status 4; only checked for the modes json and wrap).
//!
//! Differences from the awk script, all of them strictness fixes: a string
//! token must be terminated and its escapes are validated left to right, so
//! input such as `["abc]` or `"\u\n0000"` is refused instead of being printed
//! as it came.

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    Human,
    Json,
    Wrap,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum RenderError {
    /// The input is not valid JSON.
    Invalid,
    /// The input is not valid UTF-8 text.
    Utf8,
}

const KEY: &str = "1;34";
const STRING: &str = "0;32";
const NULL: &str = "1;30";
const OTHER: &str = "0;39";

/// Render `input` in `mode`, with ANSI colors when `color` is set.
pub fn render(mode: Mode, color: bool, input: &[u8]) -> Result<Vec<u8>, RenderError> {
    let lines = split_lines(input);
    if mode == Mode::Wrap {
        return wrap(color, &lines);
    }
    let mut r = Renderer::new(mode, color);
    for line in lines {
        if mode != Mode::Human && std::str::from_utf8(line).is_err() {
            return Err(RenderError::Utf8);
        }
        if !r.tokenize(line) {
            return Err(RenderError::Invalid);
        }
    }
    r.finish()
}

/// The records of awk: split at newlines, no empty record after a final one.
fn split_lines(input: &[u8]) -> Vec<&[u8]> {
    let mut lines: Vec<&[u8]> = input.split(|b| *b == b'\n').collect();
    if input.is_empty() || input.ends_with(b"\n") {
        lines.pop();
    }
    lines
}

fn paint(color: bool, s: &[u8], code: &str) -> Vec<u8> {
    if !color {
        return s.to_vec();
    }
    let mut v = Vec::with_capacity(s.len() + 12);
    v.extend_from_slice(b"\x1b[");
    v.extend_from_slice(code.as_bytes());
    v.push(b'm');
    v.extend_from_slice(s);
    v.extend_from_slice(b"\x1b[0m");
    v
}

fn spaces(n: usize) -> Vec<u8> {
    vec![b' '; n]
}

fn esc_text(s: &[u8]) -> Vec<u8> {
    let mut r = Vec::with_capacity(s.len());
    for &b in s {
        match b {
            b'"' => r.extend_from_slice(b"\\\""),
            b'\\' => r.extend_from_slice(b"\\\\"),
            b'\n' => r.extend_from_slice(b"\\n"),
            b'\r' => r.extend_from_slice(b"\\r"),
            b'\t' => r.extend_from_slice(b"\\t"),
            c if c < 32 => r.extend_from_slice(format!("\\u{:04x}", c).as_bytes()),
            c => r.push(c),
        }
    }
    r
}

fn wrap(color: bool, lines: &[&[u8]]) -> Result<Vec<u8>, RenderError> {
    let mut text: Vec<u8> = Vec::new();
    for (i, line) in lines.iter().enumerate() {
        if std::str::from_utf8(line).is_err() {
            return Err(RenderError::Utf8);
        }
        if i > 0 {
            text.extend_from_slice(b"\\n");
        }
        text.extend_from_slice(&esc_text(line));
    }
    let mut quoted = Vec::with_capacity(text.len() + 2);
    quoted.push(b'"');
    quoted.extend_from_slice(&text);
    quoted.push(b'"');
    let mut out = Vec::new();
    out.extend_from_slice(b"{\n  ");
    out.extend_from_slice(&paint(color, b"\"output\"", KEY));
    out.extend_from_slice(b": ");
    out.extend_from_slice(&paint(color, &quoted, STRING));
    out.extend_from_slice(b"\n}\n");
    Ok(out)
}

fn hexval(h: &[u8]) -> i64 {
    let mut v: i64 = 0;
    for &b in h {
        match (b as char).to_digit(16) {
            Some(d) => v = v * 16 + d as i64,
            None => return -1,
        }
    }
    v
}

fn head(s: &[u8], n: usize) -> &[u8] {
    &s[..n.min(s.len())]
}

fn tail(s: &[u8], n: usize) -> &[u8] {
    &s[n.min(s.len())..]
}

/// Decode the body of a JSON string token (with its quotes).
fn unquote(tok: &[u8]) -> Vec<u8> {
    let mut s = &tok[1..tok.len() - 1];
    let mut r = Vec::with_capacity(s.len());
    while let Some(p) = s.iter().position(|&b| b == b'\\') {
        r.extend_from_slice(&s[..p]);
        let e = s.get(p + 1).copied();
        s = tail(s, p + 2);
        match e {
            None => {}
            Some(b'n') => r.push(b'\n'),
            Some(b't') => r.push(b'\t'),
            Some(b'r') => r.push(b'\r'),
            Some(b'b') => r.push(8),
            Some(b'f') => r.push(12),
            Some(b'u') => {
                let mut cp = hexval(head(s, 4));
                s = tail(s, 4);
                if (0xd800..=0xdbff).contains(&cp) && s.starts_with(b"\\u") {
                    let lo = hexval(head(&s[2..], 4));
                    if (0xdc00..=0xdfff).contains(&lo) {
                        cp = 0x10000 + (cp - 0xd800) * 1024 + (lo - 0xdc00);
                        s = tail(s, 6);
                    }
                }
                match u32::try_from(cp).ok().and_then(char::from_u32) {
                    Some(ch) if cp > 0 => {
                        let mut buf = [0u8; 4];
                        r.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
                    }
                    _ => r.push(b'?'),
                }
            }
            Some(other) => r.push(other),
        }
    }
    r.extend_from_slice(s);
    r
}

/// A JSON string token: no raw control characters, only valid escapes.
fn string_ok(t: &[u8]) -> bool {
    let body = &t[1..t.len() - 1];
    let mut i = 0;
    while i < body.len() {
        let c = body[i];
        if c < 32 {
            return false;
        }
        if c == b'\\' {
            match body.get(i + 1) {
                Some(b'"' | b'\\' | b'/' | b'b' | b'f' | b'n' | b'r' | b't') => i += 2,
                Some(b'u') => {
                    let hex = body.get(i + 2..i + 6);
                    if !hex.is_some_and(|h| h.iter().all(u8::is_ascii_hexdigit)) {
                        return false;
                    }
                    i += 6;
                }
                _ => return false,
            }
        } else {
            i += 1;
        }
    }
    true
}

/// `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?`
fn is_number(t: &[u8]) -> bool {
    let mut i = 0;
    let digits = |i: &mut usize| -> usize {
        let start = *i;
        while t.get(*i).is_some_and(u8::is_ascii_digit) {
            *i += 1;
        }
        *i - start
    };
    if t.get(i) == Some(&b'-') {
        i += 1;
    }
    match t.get(i) {
        Some(b'0') => i += 1,
        Some(b'1'..=b'9') => {
            digits(&mut i);
        }
        _ => return false,
    }
    if t.get(i) == Some(&b'.') {
        i += 1;
        if digits(&mut i) == 0 {
            return false;
        }
    }
    if matches!(t.get(i), Some(b'e' | b'E')) {
        i += 1;
        if matches!(t.get(i), Some(b'+' | b'-')) {
            i += 1;
        }
        if digits(&mut i) == 0 {
            return false;
        }
    }
    i == t.len()
}

fn is_scalar(t: &[u8]) -> bool {
    t[0] == b'"' || t == b"true" || t == b"false" || t == b"null" || is_number(t)
}

/// One open container.
struct Frame {
    /// b'o' for an object, b'a' for an array
    kind: u8,
    /// 0 start, 1 after a key, 2 after the colon, 3 after a value, 4 after a comma
    st: u8,
    /// json: indentation and painted key of the member being written
    keyline: Vec<u8>,
    /// human: painted key of the member being written
    hkey: Vec<u8>,
    /// human: indentation of the members
    base: usize,
    /// human: text in front of the first line of the members ("- " of a list item)
    lead1: Vec<u8>,
}

impl Frame {
    fn new(kind: u8) -> Self {
        Frame {
            kind,
            st: 0,
            keyline: Vec::new(),
            hkey: Vec::new(),
            base: 0,
            lead1: Vec::new(),
        }
    }
}

struct Renderer {
    mode: Mode,
    color: bool,
    out: Vec<Vec<u8>>,
    /// start and end (exclusive) line of each document in `out`
    docs: Vec<(usize, usize)>,
    /// element 0 stands for the top level
    stack: Vec<Frame>,
    /// container opener waiting to see whether the container is empty
    pend: Option<u8>,
    pendpre: Vec<u8>,
}

impl Renderer {
    fn new(mode: Mode, color: bool) -> Self {
        Renderer {
            mode,
            color,
            out: Vec::new(),
            docs: Vec::new(),
            stack: vec![Frame::new(0)],
            pend: None,
            pendpre: Vec::new(),
        }
    }

    fn depth(&self) -> usize {
        self.stack.len() - 1
    }

    fn paint(&self, s: &[u8], code: &str) -> Vec<u8> {
        paint(self.color, s, code)
    }

    fn emit(&mut self, s: Vec<u8>) {
        self.out.push(s);
    }

    fn scalar_paint(&self, t: &[u8]) -> Vec<u8> {
        if t[0] == b'"' {
            self.paint(t, STRING)
        } else if t == b"null" {
            self.paint(t, NULL)
        } else {
            self.paint(t, OTHER)
        }
    }

    // ---------------------------------------------------------- json mode

    fn value_prefix_json(&self, p: usize) -> Vec<u8> {
        if p == 0 {
            Vec::new()
        } else if self.stack[p].kind == b'a' {
            spaces(p * 2)
        } else {
            self.stack[p].keyline.clone()
        }
    }

    fn flush_pend_json(&mut self) {
        if let Some(c) = self.pend.take() {
            let mut line = std::mem::take(&mut self.pendpre);
            line.push(c);
            self.emit(line);
        }
    }

    // --------------------------------------------------------- human mode

    fn lead(&mut self, p: usize) -> Vec<u8> {
        let f = &mut self.stack[p];
        if !f.lead1.is_empty() {
            std::mem::take(&mut f.lead1)
        } else {
            spaces(f.base)
        }
    }

    fn flush_pend_human(&mut self) {
        let Some(open) = self.pend.take() else {
            return;
        };
        let c = self.depth();
        let p = c - 1;
        if p == 0 {
            self.stack[c].base = 0;
            self.stack[c].lead1.clear();
        } else if self.stack[p].kind == b'o' {
            let mut line = self.lead(p);
            line.extend_from_slice(&self.stack[p].hkey);
            line.push(b':');
            self.emit(line);
            self.stack[c].base = self.stack[p].base + 2;
            self.stack[c].lead1.clear();
        } else if open == b'{' {
            let mut l = self.lead(p);
            l.extend_from_slice(b"- ");
            self.stack[c].lead1 = l;
            self.stack[c].base = self.stack[p].base + 2;
        } else {
            let mut line = self.lead(p);
            line.push(b'-');
            self.emit(line);
            self.stack[c].base = self.stack[p].base + 2;
            self.stack[c].lead1.clear();
        }
    }

    /// `text` is the already decoded and painted value.
    fn human_value(&mut self, p: usize, text: &[u8]) {
        if p == 0 {
            self.emit(text.to_vec());
        } else if self.stack[p].kind == b'o' {
            let mut line = self.lead(p);
            line.extend_from_slice(&self.stack[p].hkey);
            line.extend_from_slice(b": ");
            line.extend_from_slice(text);
            self.emit(line);
        } else {
            let mut line = self.lead(p);
            line.extend_from_slice(b"- ");
            line.extend_from_slice(text);
            self.emit(line);
        }
    }

    fn human_scalar(&mut self, p: usize, t: &[u8]) {
        if t[0] != b'"' {
            let text = self.scalar_paint(t);
            self.human_value(p, &text);
            return;
        }
        let s = unquote(t);
        if !s.contains(&b'\n') {
            let text = self.paint(&s, STRING);
            self.human_value(p, &text);
            return;
        }
        let b = if p == 0 { 0 } else { self.stack[p].base + 2 };
        self.human_value(p, b"|");
        for part in s.split(|&c| c == b'\n') {
            let mut line = spaces(b);
            line.extend_from_slice(&self.paint(part, STRING));
            self.emit(line);
        }
    }

    // --------------------------------------------------------- dispatchers

    /// Called with the container the value belongs to (0 for top level)
    /// before the container stack is changed.
    fn r_open(&mut self, c: u8, p: usize) {
        if self.mode == Mode::Json {
            self.flush_pend_json();
            self.pend = Some(c);
            self.pendpre = self.value_prefix_json(p);
        } else {
            self.flush_pend_human();
            self.pend = Some(c);
        }
    }

    /// Called after the container was popped.
    fn r_close(&mut self, c: u8) {
        let p = self.depth();
        if self.mode == Mode::Json {
            if let Some(open) = self.pend.take() {
                let mut line = std::mem::take(&mut self.pendpre);
                line.push(open);
                line.push(c);
                self.emit(line);
            } else {
                let mut line = spaces(p * 2);
                line.push(c);
                self.emit(line);
            }
        } else if self.pend.take().is_some() {
            let empty: &[u8] = if c == b'}' { b"{}" } else { b"[]" };
            if p == 0 {
                self.emit(empty.to_vec());
            } else {
                self.human_value(p, empty);
            }
        }
    }

    fn r_key(&mut self, t: &[u8]) {
        let d = self.depth();
        if self.mode == Mode::Json {
            self.flush_pend_json();
            let mut keyline = spaces(d * 2);
            keyline.extend_from_slice(&self.paint(t, KEY));
            keyline.extend_from_slice(b": ");
            self.stack[d].keyline = keyline;
        } else {
            self.flush_pend_human();
            let key = unquote(t);
            self.stack[d].hkey = self.paint(&key, KEY);
        }
    }

    fn r_scalar(&mut self, t: &[u8]) {
        let d = self.depth();
        if self.mode == Mode::Json {
            self.flush_pend_json();
            let mut line = self.value_prefix_json(d);
            line.extend_from_slice(&self.scalar_paint(t));
            self.emit(line);
        } else {
            self.flush_pend_human();
            self.human_scalar(d, t);
        }
    }

    fn r_comma(&mut self) {
        if self.mode == Mode::Json {
            if let Some(last) = self.out.last_mut() {
                last.push(b',');
            }
        }
    }

    // -------------------------------------------------------------- parser

    fn start_value_open(&mut self, c: u8) {
        let p = self.depth();
        self.r_open(c, p);
        self.stack
            .push(Frame::new(if c == b'{' { b'o' } else { b'a' }));
    }

    /// Feed one token to the state machine; false if the input is not JSON.
    fn feed(&mut self, t: &[u8]) -> bool {
        let c = t[0];
        let check_utf8 = self.mode != Mode::Human;
        if self.depth() == 0 {
            if c != b'{' && c != b'[' {
                return false;
            }
            self.docs.push((self.out.len(), 0));
            self.start_value_open(c);
            return true;
        }
        let d = self.depth();
        let k = self.stack[d].kind;
        let s = self.stack[d].st;
        if c == b'}' || c == b']' {
            if (c == b'}' && k != b'o') || (c == b']' && k != b'a') {
                return false;
            }
            if s != 0 && s != 3 {
                return false;
            }
            self.stack.pop();
            self.r_close(c);
            if self.depth() == 0 {
                let end = self.out.len();
                if let Some(doc) = self.docs.last_mut() {
                    doc.1 = end;
                }
            }
            return true;
        }
        if c == b',' {
            if s != 3 {
                return false;
            }
            self.stack[d].st = 4;
            self.r_comma();
            return true;
        }
        if c == b':' {
            if k != b'o' || s != 1 {
                return false;
            }
            self.stack[d].st = 2;
            return true;
        }
        if k == b'o' {
            if s == 0 || s == 4 {
                if c != b'"' || (check_utf8 && !string_ok(t)) {
                    return false;
                }
                self.stack[d].st = 1;
                self.r_key(t);
                return true;
            }
            if s != 2 {
                return false;
            }
        } else if s != 0 && s != 4 {
            return false;
        }
        self.stack[d].st = 3;
        if c == b'{' || c == b'[' {
            self.start_value_open(c);
            return true;
        }
        if !is_scalar(t) || (check_utf8 && c == b'"' && !string_ok(t)) {
            return false;
        }
        self.r_scalar(t);
        true
    }

    /// Split a line into tokens and feed them; false if the line is not JSON.
    fn tokenize(&mut self, line: &[u8]) -> bool {
        let mut i = 0;
        while i < line.len() {
            let c = line[i];
            if matches!(c, b' ' | b'\t' | b'\r') {
                i += 1;
                continue;
            }
            let start = i;
            match c {
                b'"' => {
                    i += 1;
                    loop {
                        match line.get(i) {
                            None => return false,
                            Some(b'"') => {
                                i += 1;
                                break;
                            }
                            Some(b'\\') => {
                                if i + 1 >= line.len() {
                                    return false;
                                }
                                i += 2;
                            }
                            Some(_) => i += 1,
                        }
                    }
                }
                b'{' | b'}' | b'[' | b']' | b',' | b':' => i += 1,
                b'-' | b'+' | b'.' | b'0'..=b'9' | b'a'..=b'z' | b'A'..=b'Z' => {
                    while line
                        .get(i)
                        .is_some_and(|b| matches!(b, b'-' | b'+' | b'.' | b'0'..=b'9' | b'a'..=b'z' | b'A'..=b'Z'))
                    {
                        i += 1;
                    }
                }
                _ => return false,
            }
            if !self.feed(&line[start..i]) {
                return false;
            }
        }
        true
    }

    fn finish(self) -> Result<Vec<u8>, RenderError> {
        if self.depth() != 0 || self.docs.is_empty() {
            return Err(RenderError::Invalid);
        }
        let mut out = Vec::new();
        let ndocs = self.docs.len();
        if self.mode == Mode::Json && ndocs > 1 {
            out.extend_from_slice(b"[\n");
            for (d, &(start, end)) in self.docs.iter().enumerate() {
                for i in start..end {
                    out.extend_from_slice(b"  ");
                    out.extend_from_slice(&self.out[i]);
                    if i + 1 == end && d + 1 < ndocs {
                        out.push(b',');
                    }
                    out.push(b'\n');
                }
            }
            out.extend_from_slice(b"]\n");
            return Ok(out);
        }
        for (d, &(start, end)) in self.docs.iter().enumerate() {
            if d > 0 && self.mode == Mode::Human {
                out.extend_from_slice(b"---\n");
            }
            for line in &self.out[start..end] {
                out.extend_from_slice(line);
                out.push(b'\n');
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn r(mode: Mode, color: bool, input: &str) -> Result<String, RenderError> {
        render(mode, color, input.as_bytes()).map(|v| String::from_utf8(v).unwrap())
    }

    fn json(input: &str) -> String {
        r(Mode::Json, false, input).unwrap()
    }

    fn human(input: &str) -> String {
        r(Mode::Human, false, input).unwrap()
    }

    #[test]
    fn json_pretty_prints_nested_values() {
        assert_eq!(
            json(r#"{"a":[1,2.5,-3e4,true,false,null,"x"],"b":{"c":{}},"d":[]}"#),
            "{\n  \"a\": [\n    1,\n    2.5,\n    -3e4,\n    true,\n    false,\n    null,\n    \"x\"\n  ],\n  \"b\": {\n    \"c\": {}\n  },\n  \"d\": []\n}\n"
        );
    }

    #[test]
    fn json_keeps_string_escapes_as_written() {
        let out = json(r#"{"a": "\u00e9\ud83d\ude00 \"q\" \\ \/"}"#);
        assert_eq!(
            out,
            "{\n  \"a\": \"\\u00e9\\ud83d\\ude00 \\\"q\\\" \\\\ \\/\"\n}\n"
        );
    }

    #[test]
    fn json_raw_utf8_is_kept() {
        assert_eq!(json("{\"a\": \"é €\"}"), "{\n  \"a\": \"é €\"\n}\n");
    }

    #[test]
    fn json_objects_in_arrays() {
        assert_eq!(
            json(r#"[{"a":1},{"b":[]},[]]"#),
            "[\n  {\n    \"a\": 1\n  },\n  {\n    \"b\": []\n  },\n  []\n]\n"
        );
    }

    #[test]
    fn multiple_documents_become_an_array() {
        assert_eq!(
            json("{\"a\": 1}\n{\"a\": 2}\n"),
            "[\n  {\n    \"a\": 1\n  },\n  {\n    \"a\": 2\n  }\n]\n"
        );
        assert_eq!(json("[1] {}"), "[\n  [\n    1\n  ],\n  {}\n]\n");
    }

    #[test]
    fn documents_may_span_lines() {
        assert_eq!(
            json("{\n  \"a\": [\n 1,\r\n 2 ]\n}\n"),
            "{\n  \"a\": [\n    1,\n    2\n  ]\n}\n"
        );
    }

    #[test]
    fn invalid_input_is_refused() {
        for bad in [
            "{\"a\": }",
            "{\"a\": 1} x",
            "{\"a\": \"\u{1}\"}",
            "{\"a\": \"\\q\"}",
            "[1, 2",
            "{\"a\": 1,}",
            "[1,]",
            "[,1]",
            "{\"a\" 1}",
            "{1: 2}",
            "[1 2]",
            "[01]",
            "[1.]",
            "[truefalse]",
            "[\"abc]",
            "[\"\\u\\n0000\"]",
            "[\"\\u12\"]",
            "[@]",
            "\"x\"",
            "42",
            "",
            "  \n",
            "]",
            "{]",
            "[}",
        ] {
            assert_eq!(
                r(Mode::Json, false, bad),
                Err(RenderError::Invalid),
                "{bad:?}"
            );
        }
    }

    #[test]
    fn invalid_utf8_is_refused_in_json_and_wrap_modes() {
        assert_eq!(
            render(Mode::Json, false, b"{\"a\": \"\xff\"}"),
            Err(RenderError::Utf8)
        );
        assert_eq!(
            render(Mode::Wrap, false, b"a\xffb\n"),
            Err(RenderError::Utf8)
        );
        // The first problem wins: an invalid document stops before the bad bytes
        assert_eq!(
            render(Mode::Json, false, b"{\"a\": }\n\xff"),
            Err(RenderError::Invalid)
        );
        assert_eq!(
            render(Mode::Json, false, b"\xff\n{\"a\": }"),
            Err(RenderError::Utf8)
        );
    }

    #[test]
    fn human_mode_allows_what_json_mode_refuses_in_strings() {
        let tab = r#"{"a": "x	y"}"#;
        assert_eq!(human(tab), "a: x\ty\n");
        assert_eq!(r(Mode::Json, false, tab), Err(RenderError::Invalid));
        assert!(render(Mode::Human, false, b"{\"a\": \"\xff\"}").is_ok());
    }

    #[test]
    fn color_codes() {
        let out = r(Mode::Json, true, r#"{"a": "b", "c": null, "d": 1}"#).unwrap();
        assert_eq!(
            out,
            "{\n  \x1b[1;34m\"a\"\x1b[0m: \x1b[0;32m\"b\"\x1b[0m,\n  \x1b[1;34m\"c\"\x1b[0m: \x1b[1;30mnull\x1b[0m,\n  \x1b[1;34m\"d\"\x1b[0m: \x1b[0;39m1\x1b[0m\n}\n"
        );
    }

    #[test]
    fn human_mode_is_readable() {
        assert_eq!(
            human(r#"{"a": {"b": [1, 2]}, "c": "x", "d": [], "e": {}, "f": null, "g": true}"#),
            "a:\n  b:\n    - 1\n    - 2\nc: x\nd: []\ne: {}\nf: null\ng: true\n"
        );
    }

    #[test]
    fn human_mode_lists_of_objects() {
        assert_eq!(
            human(r#"[{"a": 1, "b": 2}, {"c": [3, [4, 5]]}, [], {}, "s"]"#),
            "- a: 1\n  b: 2\n- c:\n    - 3\n    -\n      - 4\n      - 5\n- []\n- {}\n- s\n"
        );
    }

    #[test]
    fn human_mode_nested_list_item_objects() {
        assert_eq!(human(r#"[[{"a": 1}]]"#), "-\n  - a: 1\n");
        assert_eq!(
            human(r#"{"a": [{"b": {"c": 1}}]}"#),
            "a:\n  - b:\n      c: 1\n"
        );
    }

    #[test]
    fn human_mode_multiline_strings() {
        assert_eq!(
            human(r#"{"a": "l1\nl2", "b": 1}"#),
            "a: |\n  l1\n  l2\nb: 1\n"
        );
        assert_eq!(human(r#"["x\ny"]"#), "- |\n  x\n  y\n");
        assert_eq!(human(r#"{"a": "end\n"}"#), "a: |\n  end\n  \n");
    }

    #[test]
    fn human_mode_decodes_escapes() {
        assert_eq!(
            human(r#"{"k\"ey": "\u00e9\ud83d\ude00\t\"\\\/"}"#),
            "k\"ey: é\u{1f600}\t\"\\/\n"
        );
        assert_eq!(
            human(r#"{"a": "\ud83d x", "b": "\u0000"}"#),
            "a: ? x\nb: ?\n"
        );
    }

    #[test]
    fn human_mode_separates_documents() {
        assert_eq!(human("{\"a\": 1}\n{\"a\": 2}"), "a: 1\n---\na: 2\n");
    }

    #[test]
    fn human_mode_top_level_empty_containers() {
        assert_eq!(human("{}"), "{}\n");
        assert_eq!(human("[]"), "[]\n");
    }

    #[test]
    fn human_mode_colors() {
        assert_eq!(
            r(Mode::Human, true, r#"{"a": "b", "c": [null]}"#).unwrap(),
            "\x1b[1;34ma\x1b[0m: \x1b[0;32mb\x1b[0m\n\x1b[1;34mc\x1b[0m:\n  - \x1b[1;30mnull\x1b[0m\n"
        );
    }

    #[test]
    fn wrap_mode() {
        let out = r(Mode::Wrap, false, "l1 \"q\" \\ \tt\né\n").unwrap();
        assert_eq!(out, "{\n  \"output\": \"l1 \\\"q\\\" \\\\ \\tt\\né\"\n}\n");
        assert_eq!(
            r(Mode::Wrap, false, "a\n\n").unwrap(),
            "{\n  \"output\": \"a\\n\"\n}\n"
        );
        assert_eq!(
            r(Mode::Wrap, false, "no newline").unwrap(),
            "{\n  \"output\": \"no newline\"\n}\n"
        );
        assert_eq!(
            r(Mode::Wrap, false, "\u{1b}[0m\r\n").unwrap(),
            "{\n  \"output\": \"\\u001b[0m\\r\"\n}\n"
        );
        assert_eq!(
            r(Mode::Wrap, false, "").unwrap(),
            "{\n  \"output\": \"\"\n}\n"
        );
    }

    #[test]
    fn wrap_mode_colors() {
        assert_eq!(
            r(Mode::Wrap, true, "x").unwrap(),
            "{\n  \x1b[1;34m\"output\"\x1b[0m: \x1b[0;32m\"x\"\x1b[0m\n}\n"
        );
    }

    #[test]
    fn deep_nesting_does_not_overflow_the_stack() {
        let depth = 8_000;
        let doc = format!("{}{}", "[".repeat(depth), "]".repeat(depth));
        assert!(r(Mode::Json, false, &doc).is_ok());
        assert!(r(Mode::Human, false, &doc).is_ok());
    }
}
