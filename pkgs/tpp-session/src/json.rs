//! A small JSON model: just enough to talk to the app and to print results the way Python's
//! `json.dumps(value, indent=2, ensure_ascii=False)` does.

use std::fmt::Write;

/// A JSON value. Objects keep their key order, numbers keep their text.
#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Number(String),
    String(String),
    Array(Vec<Value>),
    Object(Vec<(String, Value)>),
}

impl Value {
    pub fn string(text: impl Into<String>) -> Value {
        Value::String(text.into())
    }

    pub fn get(&self, key: &str) -> Option<&Value> {
        match self {
            Value::Object(entries) => entries
                .iter()
                .find(|(name, _)| name == key)
                .map(|(_, value)| value),
            _ => None,
        }
    }

    pub fn as_array(&self) -> Option<&[Value]> {
        match self {
            Value::Array(items) => Some(items),
            _ => None,
        }
    }

    /// Python truthiness, which the session list uses for its flags.
    pub fn truthy(&self) -> bool {
        match self {
            Value::Null => false,
            Value::Bool(flag) => *flag,
            Value::Number(text) => text
                .parse::<f64>()
                .map(|number| number != 0.0)
                .unwrap_or(true),
            Value::String(text) => !text.is_empty(),
            Value::Array(items) => !items.is_empty(),
            Value::Object(entries) => !entries.is_empty(),
        }
    }

    /// Python `str()` of a parsed value, as far as the session list needs it.
    pub fn display(&self) -> String {
        match self {
            Value::Null => "None".to_string(),
            Value::Bool(true) => "True".to_string(),
            Value::Bool(false) => "False".to_string(),
            Value::Number(text) | Value::String(text) => text.clone(),
            other => other.to_compact(),
        }
    }

    /// Compact form with Python's default separators (`", "` and `": "`), for the request line.
    pub fn to_compact(&self) -> String {
        let mut text = String::new();
        self.write_compact(&mut text);
        text
    }

    fn write_compact(&self, text: &mut String) {
        match self {
            Value::Array(items) => {
                text.push('[');
                for (index, item) in items.iter().enumerate() {
                    if index > 0 {
                        text.push_str(", ");
                    }
                    item.write_compact(text);
                }
                text.push(']');
            }
            Value::Object(entries) => {
                text.push('{');
                for (index, (key, value)) in entries.iter().enumerate() {
                    if index > 0 {
                        text.push_str(", ");
                    }
                    write_string(text, key);
                    text.push_str(": ");
                    value.write_compact(text);
                }
                text.push('}');
            }
            scalar => scalar.write_scalar(text),
        }
    }

    /// Pretty form with two-space indentation, like `json.dumps(indent=2, ensure_ascii=False)`.
    pub fn to_pretty(&self) -> String {
        let mut text = String::new();
        self.write_pretty(&mut text, 0);
        text
    }

    fn write_pretty(&self, text: &mut String, depth: usize) {
        match self {
            Value::Array(items) if !items.is_empty() => {
                text.push_str("[\n");
                for (index, item) in items.iter().enumerate() {
                    indent(text, depth + 1);
                    item.write_pretty(text, depth + 1);
                    text.push_str(if index + 1 < items.len() { ",\n" } else { "\n" });
                }
                indent(text, depth);
                text.push(']');
            }
            Value::Object(entries) if !entries.is_empty() => {
                text.push_str("{\n");
                for (index, (key, value)) in entries.iter().enumerate() {
                    indent(text, depth + 1);
                    write_string(text, key);
                    text.push_str(": ");
                    value.write_pretty(text, depth + 1);
                    text.push_str(if index + 1 < entries.len() {
                        ",\n"
                    } else {
                        "\n"
                    });
                }
                indent(text, depth);
                text.push('}');
            }
            Value::Array(_) => text.push_str("[]"),
            Value::Object(_) => text.push_str("{}"),
            scalar => scalar.write_scalar(text),
        }
    }

    fn write_scalar(&self, text: &mut String) {
        match self {
            Value::Null => text.push_str("null"),
            Value::Bool(true) => text.push_str("true"),
            Value::Bool(false) => text.push_str("false"),
            Value::Number(raw) => text.push_str(raw),
            Value::String(value) => write_string(text, value),
            Value::Array(_) | Value::Object(_) => {
                unreachable!("containers are written by the callers")
            }
        }
    }
}

fn indent(text: &mut String, depth: usize) {
    for _ in 0..depth {
        text.push_str("  ");
    }
}

fn write_string(text: &mut String, value: &str) {
    text.push('"');
    for c in value.chars() {
        match c {
            '"' => text.push_str("\\\""),
            '\\' => text.push_str("\\\\"),
            '\n' => text.push_str("\\n"),
            '\r' => text.push_str("\\r"),
            '\t' => text.push_str("\\t"),
            '\u{8}' => text.push_str("\\b"),
            '\u{c}' => text.push_str("\\f"),
            c if (c as u32) < 0x20 => {
                let _ = write!(text, "\\u{:04x}", c as u32);
            }
            c => text.push(c),
        }
    }
    text.push('"');
}

/// Wraps the tokens of pretty-printed JSON in the colors `tpp session` has always used:
/// keys 36, strings 32, `true`/`false`/`null` 35 and numbers 33.
pub fn colorize(json: &str) -> String {
    let bytes = json.as_bytes();
    let mut result = String::with_capacity(json.len() * 2);
    let mut index = 0;
    while index < bytes.len() {
        let byte = bytes[index];
        let (end, code) = match byte {
            b'"' => {
                let mut end = index + 1;
                while end < bytes.len() && bytes[end] != b'"' {
                    if bytes[end] == b'\\' {
                        // Skip the backslash and the whole character after it
                        end += 1;
                        end += json[end.min(json.len())..]
                            .chars()
                            .next()
                            .map_or(0, char::len_utf8);
                    } else {
                        end += 1;
                    }
                }
                end = (end + 1).min(bytes.len());
                let mut after = end;
                while after < bytes.len() && bytes[after].is_ascii_whitespace() {
                    after += 1;
                }
                (
                    end,
                    if bytes.get(after) == Some(&b':') {
                        "36"
                    } else {
                        "32"
                    },
                )
            }
            b't' | b'f' | b'n' => {
                let word = ["true", "false", "null"]
                    .into_iter()
                    .find(|word| json[index..].starts_with(word));
                match word {
                    Some(word) => (index + word.len(), "35"),
                    None => {
                        result.push(byte as char);
                        index += 1;
                        continue;
                    }
                }
            }
            b'-' | b'0'..=b'9' => {
                let mut end = index + 1;
                while end < bytes.len()
                    && matches!(bytes[end], b'0'..=b'9' | b'.' | b'e' | b'E' | b'+' | b'-')
                {
                    end += 1;
                }
                (end, "33")
            }
            _ => {
                // Copy one whole character
                let width = json[index..].chars().next().map_or(1, char::len_utf8);
                result.push_str(&json[index..index + width]);
                index += width;
                continue;
            }
        };
        let end = end.min(bytes.len());
        let _ = write!(result, "\u{1b}[{code}m{}\u{1b}[0m", &json[index..end]);
        index = end;
    }
    result
}

/// Parses one JSON document. Trailing whitespace is allowed, anything else after it is an error.
pub fn parse(text: &str) -> Result<Value, String> {
    let mut parser = Parser {
        bytes: text.as_bytes(),
        text,
        index: 0,
        depth: 0,
    };
    parser.skip_whitespace();
    let value = parser.value()?;
    parser.skip_whitespace();
    if parser.index != parser.bytes.len() {
        return Err(parser.error("Extra data"));
    }
    Ok(value)
}

struct Parser<'a> {
    bytes: &'a [u8],
    text: &'a str,
    index: usize,
    depth: usize,
}

const MAX_DEPTH: usize = 128;

impl Parser<'_> {
    fn skip_whitespace(&mut self) {
        while matches!(
            self.bytes.get(self.index),
            Some(b' ' | b'\t' | b'\n' | b'\r')
        ) {
            self.index += 1;
        }
    }

    /// The message Python's `json.loads` would give: `MSG: line L column C (char N)`.
    fn error(&self, message: &str) -> String {
        let before = &self.text[..self.index.min(self.text.len())];
        let position = before.chars().count();
        let line = before.matches('\n').count() + 1;
        let column = match before.rfind('\n') {
            Some(newline) => before[newline + 1..].chars().count() + 1,
            None => position + 1,
        };
        format!("{message}: line {line} column {column} (char {position})")
    }

    fn value(&mut self) -> Result<Value, String> {
        match self.bytes.get(self.index) {
            None => Err(self.error("Expecting value")),
            Some(b'{') => self.object(),
            Some(b'[') => self.array(),
            Some(b'"') => Ok(Value::String(self.string()?)),
            Some(b't') => self.literal("true", Value::Bool(true)),
            Some(b'f') => self.literal("false", Value::Bool(false)),
            Some(b'n') => self.literal("null", Value::Null),
            Some(b'-' | b'0'..=b'9') => self.number(),
            Some(_) => Err(self.error("Expecting value")),
        }
    }

    fn literal(&mut self, word: &str, value: Value) -> Result<Value, String> {
        if self.text[self.index..].starts_with(word) {
            self.index += word.len();
            Ok(value)
        } else {
            Err(self.error("Expecting value"))
        }
    }

    fn number(&mut self) -> Result<Value, String> {
        let start = self.index;
        if self.bytes.get(self.index) == Some(&b'-') {
            self.index += 1;
        }
        match self.bytes.get(self.index) {
            Some(b'0') => self.index += 1,
            Some(b'1'..=b'9') => self.digits(),
            _ => return Err(self.error("Expecting value")),
        }
        if self.bytes.get(self.index) == Some(&b'.')
            && matches!(self.bytes.get(self.index + 1), Some(b'0'..=b'9'))
        {
            self.index += 1;
            self.digits();
        }
        if matches!(self.bytes.get(self.index), Some(b'e' | b'E')) {
            let mut probe = self.index + 1;
            if matches!(self.bytes.get(probe), Some(b'+' | b'-')) {
                probe += 1;
            }
            if matches!(self.bytes.get(probe), Some(b'0'..=b'9')) {
                self.index = probe;
                self.digits();
            }
        }
        Ok(Value::Number(self.text[start..self.index].to_string()))
    }

    fn digits(&mut self) {
        while matches!(self.bytes.get(self.index), Some(b'0'..=b'9')) {
            self.index += 1;
        }
    }

    fn string(&mut self) -> Result<String, String> {
        self.index += 1;
        let mut value = String::new();
        loop {
            let start = self.index;
            while let Some(&byte) = self.bytes.get(self.index) {
                if byte == b'"' || byte == b'\\' || byte < 0x20 {
                    break;
                }
                self.index += 1;
            }
            value.push_str(&self.text[start..self.index]);
            match self.bytes.get(self.index) {
                None => return Err(self.error("Unterminated string starting at")),
                Some(b'"') => {
                    self.index += 1;
                    return Ok(value);
                }
                Some(b'\\') => {
                    self.index += 1;
                    let escape = *self
                        .bytes
                        .get(self.index)
                        .ok_or_else(|| self.error("Unterminated string starting at"))?;
                    self.index += 1;
                    match escape {
                        b'"' => value.push('"'),
                        b'\\' => value.push('\\'),
                        b'/' => value.push('/'),
                        b'b' => value.push('\u{8}'),
                        b'f' => value.push('\u{c}'),
                        b'n' => value.push('\n'),
                        b'r' => value.push('\r'),
                        b't' => value.push('\t'),
                        b'u' => value.push(self.unicode_escape()?),
                        _ => return Err(self.error("Invalid \\escape")),
                    }
                }
                Some(_) => return Err(self.error("Invalid control character at")),
            }
        }
    }

    fn hex4(&mut self) -> Result<u32, String> {
        let digits = self
            .text
            .get(self.index..self.index + 4)
            .filter(|digits| digits.bytes().all(|byte| byte.is_ascii_hexdigit()))
            .ok_or_else(|| self.error("Invalid \\uXXXX escape"))?;
        let code =
            u32::from_str_radix(digits, 16).map_err(|_| self.error("Invalid \\uXXXX escape"))?;
        self.index += 4;
        Ok(code)
    }

    fn unicode_escape(&mut self) -> Result<char, String> {
        let first = self.hex4()?;
        if (0xD800..0xDC00).contains(&first) && self.text[self.index..].starts_with("\\u") {
            let saved = self.index;
            self.index += 2;
            let second = self.hex4()?;
            if (0xDC00..0xE000).contains(&second) {
                let code = 0x10000 + ((first - 0xD800) << 10) + (second - 0xDC00);
                return Ok(char::from_u32(code).unwrap_or('\u{fffd}'));
            }
            self.index = saved;
        }
        // A lone surrogate cannot be a Rust char
        Ok(char::from_u32(first).unwrap_or('\u{fffd}'))
    }

    fn array(&mut self) -> Result<Value, String> {
        self.enter()?;
        self.index += 1;
        let mut items = Vec::new();
        self.skip_whitespace();
        if self.bytes.get(self.index) == Some(&b']') {
            self.index += 1;
            self.depth -= 1;
            return Ok(Value::Array(items));
        }
        loop {
            self.skip_whitespace();
            items.push(self.value()?);
            self.skip_whitespace();
            match self.bytes.get(self.index) {
                Some(b',') => self.index += 1,
                Some(b']') => {
                    self.index += 1;
                    self.depth -= 1;
                    return Ok(Value::Array(items));
                }
                _ => return Err(self.error("Expecting ',' delimiter")),
            }
        }
    }

    fn object(&mut self) -> Result<Value, String> {
        self.enter()?;
        self.index += 1;
        let mut entries: Vec<(String, Value)> = Vec::new();
        self.skip_whitespace();
        if self.bytes.get(self.index) == Some(&b'}') {
            self.index += 1;
            self.depth -= 1;
            return Ok(Value::Object(entries));
        }
        loop {
            self.skip_whitespace();
            if self.bytes.get(self.index) != Some(&b'"') {
                return Err(self.error("Expecting property name enclosed in double quotes"));
            }
            let key = self.string()?;
            self.skip_whitespace();
            if self.bytes.get(self.index) != Some(&b':') {
                return Err(self.error("Expecting ':' delimiter"));
            }
            self.index += 1;
            self.skip_whitespace();
            let value = self.value()?;
            // Like Python's dict: a repeated key keeps its first position and takes the last value
            match entries.iter_mut().find(|(name, _)| *name == key) {
                Some(entry) => entry.1 = value,
                None => entries.push((key, value)),
            }
            self.skip_whitespace();
            match self.bytes.get(self.index) {
                Some(b',') => self.index += 1,
                Some(b'}') => {
                    self.index += 1;
                    self.depth -= 1;
                    return Ok(Value::Object(entries));
                }
                _ => return Err(self.error("Expecting ',' delimiter")),
            }
        }
    }

    fn enter(&mut self) -> Result<(), String> {
        self.depth += 1;
        if self.depth > MAX_DEPTH {
            return Err(self.error("Nesting too deep"));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pretty_matches_python_layout() {
        let value =
            parse(r#"[{"id":"a","position":1,"nested":{"x":[],"y":{}},"flag":true,"none":null}]"#)
                .unwrap();
        assert_eq!(
            value.to_pretty(),
            "[\n  {\n    \"id\": \"a\",\n    \"position\": 1,\n    \"nested\": {\n      \"x\": [],\n      \"y\": {}\n    },\n    \"flag\": true,\n    \"none\": null\n  }\n]"
        );
        assert_eq!(Value::Array(vec![]).to_pretty(), "[]");
    }

    #[test]
    fn strings_are_escaped_like_ensure_ascii_false() {
        let value = Value::string("a\"b\\c\n\t\r\u{8}\u{c}\u{1f}\u{7f}é\u{2028}😀");
        assert_eq!(
            value.to_pretty(),
            "\"a\\\"b\\\\c\\n\\t\\r\\b\\f\\u001f\u{7f}é\u{2028}😀\""
        );
    }

    #[test]
    fn compact_uses_python_separators() {
        let value = Value::Array(vec![
            Value::string("move"),
            Value::string("abc"),
            Value::Number("2".into()),
        ]);
        assert_eq!(value.to_compact(), "[\"move\", \"abc\", 2]");
    }

    #[test]
    fn parse_round_trips_and_keeps_key_order() {
        let text = r#"{"b": 1, "a": [1.5, -2, 1e5, "x\u00e9\ud83d\ude00\/"], "b": 3}"#;
        let value = parse(text).unwrap();
        assert_eq!(
            value,
            Value::Object(vec![
                ("b".into(), Value::Number("3".into())),
                (
                    "a".into(),
                    Value::Array(vec![
                        Value::Number("1.5".into()),
                        Value::Number("-2".into()),
                        Value::Number("1e5".into()),
                        Value::string("xé😀/"),
                    ])
                ),
            ])
        );
    }

    #[test]
    fn parse_rejects_malformed_documents() {
        for bad in [
            "",
            "{",
            "[1,]",
            "{\"a\" 1}",
            "nul",
            "01",
            "\"x",
            "[1] 2",
            "\"\\q\"",
            "\"a\nb\"",
            "-",
            "{1: 2}",
        ] {
            assert!(parse(bad).is_err(), "{bad:?} should not parse");
        }
        assert!(parse(&"[".repeat(1000)).is_err());
        assert!(parse(" {\"a\": true}\n").is_ok());
    }

    #[test]
    fn colorize_wraps_every_token_kind() {
        let pretty = parse(r#"{"k": "v", "n": -1.5e3, "t": true, "z": null, "s": "tr\"ue: 1"}"#)
            .unwrap()
            .to_pretty();
        let colored = colorize(&pretty);
        assert!(colored.contains("\u{1b}[36m\"k\"\u{1b}[0m: \u{1b}[32m\"v\"\u{1b}[0m"));
        assert!(colored.contains("\u{1b}[33m-1.5e3\u{1b}[0m"));
        assert!(colored.contains("\u{1b}[35mtrue\u{1b}[0m"));
        assert!(colored.contains("\u{1b}[35mnull\u{1b}[0m"));
        assert!(colored.contains("\u{1b}[32m\"tr\\\"ue: 1\"\u{1b}[0m"));
        // Stripping the colors gives the plain text back
        let mut plain = String::new();
        let mut chars = colored.chars();
        while let Some(c) = chars.next() {
            if c == '\u{1b}' {
                for next in chars.by_ref() {
                    if next == 'm' {
                        break;
                    }
                }
            } else {
                plain.push(c);
            }
        }
        assert_eq!(plain, pretty);
    }

    #[test]
    fn truthiness_and_display_follow_python() {
        assert!(
            !Value::Null.truthy() && !Value::Bool(false).truthy() && !Value::string("").truthy()
        );
        assert!(!Value::Number("0".into()).truthy() && Value::Number("2".into()).truthy());
        assert!(Value::string("x").truthy() && !Value::Array(vec![]).truthy());
        assert_eq!(Value::Bool(true).display(), "True");
        assert_eq!(Value::Null.display(), "None");
    }
}
