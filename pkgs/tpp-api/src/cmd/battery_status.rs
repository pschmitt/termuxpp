//! `battery-status`: `tpp battery [FIELD]`.
//!
//! Deviations: the script takes no operand. An optional FIELD (for example
//! `plugged` or `percentage`) prints only that value of the status, without
//! quotes, and fails when the status has no such field.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/battery-status.txt");
const NAME: &str = "tpp battery";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let rest = match super::only_help(args, io, NAME, USAGE) {
        Ok(r) => r,
        Err(status) => return status,
    };
    let [field] = rest else {
        if let Some(status) = super::no_operands(io, NAME, rest) {
            return status;
        }
        return io.run_call(Call::new("BatteryStatus"));
    };
    let status = match io.call_text(Call::new("BatteryStatus")) {
        Ok(s) => s,
        Err(e) => {
            io.errln(&format!("tpp: {e}"));
            return 1;
        }
    };
    match field_value(&status, field) {
        Some(value) => {
            io.outln(&value);
            0
        }
        None => {
            io.errln(&format!("{NAME}: no such field: {field}"));
            1
        }
    }
}

/// The value of a top-level member of a JSON object: strings unquoted,
/// anything else as written (`85`, `25.0`, `true`, `null`).
fn field_value(json: &str, name: &str) -> Option<String> {
    let mut chars = json.chars().peekable();
    let skip_ws = |c: &mut std::iter::Peekable<std::str::Chars>| {
        while c.next_if(|ch| ch.is_whitespace()).is_some() {}
    };
    skip_ws(&mut chars);
    chars.next_if_eq(&'{')?;
    loop {
        skip_ws(&mut chars);
        chars.next_if_eq(&',');
        skip_ws(&mut chars);
        let key = string(&mut chars)?;
        skip_ws(&mut chars);
        chars.next_if_eq(&':')?;
        skip_ws(&mut chars);
        let value = if chars.peek() == Some(&'"') {
            string(&mut chars)?
        } else {
            raw(&mut chars)
        };
        if key == name {
            return Some(value);
        }
    }
}

/// A JSON string; the opening quote is the next character.
fn string(chars: &mut std::iter::Peekable<std::str::Chars>) -> Option<String> {
    chars.next_if_eq(&'"')?;
    let mut out = String::new();
    loop {
        match chars.next()? {
            '"' => return Some(out),
            '\\' => match chars.next()? {
                'n' => out.push('\n'),
                't' => out.push('\t'),
                'r' => out.push('\r'),
                'b' => out.push('\u{8}'),
                'f' => out.push('\u{c}'),
                'u' => {
                    let hex: String = chars.by_ref().take(4).collect();
                    let code = u32::from_str_radix(&hex, 16).ok()?;
                    out.push(char::from_u32(code).unwrap_or('\u{fffd}'));
                }
                other => out.push(other),
            },
            c => out.push(c),
        }
    }
}

/// A number, literal, object or array, as written.
fn raw(chars: &mut std::iter::Peekable<std::str::Chars>) -> String {
    let mut out = String::new();
    let mut depth = 0usize;
    while let Some(&c) = chars.peek() {
        match c {
            '{' | '[' => depth += 1,
            '}' | ']' if depth == 0 => break,
            '}' | ']' => depth -= 1,
            ',' if depth == 0 => break,
            '"' => {
                if let Some(s) = string(chars) {
                    out.push('"');
                    out.push_str(&s);
                    out.push('"');
                }
                continue;
            }
            _ => {}
        }
        out.push(c);
        chars.next();
    }
    out.trim().to_string()
}

#[cfg(test)]
mod tests {
    use super::field_value;

    const STATUS: &str = "{\n  \"health\": \"GOOD\",\n  \"percentage\": 85,\n  \"plugged\": \"PLUGGED_AC\",\n  \"status\": \"CHARGING\",\n  \"temperature\": 25.0,\n  \"current\": null\n}\n";

    #[test]
    fn reads_strings_and_scalars() {
        assert_eq!(field_value(STATUS, "plugged").as_deref(), Some("PLUGGED_AC"));
        assert_eq!(field_value(STATUS, "percentage").as_deref(), Some("85"));
        assert_eq!(field_value(STATUS, "temperature").as_deref(), Some("25.0"));
        assert_eq!(field_value(STATUS, "current").as_deref(), Some("null"));
    }

    #[test]
    fn missing_or_invalid() {
        assert_eq!(field_value(STATUS, "nope"), None);
        assert_eq!(field_value("not json", "plugged"), None);
        assert_eq!(field_value("", "plugged"), None);
    }

    #[test]
    fn keys_inside_values_do_not_match() {
        let json = "{\"a\": \"\\\"plugged\\\": 1\", \"plugged\": \"UNPLUGGED\"}";
        assert_eq!(field_value(json, "plugged").as_deref(), Some("UNPLUGGED"));
    }
}
