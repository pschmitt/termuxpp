/* Adapter for the Apache-2.0 JNI implementation pinned in build.gradle. */
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalNew Java_com_termux_terminal_GhosttyTerminalNative_terminalNew
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalFree Java_com_termux_terminal_GhosttyTerminalNative_terminalFree
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalFeed Java_com_termux_terminal_GhosttyTerminalNative_terminalFeed
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalEvents Java_com_termux_terminal_GhosttyTerminalNative_terminalEvents
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalTitle Java_com_termux_terminal_GhosttyTerminalNative_terminalTitle
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalResize Java_com_termux_terminal_GhosttyTerminalNative_terminalResize
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalSetColors Java_com_termux_terminal_GhosttyTerminalNative_terminalSetColors
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalScroll Java_com_termux_terminal_GhosttyTerminalNative_terminalScroll
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalScrollbar Java_com_termux_terminal_GhosttyTerminalNative_terminalScrollbar
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalSnapshot Java_com_termux_terminal_GhosttyTerminalNative_terminalSnapshot
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalGraphics Java_com_termux_terminal_GhosttyTerminalNative_terminalGraphics
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalImage Java_com_termux_terminal_GhosttyTerminalNative_terminalImage
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalEncodePaste Java_com_termux_terminal_GhosttyTerminalNative_terminalEncodePaste
#define Java_io_github_sylirre_terminal_term_TerminalNative_terminalEncodeMouse Java_com_termux_terminal_GhosttyTerminalNative_terminalEncodeMouse

#include "terminal_jni.c"

JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminalNative_terminalReset(
    JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    TermCtx *ctx = (TermCtx *)(intptr_t)handle;
    ghostty_terminal_reset(ctx->term);
}

/* These Bionic wrappers were added after TermuxPP's minimum Android API.
 * The 64-bit Android kernels supported by this module have both syscalls. */
#if __ANDROID_API__ < 24
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

__attribute__((visibility("hidden")))
ssize_t preadv64(int fd, const struct iovec *iov, int count, off64_t offset) {
    return syscall(__NR_preadv, fd, iov, count, (unsigned long)offset, 0UL);
}

__attribute__((visibility("hidden")))
ssize_t pwritev64(int fd, const struct iovec *iov, int count, off64_t offset) {
    return syscall(__NR_pwritev, fd, iov, count, (unsigned long)offset, 0UL);
}
#endif
