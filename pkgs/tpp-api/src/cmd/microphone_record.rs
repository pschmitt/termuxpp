//! `microphone-record`: `tpp audio record [-h] [-d] [-f file] [-l s] [-e enc]
//! [-b kbps] [-r hz] [-c n] [-i] [-q]`.
//!
//! Deviations:
//! - The script evaluates `-l`, `-b`, `-r` and `-c` with the shell's `$((...))`
//!   (so `-l 1+1` and `-r 0x1f` work). [`eval_arith`] follows bash for the
//!   operators, literals (decimal, octal, hex, `base#digits`), precedence,
//!   short-circuiting and 64-bit wrapping, with three differences: identifiers
//!   are always 0 (bash looks up shell variables), assignments and `++`/`--`
//!   are syntax errors, and the text of the error on standard error names this
//!   command (bash names the script and line). As in the script, an
//!   arithmetic error ends the option loop and the command goes on with what it
//!   has (the option already set `-a record`).
//! - Unknown options and options without their value print a message of this
//!   command on standard error (bash's `getopts` names the shell and script),
//!   then the usage error as the script does.
//! - `-f FILE`: `dirname`/`basename` are emulated for GNU coreutils; a value
//!   that starts with `-` (which they would take for an option) is not
//!   special. A failing `realpath` prints `realpath: PATH: reason` (the
//!   OS error text) and ends the command with status 1, as in the script.
use crate::getopts::{Getopts, Opt};
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/microphone-record.txt");
const NAME: &str = "tpp audio record";

/// `usage_error`: an empty line, the usage, status 1.
fn usage_error(io: &mut Io) -> i32 {
    io.out("\n");
    io.out(USAGE);
    1
}

/// `sole`: `-X` must be the only argument (the shell compares `"$1 $2"` with
/// `"-X $OPTARG"`, where OPTARG of a flag is empty).
fn sole(io: &mut Io, args: &[String], option: char) -> bool {
    let flag = format!("-{option}");
    let ok = match args {
        [a] => *a == flag,
        [a, b] => *a == flag && b.is_empty(),
        _ => false,
    };
    if !ok {
        io.outln(&format!(
            "ERROR: No other options should be specified with -{option}!"
        ));
    }
    ok
}

/// GNU `dirname`.
fn dirname(path: &str) -> String {
    if path.is_empty() {
        return ".".into();
    }
    let trimmed = path.trim_end_matches('/');
    if trimmed.is_empty() {
        return "/".into();
    }
    match trimmed.rfind('/') {
        None => ".".into(),
        Some(i) => {
            let d = trimmed[..i].trim_end_matches('/');
            if d.is_empty() {
                "/".into()
            } else {
                d.into()
            }
        }
    }
}

/// GNU `basename`.
fn basename(path: &str) -> String {
    if path.is_empty() {
        return String::new();
    }
    let trimmed = path.trim_end_matches('/');
    if trimmed.is_empty() {
        return "/".into();
    }
    match trimmed.rfind('/') {
        None => trimmed.into(),
        Some(i) => trimmed[i + 1..].into(),
    }
}

/// What `$(...)` leaves of a command's output.
fn substitution(s: String) -> String {
    s.trim_end_matches('\n').to_string()
}

fn strip_os_error(msg: &str) -> String {
    match msg.rfind(" (os error ") {
        Some(i) if msg.ends_with(')') => msg[..i].to_string(),
        _ => msg.to_string(),
    }
}

pub fn run(args: &[String], io: &mut Io) -> i32 {
    let mut record = false;
    let mut params: Vec<String> = Vec::new();

    let mut g = Getopts::new(args, "h,f:,l:,e:,b:,r:,c:,d,i,q");
    while let Some(o) = g.next_opt() {
        match o {
            Opt::Flag('h') => {
                if !sole(io, args, 'h') {
                    return usage_error(io);
                }
            }
            Opt::Arg('f', v) => {
                record = true;
                let dir = substitution(dirname(&v));
                let file = substitution(basename(&v));
                let dir = match io.realpath(&dir) {
                    Ok(d) => d,
                    Err(e) => {
                        io.errln(&format!("realpath: {}", strip_os_error(&e.to_string())));
                        return 1;
                    }
                };
                params.extend([
                    "--es".to_string(),
                    "file".to_string(),
                    format!("{dir}/{file}"),
                ]);
            }
            Opt::Arg('l', v) => {
                record = true;
                match scaled(io, &v, 1000) {
                    Some(n) => params.extend(["--ei".into(), "limit".into(), n]),
                    None => break,
                }
            }
            Opt::Flag('d') => record = true,
            Opt::Arg('e', v) => {
                record = true;
                params.extend(["--es".to_string(), "encoder".to_string(), v]);
            }
            Opt::Arg('b', v) => {
                record = true;
                match scaled(io, &v, 1000) {
                    Some(n) => params.extend(["--ei".into(), "bitrate".into(), n]),
                    None => break,
                }
            }
            Opt::Arg('r', v) => {
                record = true;
                match scaled(io, &v, 1) {
                    Some(n) => params.extend(["--ei".into(), "srate".into(), n]),
                    None => break,
                }
            }
            Opt::Arg('c', v) => {
                record = true;
                match scaled(io, &v, 1) {
                    Some(n) => params.extend(["--ei".into(), "channels".into(), n]),
                    None => break,
                }
            }
            Opt::Flag('i') => {
                if !sole(io, args, 'i') {
                    return usage_error(io);
                }
                params.extend(["-a".to_string(), "info".to_string()]);
            }
            Opt::Flag('q') => {
                if !sole(io, args, 'q') {
                    return usage_error(io);
                }
                params.extend(["-a".to_string(), "quit".to_string()]);
            }
            Opt::Illegal(c) => {
                io.errln(&format!("{NAME}: illegal option -- {c}"));
                return usage_error(io);
            }
            Opt::MissingArg(c) => {
                io.errln(&format!("{NAME}: option requires an argument -- {c}"));
                return usage_error(io);
            }
            // `,` is a valid option of the script's option string, handled by `*)`
            Opt::Flag(_) | Opt::Arg(..) => return usage_error(io),
        }
    }

    if record {
        params.extend(["-a".to_string(), "record".to_string()]);
    }
    if params.is_empty() {
        io.out(USAGE);
        return 0;
    }
    io.run_call(Call::new("MicRecorder").args(params))
}

/// `$((OPTARG * factor))` as text; None (after the error message) when the
/// expression is invalid.
fn scaled(io: &mut Io, expr: &str, factor: i64) -> Option<String> {
    match eval_arith(expr) {
        Ok(n) => Some(n.wrapping_mul(factor).to_string()),
        Err(msg) => {
            io.errln(&format!("{NAME}: {expr}: {msg}"));
            None
        }
    }
}

/// The value of a shell arithmetic expression (see the module documentation
/// for what is different from bash).
pub fn eval_arith(expr: &str) -> Result<i64, String> {
    let mut p = Parser {
        s: expr.as_bytes(),
        i: 0,
        noeval: 0,
    };
    p.skip_ws();
    if p.i >= p.s.len() {
        return Ok(0);
    }
    let v = p.comma()?;
    p.skip_ws();
    if p.i < p.s.len() {
        return Err("arithmetic syntax error in expression".into());
    }
    Ok(v)
}

struct Parser<'a> {
    s: &'a [u8],
    i: usize,
    /// > 0 while the operand being parsed is not evaluated (`0 && 1/0`)
    noeval: u32,
}

type Res = Result<i64, String>;

fn syntax() -> String {
    "arithmetic syntax error: operand expected".into()
}

impl Parser<'_> {
    fn skip_ws(&mut self) {
        while self.i < self.s.len() && matches!(self.s[self.i], b' ' | b'\t' | b'\n') {
            self.i += 1;
        }
    }

    /// Consume `op` when it is next (and not the start of a longer operator
    /// listed in `not_followed_by`).
    fn eat(&mut self, op: &str, not_followed_by: &[u8]) -> bool {
        self.skip_ws();
        let rest = &self.s[self.i..];
        if rest.starts_with(op.as_bytes()) {
            let next = rest.get(op.len());
            if next.is_some_and(|n| not_followed_by.contains(n)) {
                return false;
            }
            self.i += op.len();
            true
        } else {
            false
        }
    }

    fn comma(&mut self) -> Res {
        let mut v = self.cond()?;
        while self.eat(",", &[]) {
            v = self.cond()?;
        }
        Ok(v)
    }

    fn cond(&mut self) -> Res {
        let c = self.lor()?;
        if !self.eat("?", &[]) {
            return Ok(c);
        }
        if c == 0 {
            self.noeval += 1;
        }
        let a = self.comma();
        if c == 0 {
            self.noeval -= 1;
        }
        let a = a?;
        if !self.eat(":", &[]) {
            return Err("`:' expected for conditional expression".into());
        }
        if c != 0 {
            self.noeval += 1;
        }
        let b = self.cond();
        if c != 0 {
            self.noeval -= 1;
        }
        let b = b?;
        Ok(if c != 0 { a } else { b })
    }

    fn lor(&mut self) -> Res {
        let mut v = self.land()?;
        while self.eat("||", &[]) {
            if v != 0 {
                self.noeval += 1;
            }
            let r = self.land();
            if v != 0 {
                self.noeval -= 1;
            }
            v = i64::from(v != 0 || r? != 0);
        }
        Ok(v)
    }

    fn land(&mut self) -> Res {
        let mut v = self.bor()?;
        while self.eat("&&", &[]) {
            if v == 0 {
                self.noeval += 1;
            }
            let r = self.bor();
            if v == 0 {
                self.noeval -= 1;
            }
            v = i64::from(v != 0 && r? != 0);
        }
        Ok(v)
    }

    fn bor(&mut self) -> Res {
        let mut v = self.bxor()?;
        while self.eat("|", b"|") {
            v |= self.bxor()?;
        }
        Ok(v)
    }

    fn bxor(&mut self) -> Res {
        let mut v = self.band()?;
        while self.eat("^", &[]) {
            v ^= self.band()?;
        }
        Ok(v)
    }

    fn band(&mut self) -> Res {
        let mut v = self.equality()?;
        while self.eat("&", b"&") {
            v &= self.equality()?;
        }
        Ok(v)
    }

    fn equality(&mut self) -> Res {
        let mut v = self.relational()?;
        loop {
            if self.eat("==", &[]) {
                v = i64::from(v == self.relational()?);
            } else if self.eat("!=", &[]) {
                v = i64::from(v != self.relational()?);
            } else {
                return Ok(v);
            }
        }
    }

    fn relational(&mut self) -> Res {
        let mut v = self.shift()?;
        loop {
            if self.eat("<=", &[]) {
                v = i64::from(v <= self.shift()?);
            } else if self.eat(">=", &[]) {
                v = i64::from(v >= self.shift()?);
            } else if self.eat("<", b"<") {
                v = i64::from(v < self.shift()?);
            } else if self.eat(">", b">") {
                v = i64::from(v > self.shift()?);
            } else {
                return Ok(v);
            }
        }
    }

    fn shift(&mut self) -> Res {
        let mut v = self.additive()?;
        loop {
            if self.eat("<<", &[]) {
                let n = self.additive()?;
                v = v.wrapping_shl(n as u32);
            } else if self.eat(">>", &[]) {
                let n = self.additive()?;
                v = v.wrapping_shr(n as u32);
            } else {
                return Ok(v);
            }
        }
    }

    fn additive(&mut self) -> Res {
        let mut v = self.multiplicative()?;
        loop {
            if self.eat("+", &[]) {
                v = v.wrapping_add(self.multiplicative()?);
            } else if self.eat("-", &[]) {
                v = v.wrapping_sub(self.multiplicative()?);
            } else {
                return Ok(v);
            }
        }
    }

    fn multiplicative(&mut self) -> Res {
        let mut v = self.power()?;
        loop {
            if self.eat("*", b"*") {
                v = v.wrapping_mul(self.power()?);
            } else if self.eat("/", &[]) {
                let d = self.power()?;
                if d == 0 {
                    if self.noeval == 0 {
                        return Err("division by 0".into());
                    }
                } else if d == -1 {
                    v = v.wrapping_neg();
                } else {
                    v /= d;
                }
            } else if self.eat("%", &[]) {
                let d = self.power()?;
                if d == 0 {
                    if self.noeval == 0 {
                        return Err("division by 0".into());
                    }
                } else if d == -1 {
                    v = 0;
                } else {
                    v %= d;
                }
            } else {
                return Ok(v);
            }
        }
    }

    /// `**` binds tighter than the binary operators and is right associative;
    /// its operands are unary expressions.
    fn power(&mut self) -> Res {
        let base = self.unary()?;
        if self.eat("**", &[]) {
            let exp = self.power()?;
            if exp < 0 {
                if self.noeval == 0 {
                    return Err("exponent less than 0".into());
                }
                return Ok(0);
            }
            return Ok(pow(base, exp));
        }
        Ok(base)
    }

    fn unary(&mut self) -> Res {
        self.skip_ws();
        match self.s.get(self.i) {
            Some(b'+') => {
                self.i += 1;
                self.unary()
            }
            Some(b'-') => {
                self.i += 1;
                Ok(self.unary()?.wrapping_neg())
            }
            Some(b'!') if self.s.get(self.i + 1) != Some(&b'=') => {
                self.i += 1;
                Ok(i64::from(self.unary()? == 0))
            }
            Some(b'~') => {
                self.i += 1;
                Ok(!self.unary()?)
            }
            _ => self.primary(),
        }
    }

    fn primary(&mut self) -> Res {
        self.skip_ws();
        match self.s.get(self.i) {
            Some(b'(') => {
                self.i += 1;
                let v = self.comma()?;
                if !self.eat(")", &[]) {
                    return Err("missing `)'".into());
                }
                Ok(v)
            }
            Some(c) if c.is_ascii_digit() => self.number(),
            Some(c) if c.is_ascii_alphabetic() || *c == b'_' => {
                while self
                    .s
                    .get(self.i)
                    .is_some_and(|c| c.is_ascii_alphanumeric() || *c == b'_')
                {
                    self.i += 1;
                }
                // an unset variable
                Ok(0)
            }
            _ => Err(syntax()),
        }
    }

    fn number(&mut self) -> Res {
        let start = self.i;
        while self
            .s
            .get(self.i)
            .is_some_and(|c| c.is_ascii_alphanumeric() || matches!(c, b'#' | b'@' | b'_'))
        {
            self.i += 1;
        }
        let token = &self.s[start..self.i];
        let too_great = || "value too great for base".to_string();
        if let Some(hash) = token.iter().position(|c| *c == b'#') {
            let base = &token[..hash];
            if !base.iter().all(u8::is_ascii_digit) {
                return Err(too_great());
            }
            let base: i64 = std::str::from_utf8(base)
                .ok()
                .and_then(|b| b.parse().ok())
                .unwrap_or(i64::MAX);
            if !(2..=64).contains(&base) {
                return Err("invalid arithmetic base".into());
            }
            let digits = &token[hash + 1..];
            return accumulate(digits, base, |c| base_digit(c, base)).ok_or_else(too_great);
        }
        if token.len() > 1 && token[0] == b'0' && matches!(token[1], b'x' | b'X') {
            return accumulate(&token[2..], 16, |c| base_digit(c, 16)).ok_or_else(too_great);
        }
        if token[0] == b'0' {
            return accumulate(token, 8, |c| base_digit(c, 8)).ok_or_else(too_great);
        }
        accumulate(token, 10, |c| base_digit(c, 10)).ok_or_else(too_great)
    }
}

/// `value` of the digits in `base` (wrapping like bash's 64-bit arithmetic);
/// None when a character is not a digit of that base.
fn accumulate(digits: &[u8], base: i64, digit: impl Fn(u8) -> Option<i64>) -> Option<i64> {
    let mut v = 0i64;
    for c in digits {
        let d = digit(*c)?;
        if d >= base {
            return None;
        }
        v = v.wrapping_mul(base).wrapping_add(d);
    }
    Some(v)
}

/// The value of a digit character (`0-9a-zA-Z@_`); for bases up to 36 the
/// case of a letter does not matter, above it capitals are 36..=61.
fn base_digit(c: u8, base: i64) -> Option<i64> {
    match c {
        b'0'..=b'9' => Some(i64::from(c - b'0')),
        b'a'..=b'z' => Some(i64::from(c - b'a') + 10),
        b'A'..=b'Z' if base <= 36 => Some(i64::from(c - b'A') + 10),
        b'A'..=b'Z' => Some(i64::from(c - b'A') + 36),
        b'@' => Some(62),
        b'_' => Some(63),
        _ => None,
    }
}

/// `base ** exp` in wrapping 64-bit arithmetic.
fn pow(mut base: i64, mut exp: i64) -> i64 {
    let mut result = 1i64;
    while exp > 0 {
        if exp & 1 == 1 {
            result = result.wrapping_mul(base);
        }
        base = base.wrapping_mul(base);
        exp >>= 1;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Results of the shell's `$((OPTARG))` (bash 5), None for an error.
    const TABLE: &[(&str, Option<i64>)] = &[
        ("", Some(0)),
        (" ", Some(0)),
        ("7*3", Some(21)),
        ("010", Some(8)),
        ("0x1f", Some(31)),
        ("0x", Some(0)),
        ("2#101", Some(5)),
        ("64#_", Some(63)),
        ("08", None),
        ("09", None),
        ("abc", Some(0)),
        ("1+2", Some(3)),
        ("1 2", None),
        ("-5", Some(-5)),
        ("1.5", None),
        ("9223372036854775807", Some(i64::MAX)),
        ("9223372036854775808", Some(i64::MIN)),
        ("1/0", None),
        ("5%0", None),
        ("2**63", Some(i64::MIN)),
        ("2**-1", None),
        ("1<<64", Some(1)),
        ("1<<63", Some(i64::MIN)),
        ("3>2", Some(1)),
        ("1?2:3", Some(2)),
        ("(1)", Some(1)),
        ("(1", None),
        ("!0", Some(1)),
        ("~0", Some(-1)),
        ("1,2", Some(2)),
        ("1#0", None),
        ("65#1", None),
        ("-0x10", Some(-16)),
        ("+5", Some(5)),
        ("--5", Some(5)),
        ("1 + + 2", Some(3)),
        ("  7  ", Some(7)),
        ("1e3", None),
        ("0b11", None),
        ("@", None),
        ("4 > 3 ? 10 : 20", Some(10)),
        ("2+3*4", Some(14)),
        ("-2**2", Some(4)),
        ("2**3**2", Some(512)),
        ("1<2==1", Some(1)),
        ("6&3|8^1", Some(11)),
        ("7-3-2", Some(2)),
        ("100/7/2", Some(7)),
        ("-7/2", Some(-3)),
        ("-7%3", Some(-1)),
        ("7%-3", Some(1)),
        ("1||1/0", Some(1)),
        ("0&&1/0", Some(0)),
        ("1?2:1/0", Some(2)),
        ("0?1/0:5", Some(5)),
        ("3>2>1", Some(0)),
        ("1+2,3*4", Some(12)),
        ("(1+2)*(3+4)", Some(21)),
        ("0x1F+0Xa", Some(41)),
        ("0777", Some(511)),
        ("16#ff", Some(255)),
        ("36#zz", Some(1295)),
        ("37#Z", None),
        ("64#Zz", Some(3939)),
        ("8#9", None),
        ("1 ? 2 ? 3 : 4 : 5", Some(3)),
        ("5 > 3 && 2 < 1", Some(0)),
        ("!!5", Some(1)),
        ("~5", Some(-6)),
        ("- - 5", Some(5)),
        ("-(2+3)", Some(-5)),
        ("1<<2+1", Some(8)),
        ("256>>4>>1", Some(8)),
        ("-16>>2", Some(-4)),
        ("0xffffffffffffffff", Some(-1)),
        ("1 -", None),
        ("*3", None),
        ("3 3", None),
        ("()", None),
        ("2 ** 0", Some(1)),
        ("0**0", Some(1)),
        ("10 % 4 * 3", Some(6)),
        ("1 == 1 != 0", Some(1)),
        ("3 & 5 ^ 6", Some(7)),
        ("1 ?: 2", None),
        ("0x1g", None),
        ("a b", None),
        ("a+1", Some(1)),
        ("_x1", Some(0)),
        ("1#", None),
    ];

    #[test]
    fn arithmetic_matches_bash() {
        for (expr, expected) in TABLE {
            assert_eq!(eval_arith(expr).ok(), *expected, "expression {expr:?}");
        }
    }

    #[test]
    fn dirname_and_basename_follow_gnu() {
        let table = [
            ("", ".", ""),
            ("/", "/", "/"),
            ("//", "/", "/"),
            ("///", "/", "/"),
            ("a", ".", "a"),
            ("a/", ".", "a"),
            ("a//", ".", "a"),
            ("/a", "/", "a"),
            ("//a", "/", "a"),
            ("a/b", "a", "b"),
            ("a//b", "a", "b"),
            ("a/b/", "a", "b"),
            ("/a/b//", "/a", "b"),
            ("a/./b", "a/.", "b"),
            ("./a", ".", "a"),
            ("..", ".", ".."),
        ];
        for (path, dir, base) in table {
            assert_eq!(dirname(path), dir, "dirname {path:?}");
            assert_eq!(basename(path), base, "basename {path:?}");
        }
    }
}
