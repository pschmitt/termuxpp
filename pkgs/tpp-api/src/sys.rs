//! The few libc calls the API protocol needs, declared here so that the crate
//! has no dependencies (the C library is linked by std anyway).

use std::io;
use std::os::raw::{c_int, c_void};

#[repr(C)]
pub struct IoVec {
    pub base: *mut c_void,
    pub len: usize,
}

/// `struct msghdr` as laid out by glibc, musl (little endian) and bionic.
#[repr(C)]
pub struct MsgHdr {
    pub name: *mut c_void,
    pub namelen: u32,
    pub iov: *mut IoVec,
    pub iovlen: usize,
    pub control: *mut c_void,
    pub controllen: usize,
    pub flags: c_int,
}

#[repr(C)]
pub struct CmsgHdr {
    pub len: usize,
    pub level: c_int,
    pub kind: c_int,
}

#[repr(C)]
pub struct UCred {
    pub pid: i32,
    pub uid: u32,
    pub gid: u32,
}

pub const SOL_SOCKET: c_int = 1;
pub const SCM_RIGHTS: c_int = 1;
pub const SO_PEERCRED: c_int = 17;

extern "C" {
    fn recvmsg(fd: c_int, msg: *mut MsgHdr, flags: c_int) -> isize;
    fn getsockopt(fd: c_int, level: c_int, name: c_int, val: *mut c_void, len: *mut u32) -> c_int;
    fn getuid() -> u32;
    pub fn close(fd: c_int) -> c_int;
}

pub fn uid() -> u32 {
    // SAFETY: getuid has no preconditions and cannot fail.
    unsafe { getuid() }
}

/// The uid of the peer of a connected unix socket.
pub fn peer_uid(fd: c_int) -> io::Result<u32> {
    let mut cred = UCred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut len = std::mem::size_of::<UCred>() as u32;
    // SAFETY: cred and len are valid for writes of the sizes passed.
    let rc = unsafe {
        getsockopt(
            fd,
            SOL_SOCKET,
            SO_PEERCRED,
            (&mut cred as *mut UCred).cast(),
            &mut len,
        )
    };
    if rc == 0 {
        Ok(cred.uid)
    } else {
        Err(io::Error::last_os_error())
    }
}

/// `CMSG_ALIGN`.
const fn cmsg_align(n: usize) -> usize {
    let a = std::mem::size_of::<usize>();
    (n + a - 1) & !(a - 1)
}

/// `CMSG_LEN(sizeof(int))`.
const CMSG_LEN_INT: usize =
    cmsg_align(std::mem::size_of::<CmsgHdr>()) + std::mem::size_of::<c_int>();

/// Receive up to `buf.len()` bytes and the file descriptor passed with them,
/// if any (a single SCM_RIGHTS control message holding one descriptor).
/// Returns the byte count (0 at end of stream) and the descriptor.
pub fn recv_with_fd(fd: c_int, buf: &mut [u8]) -> io::Result<(usize, Option<c_int>)> {
    // u64 elements: the control buffer must be aligned for cmsghdr.
    let mut cbuf = [0u64; 32];
    let mut iov = IoVec {
        base: buf.as_mut_ptr().cast(),
        len: buf.len(),
    };
    let mut msg = MsgHdr {
        name: std::ptr::null_mut(),
        namelen: 0,
        iov: &mut iov,
        iovlen: 1,
        control: cbuf.as_mut_ptr().cast(),
        controllen: std::mem::size_of_val(&cbuf),
        flags: 0,
    };
    loop {
        // SAFETY: msg points at live buffers of the sizes it states.
        let n = unsafe { recvmsg(fd, &mut msg, 0) };
        if n < 0 {
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                msg.controllen = std::mem::size_of_val(&cbuf);
                continue;
            }
            return Err(err);
        }
        let mut got = None;
        if msg.controllen >= std::mem::size_of::<CmsgHdr>() {
            // SAFETY: the kernel filled at least one cmsghdr into the aligned
            // control buffer.
            let hdr = unsafe { &*(cbuf.as_ptr() as *const CmsgHdr) };
            if hdr.len == CMSG_LEN_INT && hdr.kind == SCM_RIGHTS && hdr.level == SOL_SOCKET {
                let off = cmsg_align(std::mem::size_of::<CmsgHdr>());
                // SAFETY: hdr.len says an int follows the aligned header.
                let fd = unsafe { *((cbuf.as_ptr() as *const u8).add(off) as *const c_int) };
                got = Some(fd);
            }
        }
        return Ok((n as usize, got));
    }
}

/// The Android API level (0 where unknown or not on Android).
#[cfg(target_os = "android")]
pub fn android_api_level() -> i32 {
    extern "C" {
        fn __system_property_get(name: *const u8, value: *mut u8) -> c_int;
    }
    let mut value = [0u8; 92];
    // SAFETY: the name is NUL terminated and value has PROP_VALUE_MAX bytes.
    let n =
        unsafe { __system_property_get(b"ro.build.version.sdk\0".as_ptr(), value.as_mut_ptr()) };
    if n <= 0 {
        return 0;
    }
    std::str::from_utf8(&value[..n as usize])
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(0)
}

#[cfg(not(target_os = "android"))]
pub fn android_api_level() -> i32 {
    0
}

// ---------------------------------------------------------------------------
// Interrupts: SIGINT and SIGTERM while a command wants to clean up after them.

use std::sync::atomic::{AtomicI32, AtomicUsize, Ordering};
use std::sync::Mutex;

pub const SIGINT: c_int = 2;
pub const SIGTERM: c_int = 15;
const SIG_ERR: usize = usize::MAX;

extern "C" {
    fn signal(sig: c_int, handler: usize) -> usize;
}

/// The signal number received while a guard was active (0: none).
static PENDING: AtomicI32 = AtomicI32::new(0);
/// How many [`InterruptGuard`]s are active, and the dispositions to restore
/// when the last one ends.
static GUARDS: Mutex<(usize, [usize; 2])> = Mutex::new((0, [SIG_ERR; 2]));
/// Mirrors the guard count for [`watching_interrupts`] (no lock needed).
static WATCHERS: AtomicUsize = AtomicUsize::new(0);

const SIGNALS: [c_int; 2] = [SIGINT, SIGTERM];

extern "C" fn on_interrupt(sig: c_int) {
    // Only an atomic store: that is async-signal-safe.
    PENDING.store(sig, Ordering::SeqCst);
}

/// While it lives, SIGINT and SIGTERM do not end the process: they are
/// recorded, and [`interrupted`] reports the first one. The native transport
/// polls for it, so a call that waits for the app ends soon after the signal.
/// The previous dispositions come back when the last guard is dropped.
pub struct InterruptGuard(());

impl InterruptGuard {
    pub fn install() -> InterruptGuard {
        let mut g = GUARDS.lock().unwrap_or_else(|e| e.into_inner());
        if g.0 == 0 {
            PENDING.store(0, Ordering::SeqCst);
            for (slot, sig) in g.1.iter_mut().zip(SIGNALS) {
                // SAFETY: on_interrupt is async-signal-safe (an atomic store)
                // and has the signature `void (int)` that signal() expects.
                *slot = unsafe { signal(sig, on_interrupt as extern "C" fn(c_int) as usize) };
            }
        }
        g.0 += 1;
        WATCHERS.store(g.0, Ordering::SeqCst);
        InterruptGuard(())
    }
}

impl Drop for InterruptGuard {
    fn drop(&mut self) {
        let mut g = GUARDS.lock().unwrap_or_else(|e| e.into_inner());
        g.0 -= 1;
        if g.0 == 0 {
            for (old, sig) in g.1.iter().zip(SIGNALS) {
                if *old != SIG_ERR {
                    // SAFETY: `old` is what signal() returned for this signal.
                    unsafe { signal(sig, *old) };
                }
            }
        }
        WATCHERS.store(g.0, Ordering::SeqCst);
    }
}

/// Whether some [`InterruptGuard`] is active (waiting calls should poll).
pub fn watching_interrupts() -> bool {
    WATCHERS.load(Ordering::SeqCst) != 0
}

/// The signal received since the guard was installed, if any.
pub fn interrupted() -> Option<c_int> {
    match PENDING.load(Ordering::SeqCst) {
        0 => None,
        sig => Some(sig),
    }
}

/// The signal received, if any, and forget it (so that a following call is not
/// cut short by it).
pub fn take_interrupt() -> Option<c_int> {
    match PENDING.swap(0, Ordering::SeqCst) {
        0 => None,
        sig => Some(sig),
    }
}

/// `kill(getpid(), sig)`, for tests.
pub fn raise_in_process(sig: c_int) {
    extern "C" {
        fn raise(sig: c_int) -> c_int;
    }
    // SAFETY: raise has no preconditions.
    unsafe { raise(sig) };
}
