# GPU Terminal Emulator with SSH

**Date**: 2026-03-24
**Status**: Approved (v2 — full rewrite from ConnectBot approach)

## Overview

Add a GPU-accelerated terminal emulator to the in-app terminal tab powered by libghostty-vt (terminal emulation) and a Ghostty-derived GLES 3.2 renderer. The terminal is a general-purpose TTY — SSH is one of many things you can do in it.

User types `@tty` in the REPL to open the terminal. From there: `ssh user@host`, local shell commands, or `@zero <message>` to talk to the AI agent. The REPL (rich Compose UI) and TTY (GPU-rendered terminal) are separate modes in the same tab.

## Architecture Overview

```
┌─ Kotlin ──────────────────────────────────────────┐
│ TerminalScreen                                     │
│  ├─ REPL mode (default): existing LazyColumn       │
│  └─ TTY mode (@tty): GLSurfaceView in AndroidView  │
│       ├─ SurfaceHolder.Callback → JNI              │
│       ├─ KeyEvent / IME / extra key row → JNI      │
│       ├─ @zero → agent bottom sheet (Compose)      │
│       └─ Status bar (Compose overlay)              │
├─ SSH key management (EncryptedSharedPreferences)   │
├─ Auth dialogs (password, host key trust)           │
└────────────────────────────────────────────────────┘
          ↕ JNI (surface, input, resize only)
┌─ Rust (inside libzeroclaw.so) ────────────────────┐
│ TtySession                                         │
│  ├─ Local shell: /system/bin/sh via PTY            │
│  ├─ SSH: russh (async, tokio)                      │
│  ├─ libghostty-vt (C FFI, in-process)              │
│  │    └─ VT parser, screen buffer, scrollback      │
│  │    └─ Render state API (dirty cell tracking)    │
│  ├─ GLES 3.2 renderer (via glow)                   │
│  │    ├─ Ghostty shader architecture, ported       │
│  │    ├─ Glyph atlas (fontdue + etagere)           │
│  │    └─ Instanced cell rendering                  │
│  └─ LineRingBuffer (agent context, ANSI stripped)  │
├─ Agent bridge: @zero → session_send() FFI          │
└────────────────────────────────────────────────────┘
          ↕ C FFI (in-process, zero overhead)
┌─ Zig (static .a, linked into libzeroclaw.so) ────┐
│ libghostty-vt                                      │
│  └─ Zero-dependency VT terminal emulator           │
│  └─ render state API: incremental dirty tracking   │
│  └─ Input encoding (Kitty keyboard protocol)       │
└────────────────────────────────────────────────────┘
```

## Dependencies

### New Rust Crates

| Crate | Purpose | License | Size Impact |
|---|---|---|---|
| `russh` | SSH2 client (async, tokio) | Apache 2.0 | ~300KB |
| `glow` | Thin GLES bindings | MIT/Apache | ~50KB |
| `fontdue` | Glyph rasterizer (monospace) | MIT | ~100KB |
| `etagere` | Texture atlas allocator | MIT/Apache | ~30KB |

### Native Library

| Library | Source | Build | License |
|---|---|---|---|
| `libghostty-vt` | Zig, zero dependencies | Zig cross-compile → static `.a` for `aarch64-linux-android` | MIT |

libghostty-vt is statically linked into `libzeroclaw.so` via Rust `build.rs`. No dynamic loading, no bionic libc dependency (libghostty-vt requires no libc). Rust accesses it via `bindgen`-generated C FFI bindings.

### Removed (vs v1 spec)

No Kotlin terminal/SSH libraries. No ConnectBot termlib, no sshlib, no Tink dependency. No Kotlin version bump required.

### Estimated Size Impact

- libghostty-vt static `.a` (arm64): ~1-4 MB (estimated, unconfirmed for Android)
- Rust crates (russh + glow + fontdue + etagere): ~500KB stripped
- GLSL shaders: negligible
- **Total**: ~2-5 MB added to `libzeroclaw.so`

## TTY Activation

### Entry

User types `@tty` in the REPL. `CommandRegistry` parses into `CommandResult.TtyOpen`. ViewModel switches `terminalMode` to `Tty`. The GLSurfaceView is created, Rust-side `TtySession` spawns `/system/bin/sh` with a PTY. User sees a local shell prompt.

### Local Shell

`/system/bin/sh` (toybox) — present on every Android device. Provides: `ls`, `cat`, `cp`, `mv`, `rm`, `ps`, `top`, `ping`, `ifconfig`, `logcat`, `am`, `pm`, `dumpsys`. Sufficient for quick local tasks. Users who need more will SSH out.

The PTY is allocated via `openpty()`/`forkpty()` equivalent in Rust (using `nix` crate or raw libc). Shell runs scoped to the app's private directory as working directory.

### SSH from Within TTY

User types `ssh user@host` in the local shell. This is NOT intercepted by the app — it would invoke Android's `/system/bin/ssh` if present (usually not). Instead, SSH is handled by detecting the command pattern in `TtySession`:

- If input matches `^ssh\s+` pattern, `TtySession` intercepts and uses `russh` to establish the connection.
- On first connect: host key trust dialog (Kotlin-side, via JNI callback).
- Auth: try stored SSH keys → password dialog.
- On success: `russh` PTY channel replaces the local shell PTY as the input/output source for libghostty-vt.
- On disconnect: reverts to local shell.

Alternatively, `/ssh user@host` as a recognized escape command if pattern detection feels fragile.

### Exit

- Type `exit` in local shell (or SSH session ends) → if no more shells, return to REPL.
- `Ctrl+D` on empty line → same as `exit`.
- Swipe-down gesture or tap [X] in status bar → confirm dialog, then back to REPL.
- System message appended to REPL: "TTY session ended."

## TTY Mode UI

### Status Bar (top, 48dp, Compose overlay)

- Status indicator: green circle + "Local" label, or green circle + `user@host` when SSH'd. Red triangle + "Disconnected" on SSH connection loss. Distinct shapes, text labels, and `contentDescription` for accessibility.
- [X] close button on right, minimum 48x48dp touch target.

### Main Area

- `GLSurfaceView` wrapped in `AndroidView` composable. Fills remaining space.
- Rust-side `glow` renderer draws the terminal grid directly to the GL surface.
- Full xterm emulation via libghostty-vt: vim, htop, tmux, nano all work.
- Kitty keyboard protocol, Unicode grapheme clustering (emoji, RTL), scrollback with reflow.
- Dark background matching REPL theme.
- Touch: tap to focus, long-press to select/copy, pinch-to-zoom for font size.

### Input

- Keyboard input routed via JNI to Rust `TtySession`, which encodes via libghostty-vt's key encoding API and writes to the PTY/SSH channel.
- Extra key row above software keyboard: `Tab`, `Ctrl`, `Esc`, `Alt`, arrow keys, `|`, `/`, `~`, `-`. Rendered as a Compose `Row` positioned above the IME.
- `@zero` prefix intercepted in Kotlin before reaching JNI (see Agent Bridge).

### Hidden in TTY Mode

- Image attachment picker.
- Voice input FAB.
- Slash command autocomplete.
- Nano intent classifier.

### Transitions

- REPL → TTY: crossfade. REPL state preserved in ViewModel.
- TTY → REPL: crossfade. System message appended.
- No animation under power save mode.

## GLES 3.2 Renderer

### Ghostty Shader Port

Ghostty's OpenGL 4.3 renderer requires 5 changes for GLES 3.2 compatibility (~100-200 lines):

| GL 4.3 Feature | GLES 3.2 Fix |
|---|---|
| `#version 430 core` | `#version 320 es` + `precision highp float;` |
| `layout(origin_upper_left) gl_FragCoord` | Manual Y-flip: `screen_size.y - gl_FragCoord.y` |
| `GL_TEXTURE_RECTANGLE` / `sampler2DRect` | `GL_TEXTURE_2D` + `texelFetch()` with integer coords |
| `GL_FRAMEBUFFER_SRGB` | Manual sRGB in shaders (Ghostty's `linearize()`/`unlinearize()` already exist in `common.glsl`) |
| `GL_BGRA` pixel format | `GL_EXT_texture_format_BGRA8888` (99% of Android GPUs) or CPU swizzle |

### Rendering Pipeline

Ghostty's multi-pass instanced architecture, implemented in Rust via `glow`:

1. **Cell background pass**: instanced quads for cell background colors.
2. **Cell text pass**: instanced quads sampling from glyph atlas. One `CellInstance` (32 bytes) per visible cell. Single draw call for all glyphs.
3. **Cursor pass**: single quad overlay.

Glyph atlas management:
- `fontdue` rasterizes glyphs on cache miss.
- `etagere` shelf-packs glyphs into a GPU texture.
- Cache key: `(glyph_id, font_size)`. No kerning for monospace.
- Atlas grows on new characters, persists across frames.

### GL Surface Lifecycle

- Kotlin `GLSurfaceView` creates EGL context (GLES 3.2).
- `SurfaceHolder.Callback.surfaceCreated()` → JNI call to Rust with `ANativeWindow` pointer.
- Rust initializes `glow` context from the current EGL context.
- `GLSurfaceView.Renderer.onDrawFrame()` → JNI call to Rust `render_frame()`.
- Rust reads dirty cells from libghostty-vt render state, updates instance buffer, draws.
- `surfaceDestroyed()` → Rust drops GL resources before surface is released.

### Performance

- 80x40 grid = 3200 cells. At 32 bytes/instance = ~100KB instance buffer.
- Dirty cell tracking: most frames update only a few cells. Full redraw only on scroll.
- Target: <2ms per frame (instanced rendering). 60fps sustained during `cat` of large files.
- Battery: renderer idles when terminal content is static (no dirty cells → no draw calls).

## Agent Bridge (`@zero`)

### Input Interception

Interception happens in Kotlin, before JNI. If the input field text starts with `@zero ` (case-sensitive, column 0, trailing space), the line is intercepted. Text after `@zero ` is the agent message.

**Escape mechanism**: `@@zero` sends literal `@zero` to the TTY (first `@` stripped).

**Paste behavior**: if pasted text starts with `@zero `, entire paste is treated as agent message.

### Context Assembly

When `@zero` fires, Kotlin calls a Rust FFI function `tty_get_context()` which returns the last ~500 lines of plain text from the `LineRingBuffer`. The ring buffer is populated in Rust by tee-ing the PTY/SSH output through a stateful ANSI stripper before feeding it to libghostty-vt. No data crosses FFI per frame — only on `@zero` invocation.

The agent receives:
1. The user's message.
2. The 500-line context string from Rust.
3. System context: `"User is in a terminal session [local | SSH to user@host]. You may suggest shell commands wrapped in tty code blocks; the user must explicitly approve each command before it runs."`

Routed through existing `session_send()` FFI path.

### Agent Responses

- Material 3 modal bottom sheet slides up over the GLSurfaceView.
- Streaming response with existing `ThinkingCard` / `StreamingState` rendering.
- Dismissible (swipe down or tap scrim).
- GLSurfaceView visible above the sheet.

### Agent Command Execution

- Agent includes fenced action blocks: `` ```tty\ncommand here\n``` ``
- UI renders as tappable "Run" button.
- User taps → Kotlin calls Rust FFI `tty_write(bytes)` → written to PTY/SSH channel.
- **No auto-execution.** User always confirms.

### Agent Limitations (v1)

- Cannot open new SSH connections.
- Cannot transfer files.
- Cannot resize or modify the terminal.
- Gets a snapshot when `@zero` is invoked, not real-time.

## SSH Key Management

Accessed via `/ssh-keys` in the REPL or from Settings.

### Storage

- SSH keys in dedicated `EncryptedSharedPreferences("ssh_keys")` — separate from API keys.
- Each entry: label, algorithm, public key, encrypted private key, creation date.
- Default algorithm: Ed25519.
- Key generation and import handled in Kotlin (using BouncyCastle or `russh-keys` via FFI).

### Operations

- **Generate**: enter label, Ed25519 keypair generated. Public key shown for copy-paste.
- **Import**: paste or pick file (OpenSSH format). Passphrase prompt if encrypted.
- **Delete**: swipe-to-delete with confirmation.
- **Copy public key**: tap entry, copies to clipboard.

### Not Supported (v1)

- Key export.
- Certificate authority support.
- Agent forwarding.

### Known Hosts

- Dedicated `EncryptedSharedPreferences("ssh_known_hosts")`, keyed by `host:port`.
- Fingerprint in OpenSSH format: `SHA256:<base64>`.
- First connect: trust dialog with fingerprint.
- Subsequent: silent if match, warning dialog if changed (MITM warning).

## Process Lifecycle

- **App backgrounded**: foreground service keeps process alive. SSH keepalive interval: 30 seconds (covers mobile NAT timeouts). Local shell PTY stays open.
- **Swiped from recents**: `ZeroAIDaemonService.onTaskRemoved()` sends SSH disconnect, closes PTY.
- **Screen off / Doze**: foreground service exempt. Connection persists.
- **Force-kill / crash**: session dies. Next terminal open resets to REPL. System message: "Previous TTY session was terminated."
- **Configuration changes** (rotation, font size): `TerminalViewModel` survives. GLSurfaceView is recreated but Rust-side `TtySession` (and libghostty-vt state) persists. `surfaceCreated()` re-initializes GL resources from the existing terminal state.
- **No session persistence or reconnect.** Lost connection → user opens `@tty` again.

### Manifest

No new permissions. `INTERNET` already declared. SSH is not HTTP — `network_security_config.xml` does not apply.

## New Rust Modules

| Module | Location | Purpose |
|---|---|---|
| `tty/mod.rs` | `zeroclaw-ffi/src/tty/` | `TtySession`: PTY management, shell spawn, SSH lifecycle via russh |
| `tty/renderer.rs` | `zeroclaw-ffi/src/tty/` | GLES 3.2 renderer via glow: instanced cell rendering, glyph atlas |
| `tty/ghostty_bridge.rs` | `zeroclaw-ffi/src/tty/` | Safe Rust wrapper around libghostty-vt C API (bindgen-generated) |
| `tty/ring_buffer.rs` | `zeroclaw-ffi/src/tty/` | `LineRingBuffer`: 500-line capacity, stateful ANSI stripping |
| `tty/ssh.rs` | `zeroclaw-ffi/src/tty/` | russh connection management, key auth, host key verification callbacks |
| `tty/shaders/` | `zeroclaw-ffi/src/tty/` | GLSL ES 3.20 shaders: cell_bg, cell_text, cursor (ported from Ghostty) |

### New FFI Exports

| Function | Purpose |
|---|---|
| `tty_create(surface_ptr: u64) → Result<(), FfiError>` | Initialize TtySession + GL renderer with ANativeWindow |
| `tty_destroy()` | Tear down session, release GL resources |
| `tty_render_frame()` | Read dirty cells, update instance buffer, draw |
| `tty_write(data: Vec<u8>)` | Write bytes to PTY/SSH stdin |
| `tty_resize(cols: u32, rows: u32, width: u32, height: u32)` | Resize PTY + terminal + renderer viewport |
| `tty_ssh_connect(host: String, port: u16, user: String) → Result<(), FfiError>` | Start SSH via russh, swap PTY source |
| `tty_ssh_auth_password(password: String) → Result<bool, FfiError>` | Password auth attempt |
| `tty_ssh_auth_key(private_key: Vec<u8>, passphrase: Option<String>) → Result<bool, FfiError>` | Key auth attempt |
| `tty_ssh_disconnect()` | Close SSH, revert to local shell |
| `tty_get_context() → String` | Return ring buffer contents for agent |
| `tty_get_host_key_fingerprint() → Result<String, FfiError>` | Get pending host key for trust dialog |
| `tty_accept_host_key()` | User accepted the host key |

All wrapped in `catch_unwind`, returning `Result<T, FfiError>`.

## Modified Kotlin Classes

| Class | Change |
|---|---|
| `TerminalViewModel` | New `terminalMode: StateFlow<TerminalMode>` (`Repl` / `Tty`). TTY lifecycle, `@zero` interception, JNI bridge calls. |
| `TerminalScreen` | Conditional render: REPL LazyColumn or `AndroidView { GLSurfaceView }` + status bar. Bottom sheet for agent responses in TTY mode. |
| `CommandRegistry` | New `@tty` → `CommandResult.TtyOpen`, `/ssh-keys` → `CommandResult.LocalAction`. |
| `CommandResult` | New `TtyOpen` variant. |
| `TerminalBlock` | TTY session messages use existing `System` variant. |

### New Kotlin Classes

| Class | Package | Purpose |
|---|---|---|
| `TtyGLSurfaceView` | `ui.screen.terminal` | Custom `GLSurfaceView` subclass: creates GLES 3.2 context, routes `surfaceCreated`/`onDrawFrame`/`surfaceDestroyed` to JNI. |
| `TtyKeyRow` | `ui.screen.terminal` | Compose `Row` of extra keys (Tab, Ctrl, Esc, arrows, etc.) positioned above IME. |
| `TtyStatusBar` | `ui.screen.terminal` | Status indicator + host label + close button. |
| `TtyAuthDialog` | `ui.screen.terminal` | Password prompt, host key trust, key selection dialogs. |
| `SshKeyScreen` | `ui.screen.settings` | Key management UI. |
| `SshKeyViewModel` | `ui.screen.settings` | ViewModel for key management. |
| `SshKeyRepository` | `data.ssh` | CRUD for SSH keys in `EncryptedSharedPreferences("ssh_keys")`. |
| `SshHostRepository` | `data.ssh` | Known hosts store in `EncryptedSharedPreferences("ssh_known_hosts")`. |

## Build Integration

### libghostty-vt Static Library

```
# Cross-compile libghostty-vt for Android
zig build -Dtarget=aarch64-linux-android -Doptimize=ReleaseSafe
# Output: libghostty_vt.a
```

Integrated into Rust build via `build.rs`:
```rust
println!("cargo:rustc-link-lib=static=ghostty_vt");
println!("cargo:rustc-link-search=native={}", ghostty_lib_dir);
```

`bindgen` generates Rust FFI bindings from `ghostty/vt.h`.

### Cargo Dependencies

```toml
# zeroclaw-android/zeroclaw-ffi/Cargo.toml
[dependencies]
russh = "0.50"
glow = "0.16"
fontdue = "0.9"
etagere = "0.2"

[build-dependencies]
bindgen = "0.71"
```

## Testing

### Rust Unit Tests

- `ring_buffer_test` — ANSI stripping (colors, cursor, alternate screen), line accumulation, 500-line rollover.
- `ghostty_bridge_test` — feed bytes to libghostty-vt, read screen buffer, verify cell content and colors.
- `renderer_test` — shader compilation on GLES 3.2 context (requires Android emulator or device).
- `ssh_test` — russh connect to local SSH server (Docker), key auth, command execution, disconnect.

### Kotlin Unit Tests

- `SshKeyRepositoryTest` — generate, import, delete, list.
- `SshHostRepositoryTest` — fingerprint match, mismatch, changed key warning.
- `CommandRegistryTest` — `@tty`, `/ssh-keys`, unknown commands.

### Integration Tests

- `TtySessionTest` — create session, spawn local shell, send `echo hello`, verify in ring buffer.
- `TerminalViewModelTest` — mode switching: REPL → TTY → REPL, state preservation.

### Manual Tests

- Open `@tty`, run `ls`, `cat`, `top` in local shell.
- SSH to a server, run `vim`, `htop`, `tmux` — verify rendering.
- `@zero` round-trip: ask agent about terminal output, verify context.
- Agent command suggestion: "Run" button appears, executes on tap.
- Force-kill during SSH, reopen, verify clean REPL recovery.
- Pinch-to-zoom font size in TTY.
- Extra key row: Tab, Ctrl+C, Esc, arrows all work.

## Risks and Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Zig cross-compile to `aarch64-linux-android` fails for libghostty-vt | Medium | libghostty-vt is zero-dependency (no libc). Static `.a` avoids dlopen issues. Fallback: compile with Android NDK clang if Zig target is broken. |
| libghostty-vt C API breaks (unstable, public alpha) | Medium | Pin to specific commit hash. API surface needed is small (terminal create, write, render state, input encoding). |
| GLES 3.2 shader port has rendering artifacts | Low | Only 5 changes needed. `texelFetch()` is well-tested on Android. Manual sRGB math already in Ghostty's codebase. |
| `russh` async model conflicts with PTY I/O | Low | russh is tokio-native; TtySession already runs on tokio runtime. PTY reads on blocking `spawn_blocking`. |
| Font rendering quality on Android | Medium | `fontdue` is well-tested for monospace. May need to handle Android system font paths for CJK/emoji fallback. |
