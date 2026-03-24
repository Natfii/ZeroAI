# SSH Client in Terminal

**Date**: 2026-03-24
**Status**: Approved

## Overview

Add an SSH client to the in-app terminal that works independently of the AI agent. User types `/ssh user@host` in the REPL, enters SSH mode with full xterm emulation until they exit. While in SSH mode, `@zero <message>` passes a message to the agent with the SSH session's recent output as context. The agent can suggest commands that the user confirms before execution on the remote host.

## Prerequisites

### Kotlin Version Bump

termlib 0.0.22 requires Kotlin 2.3.10. The project is currently on Kotlin 2.0.20. This forces a cascade:

- `kotlin` → 2.3.10
- `ksp` → matching 2.3.10-x.x.x release
- Compose Compiler → version aligned with Kotlin 2.3.x
- Compose BOM → 2026.02.00 (termlib's transitive dependency)
- Gobley Gradle plugin → verify compatibility with Kotlin 2.3.x

This version bump is a prerequisite task that must be completed and verified (full build + test pass) before SSH work begins. If the bump proves too disruptive, the fallback is building termlib from source against Kotlin 2.0.20 or using Termux's `terminal-emulator` (Java, no Kotlin version constraint) wrapped in `AndroidView`.

## Dependencies

- **ConnectBot termlib** (`org.connectbot:termlib:0.0.22`) — Compose-native terminal emulator widget backed by libvterm. Apache 2.0. ~1.1 MB per ABI (arm64). Published on Maven Central.
- **ConnectBot sshlib** (`org.connectbot:sshlib:2.2.43`) — SSH2 client library. BSD 3-Clause. On Maven Central. Ed25519, ECDSA, RSA, post-quantum key exchange.
- **Incremental APK size**: ~2-2.5 MB compressed (Tink already present via `security-crypto`).

## Connection Flow

1. User types `/ssh user@host` or `/ssh user@host:port` in the REPL.
2. `CommandRegistry` parses into a new `CommandResult.SshConnect(user, host, port)` variant in the existing `CommandResult` sealed interface (alongside `RhaiExpression`, `LocalAction`, etc.).
3. sshlib connects to host. On first connect, host key trust dialog shown with fingerprint in OpenSSH format (`SHA256:<base64>`).
4. Auth sequence: try stored SSH keys first → fall back to password dialog (keyboard-interactive / password auth).
5. On success: allocate PTY, start shell, switch terminal to SSH mode.
6. On failure: error block in REPL, stay in REPL mode.

`/ssh-keys` parses into `CommandResult.LocalAction` and navigates to the key management screen.

### Disconnect Triggers

- User types `exit` in the remote shell (server closes channel).
- User types `~.` (SSH escape, intercepted client-side).
- User taps [Disconnect] in the status bar.
- Network loss (broken pipe) → "Connection lost" system message, return to REPL.

### Process Lifecycle

- **App backgrounded**: foreground service keeps process alive, SSH connection persists. sshlib SSH keepalive interval set to 30 seconds (covers aggressive mobile NAT timeouts of 30-120s on cellular).
- **Swiped from recents**: existing `ZeroAIDaemonService.onTaskRemoved()` tears down SSH session cleanly (disconnect packet sent) before process death.
- **Screen off / Doze**: foreground service exempts from Doze, connection persists. Aggressive OEM battery settings may throttle network.
- **Force-kill / crash**: session dies. On next terminal open, mode resets to REPL. System message: "Previous SSH session was terminated."
- **Configuration changes** (rotation, font size): `TerminalViewModel` survives as `AndroidViewModel`. SSH mode state (`SshSession` reference, ring buffer) lives in ViewModel. termlib `Terminal` composable is re-created but reconnects to the same session output flow.
- **No session persistence or reconnect logic.** If connection is lost, user runs `/ssh` again.

### Manifest

No new permissions required. `INTERNET` is already declared. SSH uses its own encrypted transport over raw TCP sockets — `network_security_config.xml` does not apply (SSH is not HTTP).

## SSH Mode UI

### Status Bar (top, 48dp)

- Status indicator: green circle (connected), red triangle (disconnected). Distinct shapes per state, not color alone. Each indicator includes a text label ("Connected" / "Disconnected") and `contentDescription` for screen readers.
- `user@host:port` label.
- [Disconnect] text button on right, minimum 48x48dp touch target.

### Main Area

- termlib `Terminal` composable fills remaining space. Full xterm emulation (vim, htop, tmux work).
- Monospace font consistent with REPL theme.
- System font scaling respected (sp-based).
- Dark background matching REPL.
- Touch: tap to focus, long-press to select/copy, pinch-to-zoom for font size.

### Input Area (bottom)

- Same input field as REPL, but keystrokes route to SSH stdin.
- `@zero` prefix intercepted before reaching SSH channel (see Agent Bridge section for exact rules).
- Extra key row above software keyboard: `Tab`, `Ctrl`, `Esc`, arrow keys, `|`, `/`, `~`.

### Hidden in SSH Mode

- Image attachment picker.
- Voice input FAB.
- Slash command autocomplete.
- Nano intent classifier.

### Transitions

- REPL → SSH: crossfade. REPL state preserved in ViewModel.
- SSH → REPL: crossfade. System message appended: "SSH session to user@host ended."
- No animation under power save mode.

## Agent Bridge (`@zero`)

### Input Interception

Interception rule: if the input field text starts with `@zero ` (case-sensitive, must be at column 0 with a trailing space), the entire line is intercepted before reaching the SSH channel. The text after `@zero ` is the agent message.

**Escape mechanism**: `@@zero` sends the literal text `@zero` to the SSH channel (first `@` is stripped). This handles the edge case of needing to type `@zero` in a remote config file.

**Paste behavior**: if pasted text starts with `@zero `, it is intercepted as a single agent message (same rule as typed input). Multi-line pastes where only the first line starts with `@zero` — the entire paste is treated as the agent message.

### Context Assembly

When `@zero` fires, the agent receives:
1. The user's message.
2. Last ~500 lines of SSH terminal output from a ring buffer maintained in `SshSession`. The output flow is tee'd: raw bytes go to both termlib (for rendering) and a side-channel `LineRingBuffer` that strips ANSI escape sequences via a stateful parser and accumulates plain-text lines. This avoids depending on termlib's limited public API (only `getLastCommandOutput()` is available, which returns only the last command's output). The side-channel approach also means context capture works even during high-throughput output.
3. System context: `"User is in an SSH session to user@host. You may suggest shell commands wrapped in ssh code blocks; the user must explicitly approve each command before it runs."`

Routed through existing `session_send()` FFI path. Same agent loop, same streaming, same tool execution.

### Agent Responses

- Material 3 modal bottom sheet slides up over the terminal.
- Streaming response with existing `ThinkingCard` / `StreamingState` rendering.
- Dismissible (swipe down or tap scrim).
- Terminal remains partially visible above the sheet.

### Agent Command Execution

- Agent includes fenced action blocks in responses: `` ```ssh\ncommand here\n``` ``
- UI renders as tappable "Run on host" button (similar to capability approval pattern).
- User taps → command written to SSH stdin → output appears in terminal.
- **No auto-execution.** User always confirms. This is a remote machine.

### Agent Limitations (v1)

- Cannot open new SSH connections.
- Cannot transfer files (no SCP/SFTP).
- Cannot modify the SSH session (resize, PTY settings).
- Does not see terminal in real-time while streaming — gets a snapshot when `@zero` is invoked.

## Key Management

Accessed via `/ssh-keys` in the REPL or from Settings.

### Storage

- SSH keys stored in a **dedicated** `EncryptedSharedPreferences` file (`"ssh_keys"`) — separate from the API key store to avoid cross-corruption.
- Each entry: label, algorithm, public key, encrypted private key, creation date.
- Default algorithm: Ed25519.

### Operations

- **Generate**: tap "Generate Key", enter label, Ed25519 keypair via sshlib `KeyPairGenerator`. Public key shown for copy-paste.
- **Import**: paste or pick file (OpenSSH format). Passphrase prompt if encrypted. Stored encrypted.
- **Delete**: swipe-to-delete with confirmation.
- **Copy public key**: tap entry, copies to clipboard.

### Not Supported (v1)

- Key export (keys don't leave encrypted storage).
- Certificate authority support.
- Agent forwarding.

### Known Hosts

- Stored in a **dedicated** `EncryptedSharedPreferences` file (`"ssh_known_hosts"`) — separate from SSH keys and API keys.
- Keyed by `host:port`.
- Fingerprint stored and displayed in OpenSSH format: `SHA256:<base64>`.
- First connect: trust dialog with fingerprint.
- Subsequent: silent if match, warning dialog if changed (possible MITM).

## Architecture

### New Classes

| Class | Package | Purpose |
|---|---|---|
| `SshSession` | `data.ssh` | Wraps sshlib `Connection` + `Session` + PTY channel. `connect()`, `write()`, `disconnect()`, `outputFlow: Flow<ByteArray>`. Backed by a `callbackFlow` with `Channel(Channel.BUFFERED, onBufferOverflow = BufferOverflow.DROP_OLDEST)` for backpressure. Output is tee'd to both the flow (for termlib) and an internal `LineRingBuffer` (for agent context, with stateful ANSI stripping). |
| `LineRingBuffer` | `data.ssh` | Fixed-capacity (500 lines) ring buffer. Receives raw bytes, strips ANSI via stateful escape sequence parser, accumulates complete lines. `getLines(): List<String>` returns current contents for agent context assembly. |
| `SshKeyRepository` | `data.ssh` | CRUD for SSH keys in dedicated `EncryptedSharedPreferences("ssh_keys")`. Generate, import, list, delete. |
| `SshHostRepository` | `data.ssh` | Known hosts in dedicated `EncryptedSharedPreferences("ssh_known_hosts")`. Verify, trust, warn-on-change. |
| `SshAgentBridge` | `data.ssh` | Reads plain-text lines from `SshSession`'s `LineRingBuffer` for agent context. Routes `@zero` through `session_send()`. Writes agent action blocks to SSH stdin on user approval. |
| `SshAuthDialog` | `ui.screen.terminal` | Composable dialogs: password prompt, host key trust, key selection. |
| `SshStatusBar` | `ui.screen.terminal` | Top bar: status indicator (shape + color + text + contentDescription), host label, disconnect button (48x48dp min). |
| `SshKeyScreen` | `ui.screen.settings` | Key management: generate, import, list, delete, copy public key. |
| `SshKeyViewModel` | `ui.screen.settings` | ViewModel for key management screen. |

### Modified Classes

| Class | Change |
|---|---|
| `TerminalViewModel` | New `terminalMode: StateFlow<TerminalMode>` sealed interface (`Repl` / `Ssh`). SSH lifecycle, `@zero` interception. SSH state survives configuration changes. |
| `TerminalScreen` | Conditional render: REPL LazyColumn or termlib `Terminal` + `SshStatusBar` based on mode. Bottom sheet for agent responses in SSH mode. |
| `CommandRegistry` | New `/ssh` → `CommandResult.SshConnect(user, host, port)` and `/ssh-keys` → `CommandResult.LocalAction`. |
| `CommandResult` | New `SshConnect(user: String, host: String, port: Int = 22)` variant added to sealed interface. |
| `TerminalBlock` | SSH connection/disconnection messages use the existing `System` variant (same rendering as welcome banners and clear confirmations). |

### No Rust/FFI Changes

Agent bridge uses existing `session_send()`. SSH context is prepended to the user message. The agent doesn't know it's SSH — it sees terminal output and produces action blocks.

### Threading Model

- SSH I/O: `Dispatchers.IO` (sshlib is blocking).
- termlib rendering: main thread (Compose).
- Ring buffer: populated on `Dispatchers.IO` (same coroutine as SSH read loop, tee'd before termlib). Read on `Dispatchers.Default` when `@zero` is invoked.
- Agent bridge: existing session coroutine scope.

### New Dependencies

```toml
# libs.versions.toml
connectbot-sshlib = "2.2.43"
connectbot-termlib = "0.0.22"

# app/build.gradle.kts
implementation("org.connectbot:sshlib:${libs.versions.connectbot.sshlib}")
implementation("org.connectbot:termlib:${libs.versions.connectbot.termlib}")
```

Both published on Maven Central. Requires Kotlin 2.3.10 (see Prerequisites).

## Testing

### Unit Tests

- `SshHostRepositoryTest` — fingerprint match, mismatch, host key changed warning.
- `SshKeyRepositoryTest` — generate Ed25519, import OpenSSH key, delete, list.
- `LineRingBufferTest` — ANSI stripping (colors, cursor movement, alternate screen), line accumulation, capacity rollover at 500 lines.
- `SshAgentBridgeTest` — context assembly from `LineRingBuffer`, `@zero` message parsing, `@@zero` escape passthrough.
- `CommandRegistryTest` — `/ssh user@host`, `/ssh user@host:2222`, `/ssh` (no args → error), `/ssh-keys`.

### Integration Tests

- `SshSessionTest` — connect to a local SSH server (Docker `linuxserver/openssh-server`), authenticate with key, send `echo hello`, verify output, disconnect.
- `TerminalViewModelTest` — mode switching: REPL → SSH → REPL, state preservation across transitions.

### Manual Tests

- Connect to a real server, run `vim`, `htop`, `top`, verify rendering.
- `@zero` round-trip: ask agent about SSH output, verify context includes recent terminal lines.
- Agent command suggestion: verify "Run on host" button appears and executes on tap.
- Force-kill app during SSH, reopen, verify clean recovery to REPL.
