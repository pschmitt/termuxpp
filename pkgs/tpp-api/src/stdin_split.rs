//! Standard input shared between an API call and the command that runs after
//! it.
//!
//! The C client copies its standard input to the app for as long as the call
//! lasts, then `exec`s `termux-callback`, which inherits the same descriptor and
//! so reads whatever the client had not read yet. This type gives the same
//! hand-over without a process change: [`SplitStdin::new`] starts a pump that
//! reads the real input; what it reads goes to the [`first`](SplitStdin::first)
//! reader (the call) until [`SplitStdin::hand_over`], and to the
//! [`rest`](SplitStdin::rest) reader (the callback) afterwards. Data the call
//! had not consumed yet at that point moves to the rest reader, so nothing is
//! lost.
//!
//! The pump thread stays blocked in `read` until the input has data or ends; it
//! ends with the process (as the C client's copier thread did).

use std::collections::VecDeque;
use std::io::{self, Read};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;

#[derive(Default)]
struct State {
    /// data for the call, not read by it yet
    first: VecDeque<u8>,
    /// data for what runs after the call
    rest: VecDeque<u8>,
    /// the call still gets the input
    first_open: bool,
    /// the input has ended
    eof: bool,
}

struct Shared {
    state: Mutex<State>,
    changed: Condvar,
}

impl Shared {
    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// The two readers over one input; see the module documentation.
pub struct SplitStdin {
    shared: Arc<Shared>,
}

/// The reader of the call: the input until [`SplitStdin::hand_over`], then end
/// of input.
pub struct First(Arc<Shared>);

/// The reader of what follows the call: the input from the hand-over on.
pub struct Rest(Arc<Shared>);

impl SplitStdin {
    /// Start reading `input`.
    pub fn new(mut input: Box<dyn Read + Send>) -> SplitStdin {
        let shared = Arc::new(Shared {
            state: Mutex::new(State {
                first_open: true,
                ..State::default()
            }),
            changed: Condvar::new(),
        });
        let pump = shared.clone();
        thread::spawn(move || {
            let mut buf = [0u8; 4096];
            loop {
                let n = match input.read(&mut buf) {
                    Ok(0) | Err(_) => 0,
                    Ok(n) => n,
                };
                let mut st = pump.lock();
                if n == 0 {
                    st.eof = true;
                } else if st.first_open {
                    st.first.extend(&buf[..n]);
                } else {
                    st.rest.extend(&buf[..n]);
                }
                drop(st);
                pump.changed.notify_all();
                if n == 0 {
                    break;
                }
            }
        });
        SplitStdin { shared }
    }

    /// The reader for the call.
    pub fn first(&self) -> First {
        First(self.shared.clone())
    }

    /// The reader for what runs after the call.
    pub fn rest(&self) -> Rest {
        Rest(self.shared.clone())
    }

    /// The call is over: its reader ends, and the input from now on, including
    /// what the call has not read yet, is for the rest reader.
    pub fn hand_over(&self) {
        let mut st = self.shared.lock();
        st.first_open = false;
        let mut pending = std::mem::take(&mut st.first);
        pending.extend(st.rest.drain(..));
        st.rest = pending;
        drop(st);
        self.shared.changed.notify_all();
    }
}

fn drain(from: &mut VecDeque<u8>, buf: &mut [u8]) -> usize {
    let n = buf.len().min(from.len());
    for (slot, byte) in buf.iter_mut().zip(from.drain(..n)) {
        *slot = byte;
    }
    n
}

impl Read for First {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let mut st = self.0.lock();
        loop {
            if !st.first_open {
                return Ok(0);
            }
            if !st.first.is_empty() {
                return Ok(drain(&mut st.first, buf));
            }
            if st.eof {
                return Ok(0);
            }
            st = self.0.changed.wait(st).unwrap_or_else(|e| e.into_inner());
        }
    }
}

impl Read for Rest {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let mut st = self.0.lock();
        loop {
            if !st.rest.is_empty() {
                return Ok(drain(&mut st.rest, buf));
            }
            if st.eof {
                return Ok(0);
            }
            st = self.0.changed.wait(st).unwrap_or_else(|e| e.into_inner());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::sync::mpsc;

    /// A reader that yields what the test sends and ends when the sender goes.
    struct Channel(mpsc::Receiver<Vec<u8>>);

    impl Read for Channel {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            match self.0.recv() {
                Ok(chunk) => {
                    buf[..chunk.len()].copy_from_slice(&chunk);
                    Ok(chunk.len())
                }
                Err(_) => Ok(0),
            }
        }
    }

    fn all(mut r: impl Read) -> Vec<u8> {
        let mut v = Vec::new();
        r.read_to_end(&mut v).unwrap();
        v
    }

    #[test]
    fn a_finite_input_goes_to_the_call() {
        let split = SplitStdin::new(Box::new(Cursor::new(b"hello\n".to_vec())));
        assert_eq!(all(split.first()), b"hello\n");
        split.hand_over();
        assert!(all(split.rest()).is_empty());
    }

    #[test]
    fn input_after_the_hand_over_goes_to_the_rest() {
        let (tx, rx) = mpsc::channel();
        let split = SplitStdin::new(Box::new(Channel(rx)));
        let mut first = split.first();
        tx.send(b"early".to_vec()).unwrap();
        let mut buf = [0u8; 16];
        let n = first.read(&mut buf).unwrap();
        assert_eq!(&buf[..n], b"early");

        split.hand_over();
        // the call's reader ends, even though the input has not
        assert_eq!(first.read(&mut buf).unwrap(), 0);
        tx.send(b"late".to_vec()).unwrap();
        drop(tx);
        assert_eq!(all(split.rest()), b"late");
    }

    #[test]
    fn what_the_call_did_not_read_is_kept_for_the_rest() {
        let (tx, rx) = mpsc::channel();
        let split = SplitStdin::new(Box::new(Channel(rx)));
        tx.send(b"unread ".to_vec()).unwrap();
        // let the pump queue it for the call
        for _ in 0..200 {
            if !split.shared.lock().first.is_empty() {
                break;
            }
            thread::sleep(std::time::Duration::from_millis(5));
        }
        split.hand_over();
        tx.send(b"later".to_vec()).unwrap();
        drop(tx);
        assert_eq!(all(split.rest()), b"unread later");
    }
}
