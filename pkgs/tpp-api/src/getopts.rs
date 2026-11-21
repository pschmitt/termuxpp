//! POSIX `getopts` (in the silent `:optstring` mode) as the shell scripts use it.

/// One option the way `getopts` reports it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Opt {
    /// An option without a value (`OPTARG` unset).
    Flag(char),
    /// An option and its value (`-c red`, `-cred`).
    Arg(char, String),
    /// `?` with `OPTARG=char`: an option that is not in the option string.
    Illegal(char),
    /// `:` with `OPTARG=char`: an option whose value is missing.
    MissingArg(char),
}

/// `while getopts :hc: opt; do ... done; shift $((OPTIND-1))`.
///
/// Like the shell builtin it stops at the first argument that does not start
/// with `-` (or is a lone `-`), and consumes a `--` that ends the options.
pub struct Getopts<'a> {
    args: &'a [String],
    spec: Vec<(char, bool)>,
    /// index of the next argument to look at (0 based; the shell's OPTIND-1)
    idx: usize,
    /// offset in the current argument (0 = not inside a cluster)
    pos: usize,
}

impl<'a> Getopts<'a> {
    /// `optstring` is written as for the shell, with or without the leading
    /// `:` (the mode is always silent).
    pub fn new(args: &'a [String], optstring: &str) -> Self {
        let mut spec = Vec::new();
        let mut chars = optstring.trim_start_matches(':').chars().peekable();
        while let Some(c) = chars.next() {
            let takes = chars.peek() == Some(&':');
            if takes {
                chars.next();
            }
            spec.push((c, takes));
        }
        Getopts {
            args,
            spec,
            idx: 0,
            pos: 0,
        }
    }

    /// The arguments left after the options (`"$@"` after the `shift`).
    pub fn rest(&self) -> &'a [String] {
        &self.args[self.idx.min(self.args.len())..]
    }

    /// The next option, or None once the options are used up.
    pub fn next_opt(&mut self) -> Option<Opt> {
        let arg = self.args.get(self.idx)?;
        if self.pos == 0 {
            if arg == "--" {
                self.idx += 1;
                return None;
            }
            if !arg.starts_with('-') || arg == "-" {
                return None;
            }
            self.pos = 1;
        }
        let chars: Vec<char> = arg.chars().collect();
        let c = chars[self.pos];
        self.pos += 1;
        let at_end = self.pos >= chars.len();
        let takes = match self.spec.iter().find(|(o, _)| *o == c && c != ':') {
            None => {
                if at_end {
                    self.idx += 1;
                    self.pos = 0;
                }
                return Some(Opt::Illegal(c));
            }
            Some((_, takes)) => *takes,
        };
        if !takes {
            if at_end {
                self.idx += 1;
                self.pos = 0;
            }
            return Some(Opt::Flag(c));
        }
        if !at_end {
            let value: String = chars[self.pos..].iter().collect();
            self.idx += 1;
            self.pos = 0;
            return Some(Opt::Arg(c, value));
        }
        self.idx += 1;
        self.pos = 0;
        match self.args.get(self.idx) {
            Some(value) => {
                self.idx += 1;
                Some(Opt::Arg(c, value.clone()))
            }
            None => Some(Opt::MissingArg(c)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    fn all(args: &[&str], spec: &str) -> (Vec<Opt>, Vec<String>) {
        let args = v(args);
        let mut g = Getopts::new(&args, spec);
        let mut opts = Vec::new();
        while let Some(o) = g.next_opt() {
            opts.push(o);
        }
        (opts, g.rest().to_vec())
    }

    #[test]
    fn flags_values_and_rest() {
        let (o, r) = all(&["-s", "-c", "red", "-bblue", "text", "-x"], ":hsc:b:");
        assert_eq!(
            o,
            vec![
                Opt::Flag('s'),
                Opt::Arg('c', "red".into()),
                Opt::Arg('b', "blue".into())
            ]
        );
        assert_eq!(r, v(&["text", "-x"]));
    }

    #[test]
    fn clusters() {
        let (o, r) = all(&["-sc", "red", "x"], ":hsc:");
        assert_eq!(o, vec![Opt::Flag('s'), Opt::Arg('c', "red".into())]);
        assert_eq!(r, v(&["x"]));
    }

    #[test]
    fn illegal_and_missing() {
        let (o, r) = all(&["-z", "-c"], ":c:");
        assert_eq!(o, vec![Opt::Illegal('z'), Opt::MissingArg('c')]);
        assert!(r.is_empty());
    }

    #[test]
    fn double_dash_and_lone_dash() {
        let (o, r) = all(&["-h", "--", "-s"], ":hs");
        assert_eq!(o, vec![Opt::Flag('h')]);
        assert_eq!(r, v(&["-s"]));
        let (o, r) = all(&["-", "-h"], ":h");
        assert!(o.is_empty());
        assert_eq!(r, v(&["-", "-h"]));
    }

    #[test]
    fn value_may_look_like_an_option() {
        let (o, _) = all(&["-c", "-h"], ":hc:");
        assert_eq!(o, vec![Opt::Arg('c', "-h".into())]);
    }
}
