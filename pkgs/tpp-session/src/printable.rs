//! Python's `str.isprintable`, `str.isspace` and decimal digits for single characters.
//!
//! The standard library has no Unicode category tables, so the tables in [`crate::unicode`] were
//! generated from Python itself. That keeps `tpp-session` identical to the Python implementation
//! it replaced, including for code points that Unicode has assigned recently.

use crate::unicode::{DECIMAL_ZEROS, NON_PRINTABLE, SPACES};

fn in_ranges(ranges: &[(u32, u32)], code: u32) -> bool {
    ranges
        .binary_search_by(|&(first, last)| {
            if code < first {
                std::cmp::Ordering::Greater
            } else if code > last {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Equal
            }
        })
        .is_ok()
}

/// True when Python's `char.isprintable()` would be true.
pub fn is_printable(c: char) -> bool {
    !in_ranges(NON_PRINTABLE, c as u32)
}

/// True when Python's `char.isspace()` would be true.
pub fn is_space(c: char) -> bool {
    in_ranges(SPACES, c as u32)
}

/// The value of a Unicode decimal digit (`str.isdecimal`), which Python's `int()` accepts.
pub fn decimal_value(c: char) -> Option<u32> {
    let code = c as u32;
    DECIMAL_ZEROS
        .iter()
        .find(|&&zero| code >= zero && code < zero + 10)
        .map(|zero| code - zero)
}

#[cfg(test)]
mod tests {
    use super::{decimal_value, is_printable, is_space};

    #[test]
    fn ordinary_text_is_printable() {
        for c in "abc XYZ 019 ~!é漢😀ü".chars() {
            assert!(is_printable(c), "{c:?}");
        }
    }

    #[test]
    fn controls_separators_and_format_characters_are_not() {
        for c in [
            '\0',
            '\t',
            '\n',
            '\r',
            '\u{1b}',
            '\u{7f}',
            '\u{85}',
            '\u{a0}',
            '\u{ad}',
            '\u{200b}',
            '\u{200e}',
            '\u{2028}',
            '\u{2029}',
            '\u{202e}',
            '\u{3000}',
            '\u{feff}',
            '\u{e000}',
            '\u{fdd0}',
            '\u{fffe}',
            '\u{ffff}',
            '\u{1fffe}',
            '\u{10ffff}',
        ] {
            assert!(!is_printable(c), "{c:?}");
        }
    }

    #[test]
    fn unassigned_code_points_are_not_printable() {
        assert!(!is_printable('\u{378}'));
        assert!(!is_printable('\u{e0fff}'));
    }

    #[test]
    fn spaces_and_digits() {
        for c in [' ', '\t', '\n', '\u{1c}', '\u{85}', '\u{a0}', '\u{3000}'] {
            assert!(is_space(c), "{c:?}");
        }
        assert!(!is_space('x'));
        assert!(!is_space('\u{200b}'));
        assert_eq!(decimal_value('7'), Some(7));
        assert_eq!(decimal_value('\u{661}'), Some(1));
        assert_eq!(decimal_value('\u{1d7ff}'), Some(9));
        assert_eq!(decimal_value('x'), None);
        assert_eq!(decimal_value('\u{b2}'), None);
    }
}
