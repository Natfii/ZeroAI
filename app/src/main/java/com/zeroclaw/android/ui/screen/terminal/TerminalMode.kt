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
 * Each variant represents a discrete phase in the connection sequence
 * from initial shell launch or SSH handshake through to an established
 * session or terminal error. The [TerminalViewModel] emits these states
 * so that composables can render the appropriate status bar, auth
 * dialogs, and error banners.
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
     * An SSH connection attempt is in progress.
     *
     * The UI should display a connecting indicator with the target
     * host details while the transport handshake completes.
     *
     * @property host Remote hostname or IP address.
     * @property port Remote SSH port number.
     * @property user Username for the SSH session.
     */
    data class SshConnecting(
        val host: String,
        val port: Int,
        val user: String,
    ) : TtySessionUiState

    /**
     * The server presented a host key that requires user verification.
     *
     * The UI should display the key fingerprint and algorithm in a
     * confirmation dialog before the connection can proceed.
     *
     * @property host Remote hostname or IP address.
     * @property port Remote SSH port number.
     * @property algorithm Key algorithm name (e.g. "ssh-ed25519").
     * @property fingerprintSha256 SHA-256 fingerprint of the host key.
     * @property isChanged Whether the fingerprint differs from a previously trusted key.
     */
    data class HostKeyVerification(
        val host: String,
        val port: Int,
        val algorithm: String,
        val fingerprintSha256: String,
        val isChanged: Boolean,
    ) : TtySessionUiState

    /**
     * The server requires authentication and advertised the given methods.
     *
     * The UI should present an auth dialog appropriate to the available
     * methods (e.g. password entry, key selection).
     *
     * @property methods List of SSH authentication method names
     *   (e.g. "publickey", "password", "keyboard-interactive").
     */
    data class SshAuthRequired(
        val methods: List<String>,
    ) : TtySessionUiState

    /**
     * An SSH session is fully established and interactive.
     *
     * The status bar displays the connected host label and the TTY
     * surface accepts user input.
     *
     * @property hostLabel Human-readable label for the connected host
     *   (e.g. "user@example.com:22").
     */
    data class SshConnected(
        val hostLabel: String,
    ) : TtySessionUiState

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
