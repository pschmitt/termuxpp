//! `tts-engines`: `tpp tts engines`.
use crate::{Call, Io};

pub const USAGE: &str = include_str!("../help/tts-engines.txt");
const NAME: &str = "tpp tts engines";

pub fn run(args: &[String], io: &mut Io) -> i32 {
    super::simple_call(
        args,
        io,
        NAME,
        USAGE,
        Call::new("TextToSpeech").es("engine", "LIST_AVAILABLE"),
    )
}
