/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

/**
 * Active presentation mode of the terminal screen.
 *
 * The terminal can operate in two mutually exclusive modes: an interactive
 * AI chat REPL ([Repl]) or a raw TTY session ([Tty]) connected to a local
 * shell or remote SSH host. The [TerminalViewModel] uses this sealed
 * hierarchy to decide which compositor and input handling path is active.
 */
sealed interface TerminalMode {
    /**
     * The default interactive REPL mode.
     *
     * User input is parsed by [CommandRegistry] and routed through the
     * daemon FFI bridge for AI chat, slash commands, and Rhai evaluation.
     */
    data object Repl : TerminalMode

    /**
     * Raw TTY mode backed by either a local shell or an SSH connection.
     *
     * While in this mode the terminal renders a VT100-compatible output
     * surface and routes keystrokes directly to the underlying session
     * instead of through the REPL command parser.
     *
     * @property session Current lifecycle state of the TTY session.
     */
    data class Tty(
        val session: TtySessionUiState,
    ) : TerminalMode
}

/**
 * Observable UI state of a TTY session lifecycle.
 *
 * The local shell session is opened immediately; [Error] carries a
 * terminal failure. The [TerminalViewModel] emits these states so that
 * composables can render the status bar and error banners.
 */
sealed interface TtySessionUiState {
    /**
     * A local shell session running directly on the device.
     *
     * No network handshake is required; the PTY is opened immediately
     * against the device shell (typically `/system/bin/sh`).
     */
    data object LocalShell : TtySessionUiState

    /**
     * The TTY session encountered a terminal error.
     *
     * The UI should display the error message and offer a retry or
     * dismiss action.
     *
     * @property message Human-readable description of the failure.
     */
    data class Error(
        val message: String,
    ) : TtySessionUiState
}
