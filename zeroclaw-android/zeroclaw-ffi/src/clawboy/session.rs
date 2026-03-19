// Copyright (c) 2026 @Natfii. All rights reserved.

//! ClawBoy session lifecycle manager.
//!
//! Manages a single global emulator session. Only one game can run at a
//! time. The session starts the emulator, viewer server, and tick loop,
//! and handles start/stop, save state rotation, and provides hooks for
//! Phase 2's Agent Bridge.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use futures_util::FutureExt;
use tokio::sync::{oneshot, watch};

use super::bridge::{AgentBridge, DecisionOutcome, execute_decision};
use super::emulator::Emulator;
use super::journal;
use super::prompts::format_journal_context;
use super::types::{ClawBoySessionInfo, ClawBoyStatus, GbButton};
use super::viewer::ViewerServer;

// ── Global singleton ────────────────────────────────────────────────

/// Active session state, guarded by a mutex.
///
/// Only one ClawBoy session can run at a time. The mutex uses the same
/// poison-recovery pattern as [`crate::runtime::lock_daemon`].
static SESSION: Mutex<Option<SessionState>> = Mutex::new(None);

/// Cached flag: true when a verified ROM exists on disk.
/// Set by [`notify_rom_ready`], cleared by [`notify_rom_removed`].
/// `Ordering::Relaxed` is sufficient — this is a performance gate, not a sync barrier.
#[allow(dead_code)]
pub(crate) static ROM_PRESENT: AtomicBool = AtomicBool::new(false);

/// Data directory path, stored when ROM is marked ready.
/// The trigger interceptor reads this to construct the ROM path.
#[allow(dead_code)]
pub(crate) static ROM_DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

/// User messages queued for the agent bridge.
///
/// When a user sends a message while a ClawBoy session is active, it
/// is pushed here by [`super::chat::check_trigger`]. The tick loop
/// drains this queue each decision tick and injects messages into the
/// LLM conversation so the agent has user context.
static USER_MESSAGES: Mutex<std::collections::VecDeque<String>> =
    Mutex::new(std::collections::VecDeque::new());

/// Pushes a user message for the agent bridge to read on its next
/// decision tick.
#[allow(dead_code)]
pub(crate) fn push_user_message(msg: String) {
    if let Ok(mut q) = USER_MESSAGES.lock() {
        q.push_back(msg);
    }
}

/// Drains all queued user messages.
#[allow(dead_code)]
pub(crate) fn drain_user_messages() -> Vec<String> {
    USER_MESSAGES
        .lock()
        .map(|mut q| q.drain(..).collect())
        .unwrap_or_default()
}

/// Mutable state for a running ClawBoy session.
struct SessionState {
    /// Viewer server (needs shutdown on stop).
    viewer: ViewerServer,
    /// Shutdown signal for the tick loop.
    shutdown_tx: Option<oneshot::Sender<()>>,
    /// Handle for the tick loop task.
    tick_handle: Option<tokio::task::JoinHandle<()>>,
    /// When the session started.
    started_at: Instant,
    /// Viewer URL for status queries.
    viewer_url: String,
    /// Pause control channel.
    pause_tx: watch::Sender<bool>,
    /// Decision interval control channel (milliseconds).
    #[allow(dead_code)]
    interval_tx: watch::Sender<u64>,
    /// Directory for save files.
    #[allow(dead_code)]
    data_dir: PathBuf,
    /// Originating chat channel that started this session.
    #[allow(dead_code)]
    channel_id: Option<String>,
}

/// Timeout for awaiting the tick loop to finish during shutdown.
const TICK_SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(5);

/// Sub-directory under the data root for ClawBoy save files.
const SAVE_SUBDIR: &str = "clawboy/pokemon-red";

/// Stream every Nth frame to viewers (~15 fps at 60 fps tick rate).
const FRAME_STREAM_INTERVAL: u64 = 4;

/// Target frame duration for ~60 fps emulation.
const TARGET_FRAME_NANOS: u64 = 16_666_667;

/// Sleep duration while the session is paused.
const PAUSE_SLEEP: Duration = Duration::from_millis(100);

/// Number of frames to hold each button press.
///
/// Pokemon Red requires 2-4 frames for a press to register reliably.
/// Four frames at 60 fps is ~67ms, which ensures the game's input
/// polling always catches the press.
const BUTTON_HOLD_DURATION: u64 = 4;

/// Number of frames to release all buttons between presses.
///
/// A short gap prevents the game from interpreting consecutive
/// same-direction presses as a single held press.
const BUTTON_RELEASE_GAP: u64 = 2;

// ── Mutex helper ────────────────────────────────────────────────────

/// Locks the session mutex with poison recovery.
///
/// Uses [`std::sync::PoisonError::into_inner`] to reclaim the guard
/// after a panic, preventing permanent lock failure.
fn lock_session() -> std::sync::MutexGuard<'static, Option<SessionState>> {
    SESSION.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "clawboy::session",
            "session mutex was poisoned; recovering: {e}"
        );
        e.into_inner()
    })
}

// ── Public API ──────────────────────────────────────────────────────

/// Starts a new ClawBoy emulator session.
///
/// Loads the ROM, optionally restores a previous battery/save state,
/// starts the WebSocket viewer server, and spawns the emulation tick
/// loop on a tokio task. Only one session can run at a time.
///
/// # Arguments
///
/// * `rom_path` - Filesystem path to the Pokemon Red ROM file.
/// * `decision_interval_ms` - Agent decision interval in milliseconds
///   (Phase 2 hook; unused in Phase 1).
/// * `data_dir` - Root directory for persistent save data.
/// * `channel_id` - Optional chat channel ID for agent commentary
///   (Phase 2 hook; unused in Phase 1).
///
/// # Errors
///
/// Returns an error string if:
/// - A session is already running
/// - The ROM file cannot be read or fails verification
/// - The emulator fails to initialise
/// - The viewer server cannot bind
pub async fn start_session(
    rom_path: String,
    decision_interval_ms: u64,
    data_dir: &Path,
    channel_id: Option<String>,
) -> Result<ClawBoySessionInfo, String> {
    // Check that no session is already running.
    {
        let guard = lock_session();
        if guard.is_some() {
            return Err("a ClawBoy session is already running".into());
        }
    }

    // Read and verify ROM.
    let rom_data =
        std::fs::read(&rom_path).map_err(|e| format!("failed to read ROM at {rom_path}: {e}"))?;

    let verification = Emulator::verify_rom(&rom_data);
    if !verification.valid {
        tracing::warn!(
            target: "clawboy::session",
            sha1 = %verification.sha1,
            "ROM hash mismatch — proceeding anyway"
        );
    }

    // Ensure save directory exists.
    let save_dir = data_dir.join(SAVE_SUBDIR);
    std::fs::create_dir_all(&save_dir)
        .map_err(|e| format!("failed to create save directory: {e}"))?;

    // Try loading battery save (optional — ignore if missing).
    let battery_path = save_dir.join("battery.sav");
    let battery_save = std::fs::read(&battery_path).ok();
    let battery_ref = battery_save.as_deref();

    // Create emulator with ROM and optional battery save.
    let mut emulator = Emulator::new(&rom_data, battery_ref)?;

    // Try loading save states as fallback if battery save was missing.
    if battery_save.is_none() {
        try_load_save_state(&mut emulator, &save_dir);
    }

    // Create frame channel — session owns the sender, viewer gets
    // the receiver.
    let (frame_tx, frame_rx) = watch::channel(std::sync::Arc::new(Vec::new()));

    // Start viewer server with external frame channel.
    let viewer = ViewerServer::start_with_frame_channel(frame_rx).await?;
    let port = viewer.port();

    // Detect local IP for viewer URL.
    let ip = local_ip();
    let viewer_url = format!("http://{ip}:{port}");

    // Use warn level so it's visible in release builds (info is filtered).
    tracing::warn!(
        target: "clawboy::session",
        %viewer_url,
        "ClawBoy session starting"
    );

    // Load journal and format context for the agent bridge.
    let journal = journal::load_journal(data_dir);
    let journal_context = format_journal_context(&journal);

    // Control channels.
    let (pause_tx, pause_rx) = watch::channel(false);
    let (interval_tx, interval_rx) = watch::channel(decision_interval_ms);
    let (shutdown_tx, shutdown_rx) = oneshot::channel();

    // ISO-8601 timestamp for journal entries.
    let started_iso = chrono::Utc::now().to_rfc3339();

    // Spawn the tick loop inside a panic-catching wrapper so crashes
    // are visible in release logcat (warn level is not filtered).
    let session_channel_id = channel_id.clone();
    let tick_data_dir = data_dir.to_path_buf();
    let tick_handle = tokio::spawn(async move {
        let inner = tokio::spawn(run_tick_loop(
            emulator,
            frame_tx,
            shutdown_rx,
            pause_rx,
            interval_rx,
            tick_data_dir,
            channel_id,
            decision_interval_ms,
            journal_context,
            journal,
            started_iso,
        ));
        if let Err(e) = inner.await {
            tracing::warn!(
                target: "clawboy::session",
                "TICK LOOP PANICKED: {e}"
            );
        }
    });

    // Store session state.
    let info = ClawBoySessionInfo {
        viewer_url: viewer_url.clone(),
        port,
    };

    {
        let mut guard = lock_session();
        *guard = Some(SessionState {
            viewer,
            shutdown_tx: Some(shutdown_tx),
            tick_handle: Some(tick_handle),
            started_at: Instant::now(),
            viewer_url,
            pause_tx,
            interval_tx,
            data_dir: data_dir.to_path_buf(),
            channel_id: session_channel_id,
        });
    }

    tracing::info!(
        target: "clawboy::session",
        "ClawBoy session started successfully"
    );

    Ok(info)
}

/// Stops the running ClawBoy session.
///
/// Sends the shutdown signal to the tick loop, waits for it to finish
/// (with a 5-second timeout), and shuts down the viewer server. The
/// tick loop writes save data before exiting.
///
/// # Errors
///
/// Returns an error string if no session is currently running.
pub async fn stop_session() -> Result<(), String> {
    let state = {
        let mut guard = lock_session();
        guard.take().ok_or("no ClawBoy session is running")?
    };

    // Send shutdown signal to the tick loop.
    if let Some(tx) = state.shutdown_tx {
        let _ = tx.send(());
    }

    // Await tick loop with timeout.
    if let Some(handle) = state.tick_handle {
        match tokio::time::timeout(TICK_SHUTDOWN_TIMEOUT, handle).await {
            Ok(Ok(())) => {
                tracing::info!(
                    target: "clawboy::session",
                    "tick loop stopped cleanly"
                );
            }
            Ok(Err(e)) => {
                tracing::warn!(
                    target: "clawboy::session",
                    "tick loop panicked: {e}"
                );
            }
            Err(_) => {
                tracing::warn!(
                    target: "clawboy::session",
                    "tick loop shutdown timed out after {}s",
                    TICK_SHUTDOWN_TIMEOUT.as_secs()
                );
            }
        }
    }

    // Shutdown the viewer server.
    state.viewer.shutdown().await;

    tracing::info!(
        target: "clawboy::session",
        "ClawBoy session stopped"
    );

    Ok(())
}

/// Returns the current status of the ClawBoy session.
///
/// Inspects the global session state and returns the appropriate
/// [`ClawBoyStatus`] variant. The pause state is checked via the
/// watch channel.
pub fn get_status() -> ClawBoyStatus {
    let guard = lock_session();
    match guard.as_ref() {
        None => ClawBoyStatus::Idle,
        Some(state) => {
            if *state.pause_tx.borrow() {
                ClawBoyStatus::Paused {
                    reason: "user paused".into(),
                }
            } else {
                ClawBoyStatus::Playing {
                    viewer_url: state.viewer_url.clone(),
                    play_time_seconds: state.started_at.elapsed().as_secs(),
                }
            }
        }
    }
}

/// Returns the originating channel ID for the active session, if any.
#[allow(dead_code)]
pub fn originating_channel_id() -> Option<String> {
    let guard = lock_session();
    guard.as_ref().and_then(|s| s.channel_id.clone())
}

/// Marks a verified ROM as ready at the given data directory.
#[allow(dead_code)]
pub fn notify_rom_ready(data_dir: &Path) {
    let _ = ROM_DATA_DIR.set(data_dir.to_path_buf());
    ROM_PRESENT.store(true, Ordering::Relaxed);
    tracing::info!(target: "clawboy::session", "ROM-present flag set");
}

/// Clears the ROM-present flag.
#[allow(dead_code)]
pub fn notify_rom_removed() {
    ROM_PRESENT.store(false, Ordering::Relaxed);
    tracing::info!(target: "clawboy::session", "ROM-present flag cleared");
}

/// Updates the agent decision interval.
///
/// Phase 2 hook — in Phase 1 the tick loop ignores this value, but
/// the channel is wired so Phase 2 can read it without changes.
///
/// # Errors
///
/// Returns an error string if no session is currently running.
pub fn set_decision_interval(ms: u64) -> Result<(), String> {
    let guard = lock_session();
    let state = guard.as_ref().ok_or("no ClawBoy session is running")?;
    state
        .interval_tx
        .send(ms)
        .map_err(|e| format!("failed to update decision interval: {e}"))
}

/// Pauses the running ClawBoy session.
///
/// The emulator tick loop will sleep instead of advancing frames
/// until [`resume_session`] is called.
///
/// # Errors
///
/// Returns an error string if no session is currently running.
pub fn pause_session() -> Result<(), String> {
    let guard = lock_session();
    let state = guard.as_ref().ok_or("no ClawBoy session is running")?;
    state
        .pause_tx
        .send(true)
        .map_err(|e| format!("failed to pause session: {e}"))
}

/// Resumes a paused ClawBoy session.
///
/// The emulator tick loop will resume advancing frames.
///
/// # Errors
///
/// Returns an error string if no session is currently running.
pub fn resume_session() -> Result<(), String> {
    let guard = lock_session();
    let state = guard.as_ref().ok_or("no ClawBoy session is running")?;
    state
        .pause_tx
        .send(false)
        .map_err(|e| format!("failed to resume session: {e}"))
}

// ── Tick loop ───────────────────────────────────────────────────────

/// Main emulation loop, spawned on a tokio task.
///
/// Runs the emulator at ~60 fps, streaming frames to the viewer at
/// ~15 fps (every 4th frame). The Agent Bridge drives AI input: each
/// decision tick, game state is snapshotted and an LLM call is
/// spawned on a separate tokio task. The emulator keeps ticking at
/// full speed while the LLM thinks. Completed decisions are applied
/// back to the bridge, which queues button inputs that are fed to
/// the emulator with proper hold/release timing.
///
/// Checks for shutdown, pause, and interval control signals each
/// iteration. On shutdown, writes battery and save state data before
/// returning.
#[allow(clippy::too_many_arguments, clippy::too_many_lines)]
async fn run_tick_loop(
    mut emulator: Emulator,
    frame_tx: watch::Sender<std::sync::Arc<Vec<u8>>>,
    mut shutdown_rx: oneshot::Receiver<()>,
    pause_rx: watch::Receiver<bool>,
    mut interval_rx: watch::Receiver<u64>,
    data_dir: PathBuf,
    _channel_id: Option<String>,
    decision_interval_ms: u64,
    journal_context: String,
    mut journal: super::types::Journal,
    started_iso: String,
) {
    let save_dir = data_dir.join(SAVE_SUBDIR);
    let mut bridge = AgentBridge::new(decision_interval_ms, journal_context);
    let mut frame_counter: u64 = 0;
    let target_frame_time = Duration::from_nanos(TARGET_FRAME_NANOS);

    // Channel for receiving async LLM decision results.
    let (decision_tx, mut decision_rx) =
        tokio::sync::mpsc::channel::<Result<DecisionOutcome, String>>(1);
    let mut decision_pending = false;

    // Button hold timing state. The Game Boy needs buttons held for
    // multiple frames to register a press reliably.
    let mut current_button: Option<GbButton> = None;
    let mut button_hold_frames: u64 = 0;
    let mut in_release_gap = false;
    let mut release_gap_frames: u64 = 0;

    tracing::warn!(
        target: "clawboy::session",
        decision_interval_ms,
        "tick loop started with agent bridge"
    );

    loop {
        let loop_start = Instant::now();

        // Check shutdown signal.
        match shutdown_rx.try_recv() {
            Ok(()) | Err(oneshot::error::TryRecvError::Closed) => {
                tracing::info!(
                    target: "clawboy::session",
                    frames = frame_counter,
                    "tick loop shutting down"
                );
                save_on_shutdown(&mut emulator, &save_dir);
                save_journal_on_shutdown(
                    &mut emulator,
                    &data_dir,
                    &mut journal,
                    &started_iso,
                    frame_counter,
                );
                break;
            }
            Err(oneshot::error::TryRecvError::Empty) => {}
        }

        // Check for decision interval updates from the control channel.
        if interval_rx.has_changed().unwrap_or(false) {
            let ms = *interval_rx.borrow_and_update();
            bridge.update_interval(ms);
            tracing::debug!(
                target: "clawboy::bridge",
                interval_ms = ms,
                "decision interval updated"
            );
        }

        // Check pause state.
        if *pause_rx.borrow() {
            tokio::time::sleep(PAUSE_SLEEP).await;
            continue;
        }

        // Check for completed LLM decision from the spawned task.
        if let Ok(result) = decision_rx.try_recv() {
            decision_pending = false;
            match result {
                Ok(outcome) => {
                    tracing::debug!(
                        target: "clawboy::bridge",
                        inputs = outcome.decision.inputs.len(),
                        "decision completed"
                    );
                    bridge.apply_decision(outcome);
                }
                Err(e) => {
                    tracing::warn!(
                        target: "clawboy::bridge",
                        "decision failed: {e}"
                    );
                }
            }
        }

        // Advance one frame.
        emulator.tick_frame();
        frame_counter += 1;

        // Handle button input timing. Pokemon Red needs buttons held
        // for several frames to register, with a short release gap
        // between consecutive presses.
        if in_release_gap {
            release_gap_frames += 1;
            if release_gap_frames >= BUTTON_RELEASE_GAP {
                in_release_gap = false;
                release_gap_frames = 0;
            }
        } else if let Some(button) = current_button {
            button_hold_frames += 1;
            if button_hold_frames >= BUTTON_HOLD_DURATION {
                emulator.key_lift(button);
                current_button = None;
                button_hold_frames = 0;
                in_release_gap = true;
                release_gap_frames = 0;
            }
        } else if let Some(button) = bridge.tick(&mut emulator, frame_counter) {
            emulator.key_press(button);
            current_button = Some(button);
            button_hold_frames = 0;
        }

        // Start a new LLM decision if the bridge says it is time and
        // no decision is already in flight.
        if !decision_pending
            && bridge.needs_decision()
            && let Some(ctx) = bridge.prepare_decision(&mut emulator)
        {
            let config = match crate::runtime::clone_daemon_config() {
                Ok(c) => c,
                Err(e) => {
                    tracing::warn!(
                        target: "clawboy::bridge",
                        "skipping decision — no daemon config: {e}"
                    );
                    continue;
                }
            };

            decision_pending = true;
            let tx = decision_tx.clone();

            tokio::spawn(async move {
                let result = match std::panic::AssertUnwindSafe(execute_decision(ctx, config))
                    .catch_unwind()
                    .await
                {
                    Ok(r) => r,
                    Err(panic) => {
                        let msg = crate::panic_detail(&panic);
                        tracing::warn!(
                            target: "clawboy::bridge",
                            "execute_decision panicked: {msg}"
                        );
                        Err(format!("execute_decision panicked: {msg}"))
                    }
                };
                let _ = tx.send(result).await;
            });
        }

        // Drain commentary for logging (chat routing comes in a later
        // task).
        while let Some(commentary) = bridge.take_commentary() {
            tracing::info!(
                target: "clawboy::bridge",
                %commentary,
                "agent commentary"
            );
        }

        // Stream frame to viewers at reduced rate (~15 fps).
        if frame_counter.is_multiple_of(FRAME_STREAM_INTERVAL) {
            let frame_data = emulator.frame_buffer_rgb565();
            if let Err(e) = frame_tx.send(std::sync::Arc::new(frame_data)) {
                tracing::debug!(
                    target: "clawboy::session",
                    "frame broadcast failed (no viewers): {e}"
                );
            }
        }

        // Maintain ~60 fps timing.
        let elapsed = loop_start.elapsed();
        if let Some(remaining) = target_frame_time.checked_sub(elapsed) {
            tokio::time::sleep(remaining).await;
        }
    }

    tracing::info!(
        target: "clawboy::session",
        "tick loop exited"
    );
}

// ── Save state management ───────────────────────────────────────────

/// Writes battery save and rotates save states on shutdown.
///
/// Saves the battery-backed SRAM to `battery.sav` and rotates save
/// state files (`0 -> 1 -> 2`, oldest discarded). All I/O errors are
/// logged but do not prevent shutdown.
fn save_on_shutdown(emulator: &mut Emulator, save_dir: &Path) {
    // Write battery save (in-game save data).
    let battery = emulator.battery_save();
    let battery_path = save_dir.join("battery.sav");
    if let Err(e) = std::fs::write(&battery_path, &battery) {
        tracing::error!(
            target: "clawboy::session",
            path = %battery_path.display(),
            "failed to write battery save: {e}"
        );
    } else {
        tracing::info!(
            target: "clawboy::session",
            bytes = battery.len(),
            "battery save written"
        );
    }

    // Rotate save states: 1 -> 2, 0 -> 1 (oldest discarded).
    let slot2 = save_dir.join("savestate-2.state");
    let slot1 = save_dir.join("savestate-1.state");
    let slot0 = save_dir.join("savestate-0.state");

    let _ = std::fs::rename(&slot1, &slot2);
    let _ = std::fs::rename(&slot0, &slot1);

    // Write new save state to slot 0.
    match emulator.save_state() {
        Ok(state_data) => {
            if let Err(e) = std::fs::write(&slot0, &state_data) {
                tracing::error!(
                    target: "clawboy::session",
                    "failed to write save state slot 0: {e}"
                );
            } else {
                tracing::info!(
                    target: "clawboy::session",
                    bytes = state_data.len(),
                    "save state written to slot 0"
                );
            }
        }
        Err(e) => {
            tracing::error!(
                target: "clawboy::session",
                "failed to create save state: {e}"
            );
        }
    }
}

/// Writes a journal entry and saves the journal on session shutdown.
///
/// Reads the current game state from emulator memory, builds an
/// auto-generated summary (v1: simple "Played for Xm" format), and
/// appends the entry to the journal before writing it to disk. All
/// errors are logged but do not prevent shutdown.
fn save_journal_on_shutdown(
    emulator: &mut Emulator,
    data_dir: &Path,
    journal: &mut super::types::Journal,
    started_iso: &str,
    frame_counter: u64,
) {
    use super::memory_map::read_game_state;

    let state = read_game_state(|addr| emulator.read_memory(addr));
    let play_time_seconds = frame_counter / 60;
    let minutes = play_time_seconds / 60;
    let party_str = journal::format_party_for_journal(&state.party);
    let summary = format!(
        "Played for {minutes}m. Location: {}. Party: {party_str}.",
        state.map_name,
    );

    let ended_iso = chrono::Utc::now().to_rfc3339();
    let entry =
        journal::create_session_entry(started_iso, &ended_iso, summary, &state, play_time_seconds);

    tracing::info!(
        target: "clawboy::journal",
        summary = %entry.summary,
        "writing journal entry"
    );

    journal.sessions.push(entry);

    if let Err(e) = journal::save_journal(data_dir, journal) {
        tracing::error!(
            target: "clawboy::journal",
            "failed to save journal on shutdown: {e}"
        );
    }
}

/// Attempts to load a save state from slots 0, 1, or 2 (in order).
///
/// Tries each save state file as a fallback when no battery save is
/// available. Logs the result but does not fail — the emulator will
/// simply start fresh if all slots are empty or corrupt.
fn try_load_save_state(emulator: &mut Emulator, save_dir: &Path) {
    for slot in 0..3 {
        let path = save_dir.join(format!("savestate-{slot}.state"));
        if let Ok(data) = std::fs::read(&path) {
            match emulator.load_state(&data) {
                Ok(()) => {
                    tracing::info!(
                        target: "clawboy::session",
                        slot,
                        "restored save state"
                    );
                    return;
                }
                Err(e) => {
                    tracing::warn!(
                        target: "clawboy::session",
                        slot,
                        "save state corrupt, trying next: {e}"
                    );
                }
            }
        }
    }
    tracing::info!(
        target: "clawboy::session",
        "no save states found — starting fresh"
    );
}

// ── Local IP detection ──────────────────────────────────────────────

/// Detects the local network IP address via the UDP socket trick.
///
/// Creates a UDP socket and "connects" to a public IP (8.8.8.8:80)
/// without sending any data. The OS fills in the local address it
/// would use to reach that destination, which gives us the LAN IP.
/// Falls back to `127.0.0.1` if detection fails.
fn local_ip() -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .ok()
        .and_then(|s| {
            s.connect("8.8.8.8:80").ok()?;
            s.local_addr().ok()
        })
        .map_or_else(|| "127.0.0.1".to_owned(), |a| a.ip().to_string())
}

// ── Tests ───────────────────────────────────────────────────────────

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn local_ip_returns_non_empty() {
        let ip = local_ip();
        assert!(!ip.is_empty(), "local IP should not be empty");
    }

    #[test]
    fn get_status_idle_when_no_session() {
        // Ensure no session is running.
        let mut guard = lock_session();
        *guard = None;
        drop(guard);

        assert!(matches!(get_status(), ClawBoyStatus::Idle));
    }

    #[test]
    fn save_on_shutdown_creates_files() {
        // This test requires a ROM, so it is gated behind a
        // ROM_PATH env var. Skip in CI.
        let Ok(rom_path) = std::env::var("CLAWBOY_ROM_PATH") else {
            return;
        };

        let rom_data = std::fs::read(&rom_path).unwrap();
        let mut emu = Emulator::new(&rom_data, None).unwrap();

        let tmp = tempfile::tempdir().unwrap();
        let save_dir = tmp.path().join(SAVE_SUBDIR);
        std::fs::create_dir_all(&save_dir).unwrap();

        save_on_shutdown(&mut emu, &save_dir);

        assert!(
            save_dir.join("battery.sav").exists(),
            "battery save should be written"
        );
        assert!(
            save_dir.join("savestate-0.state").exists(),
            "save state slot 0 should be written"
        );
    }

    #[test]
    fn try_load_save_state_handles_missing_gracefully() {
        // Without a ROM we can't construct an Emulator, but we can
        // verify the function doesn't panic on an empty directory.
        let Ok(rom_path) = std::env::var("CLAWBOY_ROM_PATH") else {
            return;
        };

        let rom_data = std::fs::read(&rom_path).unwrap();
        let mut emu = Emulator::new(&rom_data, None).unwrap();

        let tmp = tempfile::tempdir().unwrap();
        let empty_dir = tmp.path().join("empty");
        std::fs::create_dir_all(&empty_dir).unwrap();

        // Should not panic — just logs "no save states found".
        try_load_save_state(&mut emu, &empty_dir);
    }
}
