//! `clipboard-get`: `tpp clipboard get`.
//!
//! Deviation: `-i` and `-m` can read images from the integrated Android API.
use crate::{Call, Io, Stdin};

pub const USAGE: &str = include_str!("../help/clipboard-get.txt");
const NAME: &str = "tpp clipboard get";
const IMAGE_MAGIC: &[u8] = b"\0TPPIMG1\n";
const MAX_IMAGE_BYTES: usize = 32 * 1024 * 1024;

pub fn run(args: &[String], io: &mut Io) -> i32 {
    match args {
        [option] if option == "-i" => read_image(io, false),
        [option] if option == "-m" => read_image(io, true),
        _ => super::simple_call(args, io, NAME, USAGE, Call::new("Clipboard")),
    }
}

fn read_image(io: &mut Io, with_mime: bool) -> i32 {
    let call = Call::new("Clipboard")
        .es("api_version", 3)
        .stdin(Stdin::None);
    let result = io.call_capture(call);
    let (bytes, mut reply) = match result {
        Ok(value) => value,
        Err(error) => {
            io.errln(&format!("{NAME}: {error}"));
            return 1;
        }
    };
    reply.close_fd();

    match image_payload(&bytes) {
        Ok((mime, image)) => {
            let result = (|| {
                if with_mime {
                    writeln!(io.stdout, "{mime}")?;
                }
                io.stdout.write_all(image)?;
                io.stdout.flush()
            })();
            match result {
                Ok(()) => 0,
                Err(error) => {
                    io.errln(&format!("{NAME}: could not write image: {error}"));
                    1
                }
            }
        }
        Err(error) => {
            io.errln(&format!("{NAME}: {error}"));
            1
        }
    }
}

fn image_payload(bytes: &[u8]) -> Result<(&str, &[u8]), &'static str> {
    let frame = bytes
        .strip_prefix(IMAGE_MAGIC)
        .ok_or("the app did not return an image response; update the TermuxPP app")?;
    let mut fields = frame.splitn(3, |&byte| byte == b'\n');
    let mime = fields
        .next()
        .and_then(|mime| std::str::from_utf8(mime).ok());
    let length = fields
        .next()
        .and_then(|length| std::str::from_utf8(length).ok())
        .and_then(|length| length.parse::<usize>().ok());
    match (mime, length, fields.next()) {
        (Some("unavailable"), Some(0), Some([])) => {
            Err("no readable non-sensitive image on the Android clipboard")
        }
        (Some("unreadable"), Some(0), Some([])) => {
            Err("could not read the clipboard image (maximum size 32 MiB)")
        }
        (Some(mime), Some(length), Some(image))
            if mime.strip_prefix("image/").is_some_and(|subtype| {
                !subtype.is_empty()
                    && subtype
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || b"!#$&^_.+-".contains(&byte))
            }) && length > 0
                && length <= MAX_IMAGE_BYTES
                && length == image.len() =>
        {
            Ok((mime, image))
        }
        _ => Err("invalid or incomplete clipboard image response"),
    }
}

#[cfg(test)]
mod tests {
    use crate::testing::{run_with, Answer, FakeTransport};

    #[test]
    fn image_modes_preserve_binary_and_output_the_mime_only_when_requested() {
        let bytes = b"\x89PNG\r\n\0\xff\n";
        let mut frame = format!("\0TPPIMG1\nimage/png\n{}\n", bytes.len()).into_bytes();
        frame.extend_from_slice(bytes);
        let transport = FakeTransport::with_answers(vec![Answer {
            output: frame,
            ..Answer::default()
        }]);

        let raw = run_with("clipboard-get", &["-i"], b"", &transport, &[]);
        assert_eq!(raw.status, 0);
        assert_eq!(raw.stdout, bytes);
        assert!(raw.stderr.is_empty());
        assert_eq!(transport.calls()[0].args, ["--es", "api_version", "3"]);

        let typed = run_with("clipboard-get", &["-m"], b"", &transport, &[]);
        assert_eq!(typed.status, 0);
        assert_eq!(typed.stdout, [b"image/png\n", bytes.as_slice()].concat());
    }

    #[test]
    fn image_failures_never_write_payload_bytes() {
        for reply in [
            "old app returned text",
            "\0TPPIMG1\nunavailable\n0\n",
            "\0TPPIMG1\nunreadable\n0\n",
            "\0TPPIMG1\nimage/png\n5\nshortened",
            "\0TPPIMG1\nimage/png\n9\nshort",
        ] {
            let transport = FakeTransport::replying(reply);
            let outcome = run_with("clipboard-get", &["-i"], b"", &transport, &[]);
            assert_eq!(outcome.status, 1);
            assert!(outcome.stdout.is_empty());
            assert!(!outcome.stderr.is_empty());
        }
    }
}
