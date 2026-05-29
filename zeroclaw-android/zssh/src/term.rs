// Copyright (c) 2026 @Natfii. All rights reserved.

//! Local terminal control: raw mode, window size, and no-echo password
//! entry, all operating on the inherited stdin PTY (fd 0).

use std::io::{BufRead, Write};
use std::os::fd::AsFd;

use nix::sys::termios::{self, LocalFlags, SetArg, Termios};
use zeroize::Zeroizing;

use crate::BoxError;

/// Default grid size used when the window size query fails.
const DEFAULT_COLS: u16 = 80;
/// Default grid rows used when the window size query fails.
const DEFAULT_ROWS: u16 = 24;

// SAFETY: `tiocgwinsz` issues the `TIOCGWINSZ` ioctl, which reads the
// terminal window size into the provided `winsize` out-parameter. The
// macro generates an `unsafe fn` whose only requirement is a valid fd.
nix::ioctl_read_bad!(tiocgwinsz, nix::libc::TIOCGWINSZ, nix::libc::winsize);

/// Restores the terminal to its original line discipline when dropped.
///
/// Captures the current attributes on [`RawModeGuard::enable`] and puts
/// stdin into raw mode so keystrokes (including control characters and
/// arrow-key escapes) reach the remote shell unmodified. The original
/// attributes are restored on drop, including during unwinding.
pub(crate) struct RawModeGuard {
    original: Termios,
}

impl RawModeGuard {
    /// Switches stdin into raw mode, returning a guard that restores the
    /// previous attributes when dropped.
    ///
    /// @return The guard, or an error if the attributes cannot be read or set.
    pub(crate) fn enable() -> Result<Self, BoxError> {
        let stdin = std::io::stdin();
        let original = termios::tcgetattr(stdin.as_fd())?;
        let mut raw = original.clone();
        termios::cfmakeraw(&mut raw);
        termios::tcsetattr(stdin.as_fd(), SetArg::TCSANOW, &raw)?;
        Ok(Self { original })
    }
}

impl Drop for RawModeGuard {
    fn drop(&mut self) {
        let stdin = std::io::stdin();
        let _ = termios::tcsetattr(stdin.as_fd(), SetArg::TCSANOW, &self.original);
    }
}

/// Returns the current `(cols, rows)` of the controlling terminal, falling
/// back to an 80x24 default when the query fails.
pub(crate) fn terminal_size() -> (u16, u16) {
    // SAFETY: zero-initialising a `winsize` (a plain C struct of integers)
    // is valid, and the ioctl below fills it before we read the fields.
    let mut ws: nix::libc::winsize = unsafe { std::mem::zeroed() };
    // SAFETY: stdin (fd 0) is a valid file descriptor for the duration of
    // the call, and `ws` is a valid, writable out-parameter.
    let ok = unsafe { tiocgwinsz(nix::libc::STDIN_FILENO, &mut ws) }.is_ok();
    if ok && ws.ws_col > 0 {
        (ws.ws_col, ws.ws_row)
    } else {
        (DEFAULT_COLS, DEFAULT_ROWS)
    }
}

/// Prompts on stderr and reads a line from stdin with terminal echo
/// disabled, returning the entered secret with the trailing newline
/// stripped.
///
/// Echo is restored even if the read fails. The prompt is written to
/// stderr so it never contaminates the data stream piped to the remote.
///
/// @param prompt Text shown before the hidden input.
/// @return The entered secret, or an error if terminal control fails.
pub(crate) fn read_password(prompt: &str) -> Result<Zeroizing<String>, BoxError> {
    eprint!("{prompt}");
    std::io::stderr().flush()?;

    let stdin = std::io::stdin();
    let original = termios::tcgetattr(stdin.as_fd())?;
    let mut no_echo = original.clone();
    no_echo.local_flags.remove(LocalFlags::ECHO);
    termios::tcsetattr(stdin.as_fd(), SetArg::TCSANOW, &no_echo)?;

    let mut line = String::new();
    let read = stdin.lock().read_line(&mut line);

    // Always restore echo before propagating any read error.
    let _ = termios::tcsetattr(stdin.as_fd(), SetArg::TCSANOW, &original);
    eprintln!();
    read?;

    while line.ends_with('\n') || line.ends_with('\r') {
        line.pop();
    }
    Ok(Zeroizing::new(line))
}
