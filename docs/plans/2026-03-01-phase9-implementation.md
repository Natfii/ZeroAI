# Phase 9: Upstream Gap Closure Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close 13 gaps between the Android wrapper and a normal ZeroClaw install across safety, web access, diagnostics, and scheduling.

**Architecture:** New FFI functions in Rust wrap upstream APIs or read upstream data files. Kotlin Settings UI screens expose existing GlobalTomlConfig fields that ConfigTomlBuilder already emits. Doctor gains channel and trace categories.

**Tech Stack:** Rust (UniFFI 0.29, tokio, serde_json, reqwest), Kotlin (Jetpack Compose, Material 3, Room), Rhai 1.21

**Design doc:** `docs/plans/2026-03-01-phase9-upstream-gap-closure-design.md`

---

## Phase 9A: Safety & Limits

### Task 1: E-Stop FFI Module

**Files:**
- Create: `zeroclaw-android/zeroclaw-ffi/src/estop.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/lib.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/error.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/runtime.rs`

**Step 1: Add EstopEngaged variant to FfiError**

In `error.rs`, add after the `InternalPanic` variant (~line 55):

```rust
    #[error("emergency stop engaged: {detail}")]
    EstopEngaged { detail: String },
```

**Step 2: Create estop.rs module**

```rust
//! Emergency stop (kill-all) for the ZeroClaw daemon.
//!
//! Provides a global atomic flag that blocks all agent execution when engaged.
//! State is persisted to `{data_dir}/estop-state.json` so it survives process death.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use serde::{Deserialize, Serialize};

use crate::error::FfiError;
use crate::types::epoch_ms_now;

/// Global estop flag. Checked at entry of every agent-executing FFI function.
static ESTOP_ENGAGED: AtomicBool = AtomicBool::new(false);

/// JSON-persisted estop state.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct EstopStateFile {
    kill_all: bool,
    #[serde(default)]
    engaged_at: Option<String>,
}

/// UniFFI record returned by [`get_estop_status`].
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiEstopStatus {
    /// Whether the emergency stop is currently engaged.
    pub engaged: bool,
    /// Epoch milliseconds when estop was engaged, if available.
    pub engaged_at_ms: Option<i64>,
}

/// Returns `true` if the emergency stop is currently engaged.
pub(crate) fn is_engaged() -> bool {
    ESTOP_ENGAGED.load(Ordering::Relaxed)
}

/// Loads estop state from disk during daemon startup.
///
/// Called from `runtime::start_daemon_inner()` after config is parsed.
pub(crate) fn load_state(data_dir: &Path) {
    let path = state_path(data_dir);
    if let Ok(contents) = std::fs::read_to_string(&path) {
        if let Ok(state) = serde_json::from_str::<EstopStateFile>(&contents) {
            ESTOP_ENGAGED.store(state.kill_all, Ordering::Relaxed);
            if state.kill_all {
                tracing::warn!("Estop state restored from disk: kill_all=true");
            }
        }
    }
}

/// Engages the emergency stop, cancels active sessions, persists state.
pub(crate) fn engage_estop_inner() -> Result<(), FfiError> {
    ESTOP_ENGAGED.store(true, Ordering::Relaxed);

    let _ = crate::session::session_cancel_inner();

    if let Ok(data_dir) = get_data_dir() {
        let state = EstopStateFile {
            kill_all: true,
            engaged_at: Some(chrono::Utc::now().to_rfc3339()),
        };
        persist_state(&data_dir, &state);
    }

    tracing::warn!("Emergency stop ENGAGED");
    Ok(())
}

/// Returns current estop status.
pub(crate) fn get_estop_status_inner() -> Result<FfiEstopStatus, FfiError> {
    let engaged = ESTOP_ENGAGED.load(Ordering::Relaxed);
    let mut engaged_at_ms = None;

    if engaged {
        if let Ok(data_dir) = get_data_dir() {
            let path = state_path(&data_dir);
            if let Ok(contents) = std::fs::read_to_string(&path) {
                if let Ok(state) = serde_json::from_str::<EstopStateFile>(&contents) {
                    if let Some(ref ts) = state.engaged_at {
                        if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(ts) {
                            engaged_at_ms = Some(dt.timestamp_millis());
                        }
                    }
                }
            }
        }
    }

    Ok(FfiEstopStatus {
        engaged,
        engaged_at_ms,
    })
}

/// Resumes from emergency stop, persists state.
pub(crate) fn resume_estop_inner() -> Result<(), FfiError> {
    ESTOP_ENGAGED.store(false, Ordering::Relaxed);

    if let Ok(data_dir) = get_data_dir() {
        let state = EstopStateFile {
            kill_all: false,
            engaged_at: None,
        };
        persist_state(&data_dir, &state);
    }

    tracing::info!("Emergency stop RESUMED");
    Ok(())
}

fn state_path(data_dir: &Path) -> PathBuf {
    data_dir.join("estop-state.json")
}

fn get_data_dir() -> Result<PathBuf, FfiError> {
    crate::runtime::with_daemon_config(|c| c.workspace_dir.clone())
        .map(|p| p.parent().unwrap_or(&p).to_path_buf())
}

fn persist_state(data_dir: &Path, state: &EstopStateFile) {
    let path = state_path(data_dir);
    if let Ok(json) = serde_json::to_string_pretty(state) {
        let _ = std::fs::write(&path, json);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_engage_resume_cycle() {
        ESTOP_ENGAGED.store(false, Ordering::Relaxed);
        assert!(!is_engaged());

        ESTOP_ENGAGED.store(true, Ordering::Relaxed);
        assert!(is_engaged());

        ESTOP_ENGAGED.store(false, Ordering::Relaxed);
        assert!(!is_engaged());
    }

    #[test]
    fn test_status_when_not_engaged() {
        ESTOP_ENGAGED.store(false, Ordering::Relaxed);
        let status = get_estop_status_inner().unwrap();
        assert!(!status.engaged);
        assert!(status.engaged_at_ms.is_none());
    }

    #[test]
    fn test_estop_state_serialization() {
        let state = EstopStateFile {
            kill_all: true,
            engaged_at: Some("2026-03-01T12:00:00Z".to_string()),
        };
        let json = serde_json::to_string(&state).unwrap();
        let parsed: EstopStateFile = serde_json::from_str(&json).unwrap();
        assert!(parsed.kill_all);
        assert_eq!(parsed.engaged_at.unwrap(), "2026-03-01T12:00:00Z");
    }

    #[test]
    fn test_engage_not_running() {
        let result = engage_estop_inner();
        assert!(result.is_ok());
        assert!(is_engaged());
        ESTOP_ENGAGED.store(false, Ordering::Relaxed);
    }

    #[test]
    fn test_resume_not_running() {
        ESTOP_ENGAGED.store(true, Ordering::Relaxed);
        let result = resume_estop_inner();
        assert!(result.is_ok());
        assert!(!is_engaged());
    }
}
```

**Step 3: Add mod and exports to lib.rs**

Add `mod estop;` to module declarations (~line 20). Add 3 exported functions after `get_version` (~line 285):

```rust
/// Engages the emergency stop, cancelling all active agent execution.
///
/// While engaged, [`send_message`], [`session_send`], and
/// [`send_message_streaming`] return [`FfiError::EstopEngaged`].
/// State is persisted to disk and survives process death.
#[uniffi::export]
pub fn engage_estop() -> Result<(), FfiError> {
    std::panic::catch_unwind(|| estop::engage_estop_inner())
        .unwrap_or_else(|e| {
            Err(FfiError::InternalPanic {
                detail: crate::panic_detail(&e),
            })
        })
}

/// Returns the current emergency stop status.
#[uniffi::export]
pub fn get_estop_status() -> Result<estop::FfiEstopStatus, FfiError> {
    std::panic::catch_unwind(|| estop::get_estop_status_inner())
        .unwrap_or_else(|e| {
            Err(FfiError::InternalPanic {
                detail: crate::panic_detail(&e),
            })
        })
}

/// Resumes from an engaged emergency stop.
#[uniffi::export]
pub fn resume_estop() -> Result<(), FfiError> {
    std::panic::catch_unwind(|| estop::resume_estop_inner())
        .unwrap_or_else(|e| {
            Err(FfiError::InternalPanic {
                detail: crate::panic_detail(&e),
            })
        })
}
```

**Step 4: Add estop guards to messaging functions**

In `lib.rs`, add this check at the top of `send_message` (line ~163), `session_send` (line ~948), and `send_message_streaming` (line ~849), inside the `catch_unwind` closure, before any other logic:

```rust
if crate::estop::is_engaged() {
    return Err(FfiError::EstopEngaged {
        detail: "Emergency stop is engaged. Resume before sending messages.".into(),
    });
}
```

**Step 5: Load estop state on daemon startup**

In `runtime.rs`, in `start_daemon_inner()`, after config is parsed and `data_dir` is resolved (~line 255), add:

```rust
crate::estop::load_state(&data_path);
```

**Step 6: Register REPL functions**

In `repl.rs`, in `build_engine()` after the last registered function (~line 337), add:

```rust
engine.register_fn("estop", || -> String {
    match crate::estop::engage_estop_inner() {
        Ok(()) => "ok".into(),
        Err(e) => format!("error: {e}"),
    }
});

engine.register_fn("estop_status", || -> String {
    match crate::estop::get_estop_status_inner() {
        Ok(s) => serde_json::to_string(&serde_json::json!({
            "engaged": s.engaged,
            "engaged_at_ms": s.engaged_at_ms,
        })).unwrap_or_else(|_| "{}".into()),
        Err(e) => format!("error: {e}"),
    }
});

engine.register_fn("estop_resume", || -> String {
    match crate::estop::resume_estop_inner() {
        Ok(()) => "ok".into(),
        Err(e) => format!("error: {e}"),
    }
});
```

**Step 7: Run tests**

Run: `cd zeroclaw-android && /c/Users/Natal/.cargo/bin/cargo.exe test -p zeroclaw-ffi -- estop`
Expected: All estop tests PASS

**Step 8: Run clippy**

Run: `cd zeroclaw-android && /c/Users/Natal/.cargo/bin/cargo.exe clippy -p zeroclaw-ffi --all-targets -- -D warnings`
Expected: No errors

**Step 9: Commit**

```bash
git add zeroclaw-android/zeroclaw-ffi/src/estop.rs zeroclaw-android/zeroclaw-ffi/src/lib.rs zeroclaw-android/zeroclaw-ffi/src/error.rs zeroclaw-android/zeroclaw-ffi/src/runtime.rs zeroclaw-android/zeroclaw-ffi/src/repl.rs
git commit -m "feat(ffi): add emergency stop (kill-all) with persistence and REPL"
```

---

### Task 2: E-Stop Kotlin UI

**Files:**
- Create: `app/src/main/java/com/zeroclaw/android/data/repository/EstopRepository.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/dashboard/DashboardScreen.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ZeroClawApplication.kt`

**Step 1: Create EstopRepository**

```kotlin
/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import android.util.Log
import com.zeroclaw.ffi.engageEstop
import com.zeroclaw.ffi.getEstopStatus
import com.zeroclaw.ffi.resumeEstop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Repository for emergency stop state, polling the FFI layer.
 *
 * Exposes [engaged] as a [StateFlow] that updates every [POLL_INTERVAL_MS]
 * milliseconds while the daemon is running.
 *
 * @param scope Coroutine scope for the polling loop.
 * @param ioDispatcher Dispatcher for blocking FFI calls.
 */
class EstopRepository(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _engaged = MutableStateFlow(false)

    /** Whether the emergency stop is currently engaged. */
    val engaged: StateFlow<Boolean> = _engaged.asStateFlow()

    private val _engagedAtMs = MutableStateFlow<Long?>(null)

    /** Epoch milliseconds when estop was last engaged. */
    val engagedAtMs: StateFlow<Long?> = _engagedAtMs.asStateFlow()

    /**
     * Starts polling estop status from the FFI layer.
     *
     * Safe to call multiple times; only one polling loop runs.
     */
    fun startPolling() {
        scope.launch(ioDispatcher) {
            while (true) {
                try {
                    val status = getEstopStatus()
                    _engaged.value = status.engaged
                    _engagedAtMs.value = status.engagedAtMs
                } catch (e: Exception) {
                    Log.w(TAG, "Estop poll failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Engages the emergency stop.
     *
     * @return `true` if successfully engaged.
     */
    suspend fun engage(): Boolean =
        withContext(ioDispatcher) {
            try {
                engageEstop()
                _engaged.value = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to engage estop: ${e.message}")
                false
            }
        }

    /**
     * Resumes from the emergency stop.
     *
     * @return `true` if successfully resumed.
     */
    suspend fun resume(): Boolean =
        withContext(ioDispatcher) {
            try {
                resumeEstop()
                _engaged.value = false
                _engagedAtMs.value = null
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume estop: ${e.message}")
                false
            }
        }

    /** Constants for [EstopRepository]. */
    companion object {
        private const val TAG = "EstopRepository"
        private const val POLL_INTERVAL_MS = 2000L
    }
}
```

**Step 2: Add to ZeroClawApplication**

In `ZeroClawApplication.kt`, add property alongside other repositories:

```kotlin
/** Emergency stop state repository. */
val estopRepository: EstopRepository by lazy {
    EstopRepository(scope = applicationScope)
}
```

**Step 3: Add E-Stop button and banner to DashboardScreen**

In `DashboardScreen.kt`, add an E-Stop section at the top of the content area. This involves:
- Collecting `estopEngaged` state from `app.estopRepository.engaged`
- When not engaged: red `FilledTonalButton` with "Emergency Stop" label
- When engaged: full-width `Card` with `errorContainer` color and "Emergency stop active" message + "Resume" button
- Resume button: if `KeyguardManager.isDeviceSecure` is true, launch `createConfirmDeviceCredentialIntent()`, otherwise show confirmation dialog

The exact composable implementation follows the existing Dashboard patterns. Place it before the service status section.

**Step 4: Run lint and format**

Run: `./gradlew spotlessApply && ./gradlew detekt`

**Step 5: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/data/repository/EstopRepository.kt
git add app/src/main/java/com/zeroclaw/android/ui/screen/dashboard/DashboardScreen.kt
git add app/src/main/java/com/zeroclaw/android/ZeroClawApplication.kt
git commit -m "feat(ui): add emergency stop button to dashboard with device credential resume"
```

---

### Task 3: Resource Limits Settings UI

**Files:**
- Create: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/security/ResourceLimitsScreen.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/navigation/Route.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/navigation/ZeroClawNavHost.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/navigation/SettingsNavAction.kt`

**Step 1: Check existing state**

The fields already exist in:
- `GlobalTomlConfig` (lines 270-273): `securityResourcesMaxMemoryMb`, `securityResourcesMaxCpuTimeSecs`, `securityResourcesMaxSubprocesses`, `securityResourcesMemoryMonitoring`
- `SettingsRepository` (lines 533-540): `setSecurityResourcesMaxMemoryMb()`, `setSecurityResourcesMaxCpuTimeSecs()`, `setSecurityResourcesMaxSubprocesses()`, `setSecurityResourcesMemoryMonitoring()`
- `ConfigTomlBuilder.appendSecurityResourcesSection()` (lines 1049-1067): emission
- `SecurityAdvancedScreen.kt` already has a Resources section

**Step 2: Verify SecurityAdvancedScreen already has these fields**

Read `SecurityAdvancedScreen.kt` to check if resource limit fields are already rendered. If they are, this task becomes: verify the UI works and the SettingsViewModel has update methods wired.

If SecurityAdvancedScreen already has resource limit inputs, this task is **already complete** — just verify and move on.

If not, create a `ResourceLimitsSection` private composable in `SecurityAdvancedScreen.kt` with:
- `OutlinedTextField` for Max Memory (MB), type Number
- `OutlinedTextField` for Max CPU Time (seconds), type Number
- `OutlinedTextField` for Max Subprocesses, type Number
- `SettingsToggleRow` for Memory Monitoring

Follow the exact pattern from existing sections in `SecurityAdvancedScreen.kt`.

**Step 3: Add SettingsViewModel update methods (if missing)**

Check `SettingsViewModel.kt` for methods like `updateSecurityResourcesMaxMemoryMb()`. If missing, add them following the `updateDaemonSetting{}` pattern.

**Step 4: Commit (if changes were needed)**

```bash
git commit -m "feat(ui): add resource limits to security advanced screen"
```

---

### Task 4: OTP Gating Settings UI

**Same investigation pattern as Task 3.** `SecurityAdvancedScreen.kt` may already render OTP fields since `SettingsRepository` has all the OTP setters (lines 541-559) and `GlobalTomlConfig` has all OTP fields (lines 275-281).

**Step 1: Verify existing UI**

Read `SecurityAdvancedScreen.kt` and check for OTP section. If it exists with toggle, method dropdown, TTL fields, and gated actions list, this task is already complete.

**Step 2: If missing, add OTP section**

Add a private composable with:
- Switch: OTP Enabled
- Dropdown: Method (only "totp" — Pairing and CliPrompt are future-reserved)
- Number fields: Token TTL, Cache validity
- Chip/text input: Gated Actions (comma-separated string stored in repo)
- Info text: "When enabled, device PIN confirmation is required for gated tool actions" (if `KeyguardManager.isDeviceSecure`)

**Step 3: Commit**

```bash
git commit -m "feat(ui): add OTP gating configuration to security advanced screen"
```

---

### Task 5: Doctor Channel Checks

**Files:**
- Modify: `app/src/main/java/com/zeroclaw/android/model/DiagnosticCheck.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/service/DoctorValidator.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/doctor/DoctorViewModel.kt`

**Step 1: Add CHANNELS category to DiagnosticCategory**

In `DiagnosticCheck.kt` (line 44), add before `SYSTEM`:

```kotlin
    /** Channel connectivity and health checks. */
    CHANNELS,
```

**Step 2: Add runChannelChecks() to DoctorValidator**

After `runDaemonHealthChecks()` (~line 172), add:

```kotlin
    /**
     * Checks channel connectivity via the FFI [doctorChannels] function.
     *
     * @param configToml The current TOML config string.
     * @param dataDir The daemon data directory path.
     * @return List of diagnostic checks for the channels category.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun runChannelChecks(
        configToml: String,
        dataDir: String,
    ): List<DiagnosticCheck> =
        try {
            val json = withContext(ioDispatcher) {
                com.zeroclaw.ffi.doctorChannels(configToml, dataDir)
            }
            parseChannelDiagnostics(json)
        } catch (e: Exception) {
            listOf(
                DiagnosticCheck(
                    id = "channels-error",
                    category = DiagnosticCategory.CHANNELS,
                    title = "Channel diagnostics",
                    status = CheckStatus.FAIL,
                    detail = "Failed to run channel checks: ${e.message}",
                ),
            )
        }
```

Add private parser:

```kotlin
    private fun parseChannelDiagnostics(json: String): List<DiagnosticCheck> {
        val array = org.json.JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val name = obj.optString("channel", "unknown")
            val healthy = obj.optBoolean("healthy", false)
            val error = obj.optString("error", "")
            DiagnosticCheck(
                id = "channel-$name",
                category = DiagnosticCategory.CHANNELS,
                title = "Channel: $name",
                status = if (healthy) CheckStatus.PASS else CheckStatus.FAIL,
                detail = if (healthy) "Connected" else error.ifBlank { "Not responding" },
            )
        }
    }
```

**Step 3: Add channel checks to DoctorViewModel.runAllChecks()**

In `DoctorViewModel.kt`, after `daemonChecks` block (~line 92), add:

```kotlin
            val channelChecks = validator.runChannelChecks(
                configToml = buildCurrentToml(),
                dataDir = app.filesDir.absolutePath,
            )
            accumulated.addAll(channelChecks)
            _checks.value = accumulated.toList()
```

Add private helper `buildCurrentToml()` that builds the TOML from current settings (reuse existing `ConfigTomlBuilder` pattern from `ZeroClawDaemonService`).

**Step 4: Run tests, commit**

```bash
git commit -m "feat(doctor): add channel health diagnostics category"
```

---

### Task 6: Runtime Traces FFI + Doctor Integration

**Files:**
- Create: `zeroclaw-android/zeroclaw-ffi/src/traces.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/lib.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/repl.rs`
- Modify: `app/src/main/java/com/zeroclaw/android/model/DiagnosticCheck.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/service/DoctorValidator.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/doctor/DoctorViewModel.kt`

**Step 1: Create traces.rs**

```rust
//! Runtime trace reader for the `ZeroClaw` daemon.
//!
//! Reads JSONL trace events from the workspace state directory.
//! Upstream stores traces at `{workspace}/state/runtime-trace.jsonl`
//! (see `zeroclaw/src/observability/runtime_trace.rs`).

use crate::error::FfiError;
use crate::runtime;

/// Queries runtime trace events from the JSONL file.
///
/// # Arguments
/// * `filter` - Optional case-insensitive substring match on message/payload
/// * `event_type` - Optional exact match on event_type field
/// * `limit` - Maximum number of events to return (newest first)
pub(crate) fn query_traces_inner(
    filter: Option<String>,
    event_type: Option<String>,
    limit: u32,
) -> Result<String, FfiError> {
    let config = runtime::clone_daemon_config()?;
    let trace_path = config.workspace_dir.join("state").join("runtime-trace.jsonl");

    if !trace_path.exists() {
        return Ok("[]".to_string());
    }

    let contents = std::fs::read_to_string(&trace_path).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to read trace file: {e}"),
    })?;

    let filter_lower = filter.as_deref().map(str::to_lowercase);
    let limit = limit as usize;

    let events: Vec<serde_json::Value> = contents
        .lines()
        .filter_map(|line| serde_json::from_str::<serde_json::Value>(line).ok())
        .filter(|ev| {
            if let Some(ref et) = event_type {
                if ev.get("event_type").and_then(|v| v.as_str()) != Some(et.as_str()) {
                    return false;
                }
            }
            if let Some(ref f) = filter_lower {
                let msg = ev
                    .get("message")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_lowercase();
                let payload = ev.get("payload").map(|v| v.to_string().to_lowercase()).unwrap_or_default();
                if !msg.contains(f.as_str()) && !payload.contains(f.as_str()) {
                    return false;
                }
            }
            true
        })
        .collect();

    let total = events.len();
    let start = total.saturating_sub(limit);
    let result: Vec<&serde_json::Value> = events[start..].iter().collect();

    serde_json::to_string(&result).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to serialize traces: {e}"),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_query_traces_not_running() {
        let result = query_traces_inner(None, None, 10);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { .. } => {}
            other => panic!("expected StateError, got {other:?}"),
        }
    }
}
```

**Step 2: Add export to lib.rs**

Add `mod traces;` and exported function:

```rust
/// Queries runtime trace events from the daemon's JSONL trace file.
///
/// Returns a JSON array of trace event objects, newest last.
/// Returns `"[]"` if tracing is disabled or no events match.
///
/// # Arguments
/// * `filter` - Optional case-insensitive substring match on message/payload.
/// * `event_type` - Optional exact match on event_type (e.g. "tool_call", "model_reply").
/// * `limit` - Maximum events to return.
#[uniffi::export]
pub fn query_runtime_traces(
    filter: Option<String>,
    event_type: Option<String>,
    limit: u32,
) -> Result<String, FfiError> {
    std::panic::catch_unwind(|| traces::query_traces_inner(filter, event_type, limit))
        .unwrap_or_else(|e| {
            Err(FfiError::InternalPanic {
                detail: crate::panic_detail(&e),
            })
        })
}
```

**Step 3: Register REPL functions**

```rust
engine.register_fn("traces", |limit: i64| -> String {
    match crate::traces::query_traces_inner(None, None, limit as u32) {
        Ok(json) => json,
        Err(e) => format!("error: {e}"),
    }
});

engine.register_fn("traces_filter", |filter: String, limit: i64| -> String {
    match crate::traces::query_traces_inner(Some(filter), None, limit as u32) {
        Ok(json) => json,
        Err(e) => format!("error: {e}"),
    }
});
```

**Step 4: Add RUNTIME_TRACES category to DiagnosticCategory**

In `DiagnosticCheck.kt`, add after `CHANNELS`:

```kotlin
    /** Runtime trace analysis for error detection. */
    RUNTIME_TRACES,
```

**Step 5: Add runTraceChecks() to DoctorValidator**

```kotlin
    /**
     * Checks for recent error events in runtime traces.
     *
     * @return Diagnostic checks for the runtime traces category.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun runTraceChecks(): List<DiagnosticCheck> =
        try {
            val json = withContext(ioDispatcher) {
                com.zeroclaw.ffi.queryRuntimeTraces("error", null, 5u)
            }
            val array = org.json.JSONArray(json)
            if (array.length() == 0) {
                listOf(
                    DiagnosticCheck(
                        id = "traces-errors",
                        category = DiagnosticCategory.RUNTIME_TRACES,
                        title = "Recent errors",
                        status = CheckStatus.PASS,
                        detail = "No error events in runtime traces",
                    ),
                )
            } else {
                val latest = array.getJSONObject(array.length() - 1)
                val msg = latest.optString("message", "Unknown error")
                listOf(
                    DiagnosticCheck(
                        id = "traces-errors",
                        category = DiagnosticCategory.RUNTIME_TRACES,
                        title = "Recent errors",
                        status = CheckStatus.WARN,
                        detail = "${array.length()} error event(s). Latest: $msg",
                    ),
                )
            }
        } catch (e: Exception) {
            listOf(
                DiagnosticCheck(
                    id = "traces-errors",
                    category = DiagnosticCategory.RUNTIME_TRACES,
                    title = "Runtime traces",
                    status = CheckStatus.PASS,
                    detail = "Tracing not available: ${e.message}",
                ),
            )
        }
```

**Step 6: Add to DoctorViewModel.runAllChecks()**

After channel checks, before system checks:

```kotlin
            val traceChecks = validator.runTraceChecks()
            accumulated.addAll(traceChecks)
            _checks.value = accumulated.toList()
```

**Step 7: Run all tests, commit**

```bash
git commit -m "feat(ffi,doctor): add runtime trace querying and doctor integration"
```

---

## Phase 9B: Web & Config Access

### Task 7: Web Access Settings Screen

**Files:**
- Create: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/web/WebAccessScreen.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/navigation/Route.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/navigation/ZeroClawNavHost.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/navigation/SettingsNavAction.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/SettingsViewModel.kt`

**Step 1: Check if SettingsViewModel already has web access update methods**

The `SettingsRepository` may already have `setWebFetchEnabled()`, `setWebSearchEnabled()`, `setHttpRequestEnabled()` methods. Check first. If present, the SettingsViewModel just needs delegation methods.

**Step 2: Create WebAccessScreen.kt**

Follow the `SecurityAdvancedScreen.kt` pattern: takes `edgeMargin`, `settingsViewModel`, `modifier`. Three collapsible sections:

1. **Web Fetch** — switch (enabled), chip inputs (allowed/blocked domains), number fields (max response size, timeout)
2. **Web Search** — switch (enabled), dropdown (duckduckgo/brave), text field (Brave API key, shown when brave selected), number fields (max results, timeout)
3. **HTTP Request** — switch (enabled), chip input (allowed domains, required), note about deny-by-default

Each field calls the corresponding `settingsViewModel.updateXxx()` method.

**Step 3: Add route, nav action, destination**

- `Route.kt`: add `@Serializable data object WebAccessRoute`
- `SettingsNavAction.kt`: add `data object WebAccess : SettingsNavAction`
- `ZeroClawNavHost.kt`: add composable destination
- `SettingsScreen.kt`: add "Web Access" `SettingsListItem` in Network section after Tunnel (~line 225)

**Step 4: Run lint, commit**

```bash
git commit -m "feat(ui): add web access settings screen (web_fetch, web_search, http_request)"
```

---

### Task 8: Multimodal & Vision Settings

**Step 1: Check if SecurityAdvancedScreen or another screen already has multimodal fields**

The `GlobalTomlConfig` has `multimodalMaxImages`, `multimodalMaxImageSizeMb`, `multimodalAllowRemoteFetch`. Check if any existing screen renders these.

**Step 2: If not rendered, add to an existing screen**

Add a "Vision" section to `SecurityAdvancedScreen.kt` (or create a small inline expandable in SettingsScreen). Three fields:
- Slider: Max Images (1-16)
- Slider: Max Image Size MB (1-20)
- Switch: Allow Remote Fetch

**Step 3: Commit**

```bash
git commit -m "feat(ui): add multimodal/vision settings"
```

---

### Task 9: Config Read API

**Files:**
- Modify: `zeroclaw-android/zeroclaw-ffi/src/lib.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/runtime.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/repl.rs`

**Step 1: Add get_running_config_inner to runtime.rs**

```rust
/// Returns the TOML representation of the currently running daemon config.
pub(crate) fn get_running_config_inner() -> Result<String, FfiError> {
    with_daemon_config(|config| {
        toml::to_string_pretty(config).map_err(|e| FfiError::SpawnError {
            detail: format!("failed to serialize config: {e}"),
        })
    })?
}
```

**Step 2: Add export to lib.rs**

```rust
/// Returns the TOML config the running daemon was started with.
///
/// Useful for verifying the daemon's active configuration matches
/// what the Kotlin layer expects.
#[uniffi::export]
pub fn get_running_config() -> Result<String, FfiError> {
    std::panic::catch_unwind(|| runtime::get_running_config_inner())
        .unwrap_or_else(|e| {
            Err(FfiError::InternalPanic {
                detail: crate::panic_detail(&e),
            })
        })
}
```

**Step 3: Register REPL function**

```rust
engine.register_fn("config", || -> String {
    match crate::runtime::get_running_config_inner() {
        Ok(toml) => toml,
        Err(e) => format!("error: {e}"),
    }
});
```

**Step 4: Test, commit**

```bash
git commit -m "feat(ffi): add get_running_config for daemon config introspection"
```

---

## Phase 9C: Diagnostics & Auth

### Task 10: Auth Profile Management FFI

**Files:**
- Create: `zeroclaw-android/zeroclaw-ffi/src/auth_profiles.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/lib.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/repl.rs`

**Step 1: Create auth_profiles.rs**

This module reads `auth-profiles.json` from the workspace state directory. Upstream format: `AuthProfilesData` with `schema_version`, `active_profiles`, `profiles` maps (see `zeroclaw/src/auth/profiles.rs`).

```rust
//! Auth profile management for reading, refreshing, and removing OAuth/token profiles.
//!
//! Reads the upstream `auth-profiles.json` file from the daemon's workspace.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

use crate::error::FfiError;
use crate::runtime;

/// UniFFI record for an auth profile.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAuthProfile {
    /// Profile ID (format: "provider:profile_name").
    pub id: String,
    /// Provider name (e.g. "openai-codex", "gemini").
    pub provider: String,
    /// Profile display name.
    pub profile_name: String,
    /// Kind: "oauth" or "token".
    pub kind: String,
    /// Whether this is the active profile for its provider.
    pub is_active: bool,
    /// Token expiry as epoch milliseconds, if available.
    pub expires_at_ms: Option<i64>,
    /// Profile creation time as epoch milliseconds.
    pub created_at_ms: i64,
    /// Last update time as epoch milliseconds.
    pub updated_at_ms: i64,
}

#[derive(Debug, Deserialize)]
struct ProfilesFile {
    #[serde(default)]
    active_profiles: BTreeMap<String, String>,
    #[serde(default)]
    profiles: BTreeMap<String, ProfileEntry>,
}

#[derive(Debug, Deserialize)]
struct ProfileEntry {
    id: String,
    provider: String,
    profile_name: String,
    kind: String,
    #[serde(default)]
    token_set: Option<TokenSetEntry>,
    created_at: String,
    updated_at: String,
}

#[derive(Debug, Deserialize)]
struct TokenSetEntry {
    #[serde(default)]
    expires_at: Option<String>,
}

pub(crate) fn list_auth_profiles_inner() -> Result<Vec<FfiAuthProfile>, FfiError> {
    let config = runtime::clone_daemon_config()?;
    let path = config.workspace_dir.join("auth-profiles.json");

    if !path.exists() {
        return Ok(vec![]);
    }

    let contents = std::fs::read_to_string(&path).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to read auth-profiles.json: {e}"),
    })?;

    let data: ProfilesFile = serde_json::from_str(&contents).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to parse auth-profiles.json: {e}"),
    })?;

    let profiles = data
        .profiles
        .values()
        .map(|p| {
            let is_active = data
                .active_profiles
                .get(&p.provider)
                .map(|active_id| active_id == &p.id)
                .unwrap_or(false);

            let expires_at_ms = p
                .token_set
                .as_ref()
                .and_then(|ts| ts.expires_at.as_deref())
                .and_then(|s| chrono::DateTime::parse_from_rfc3339(s).ok())
                .map(|dt| dt.timestamp_millis());

            let created_at_ms = chrono::DateTime::parse_from_rfc3339(&p.created_at)
                .map(|dt| dt.timestamp_millis())
                .unwrap_or(0);

            let updated_at_ms = chrono::DateTime::parse_from_rfc3339(&p.updated_at)
                .map(|dt| dt.timestamp_millis())
                .unwrap_or(0);

            FfiAuthProfile {
                id: p.id.clone(),
                provider: p.provider.clone(),
                profile_name: p.profile_name.clone(),
                kind: p.kind.clone(),
                is_active,
                expires_at_ms,
                created_at_ms,
                updated_at_ms,
            }
        })
        .collect();

    Ok(profiles)
}

pub(crate) fn remove_auth_profile_inner(
    provider: String,
    profile_name: String,
) -> Result<(), FfiError> {
    let config = runtime::clone_daemon_config()?;
    let path = config.workspace_dir.join("auth-profiles.json");

    if !path.exists() {
        return Err(FfiError::StateError {
            detail: "auth-profiles.json not found".into(),
        });
    }

    let contents = std::fs::read_to_string(&path).map_err(|e| FfiError::SpawnError {
        detail: format!("read error: {e}"),
    })?;

    let mut data: serde_json::Value =
        serde_json::from_str(&contents).map_err(|e| FfiError::SpawnError {
            detail: format!("parse error: {e}"),
        })?;

    let profile_id = format!("{}:{}", provider.trim(), profile_name.trim());

    if let Some(profiles) = data.get_mut("profiles").and_then(|v| v.as_object_mut()) {
        profiles.remove(&profile_id);
    }

    if let Some(active) = data.get_mut("active_profiles").and_then(|v| v.as_object_mut()) {
        if active.get(&provider).and_then(|v| v.as_str()) == Some(&profile_id) {
            active.remove(&provider);
        }
    }

    let json = serde_json::to_string_pretty(&data).map_err(|e| FfiError::SpawnError {
        detail: format!("serialize error: {e}"),
    })?;

    std::fs::write(&path, json).map_err(|e| FfiError::SpawnError {
        detail: format!("write error: {e}"),
    })?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_list_profiles_not_running() {
        let result = list_auth_profiles_inner();
        assert!(result.is_err());
    }

    #[test]
    fn test_remove_profile_not_running() {
        let result = remove_auth_profile_inner("openai".into(), "default".into());
        assert!(result.is_err());
    }
}
```

**Step 2: Add exports to lib.rs and REPL registrations**

Follow the established pattern: 3 exported functions + 3 REPL registrations.

Note: `refresh_auth_token` is deferred — it requires calling the provider's OAuth token endpoint, which is complex. The list and remove functions provide immediate value. Token refresh can be added when the Kotlin-side `OpenAiOAuthManager` is extended.

**Step 3: Test, commit**

```bash
git commit -m "feat(ffi): add auth profile listing and removal"
```

---

### Task 11: Auth Profiles Kotlin UI

**Files:**
- Create: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/apikeys/AuthProfilesScreen.kt`
- Create: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/apikeys/AuthProfilesViewModel.kt`
- Modify: navigation files (Route, NavHost, SettingsNavAction)
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/SettingsScreen.kt`

Follow the `CronJobsScreen` pattern: ViewModel with `UiState` sealed interface, LazyColumn of profile cards, delete action with confirmation dialog.

**Step 1: Create ViewModel, Step 2: Create Screen, Step 3: Wire navigation, Step 4: Commit**

```bash
git commit -m "feat(ui): add auth profiles management screen"
```

---

### Task 12: Model Discovery FFI + UI

**Files:**
- Create: `zeroclaw-android/zeroclaw-ffi/src/models.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/lib.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/repl.rs`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/agents/AgentDetailScreen.kt`

**Step 1: Create models.rs**

```rust
//! Model discovery by querying provider APIs.

use crate::error::FfiError;

/// Discovers available models from a provider's API.
///
/// # Providers
/// - OpenAI/OpenRouter/Compatible: `GET /v1/models`
/// - Ollama: `GET /api/tags`
/// - Anthropic: returns hardcoded known models
pub(crate) fn discover_models_inner(
    provider: String,
    api_key: String,
    base_url: Option<String>,
) -> Result<String, FfiError> {
    let runtime = crate::runtime::get_or_create_runtime()?;

    runtime.block_on(async {
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(10))
            .build()
            .map_err(|e| FfiError::SpawnError {
                detail: format!("http client error: {e}"),
            })?;

        match provider.as_str() {
            "anthropic" => Ok(anthropic_models()),
            "ollama" => {
                let url = base_url.unwrap_or_else(|| "http://localhost:11434".into());
                fetch_ollama_models(&client, &url).await
            }
            _ => {
                let url = base_url.unwrap_or_else(|| default_base_url(&provider));
                fetch_openai_models(&client, &url, &api_key).await
            }
        }
    })
}

async fn fetch_openai_models(
    client: &reqwest::Client,
    base_url: &str,
    api_key: &str,
) -> Result<String, FfiError> {
    let url = format!("{}/v1/models", base_url.trim_end_matches('/'));
    let resp = client
        .get(&url)
        .header("Authorization", format!("Bearer {api_key}"))
        .send()
        .await
        .map_err(|e| FfiError::SpawnError {
            detail: format!("model discovery failed: {e}"),
        })?;

    let body: serde_json::Value = resp.json().await.map_err(|e| FfiError::SpawnError {
        detail: format!("parse error: {e}"),
    })?;

    let models: Vec<serde_json::Value> = body
        .get("data")
        .and_then(|d| d.as_array())
        .map(|arr| {
            arr.iter()
                .map(|m| {
                    serde_json::json!({
                        "id": m.get("id").and_then(|v| v.as_str()).unwrap_or(""),
                        "name": m.get("id").and_then(|v| v.as_str()).unwrap_or(""),
                    })
                })
                .collect()
        })
        .unwrap_or_default();

    serde_json::to_string(&models).map_err(|e| FfiError::SpawnError {
        detail: format!("serialize error: {e}"),
    })
}

async fn fetch_ollama_models(
    client: &reqwest::Client,
    base_url: &str,
) -> Result<String, FfiError> {
    let url = format!("{}/api/tags", base_url.trim_end_matches('/'));
    let resp = client.get(&url).send().await.map_err(|e| FfiError::SpawnError {
        detail: format!("ollama discovery failed: {e}"),
    })?;

    let body: serde_json::Value = resp.json().await.map_err(|e| FfiError::SpawnError {
        detail: format!("parse error: {e}"),
    })?;

    let models: Vec<serde_json::Value> = body
        .get("models")
        .and_then(|m| m.as_array())
        .map(|arr| {
            arr.iter()
                .map(|m| {
                    serde_json::json!({
                        "id": m.get("name").and_then(|v| v.as_str()).unwrap_or(""),
                        "name": m.get("name").and_then(|v| v.as_str()).unwrap_or(""),
                    })
                })
                .collect()
        })
        .unwrap_or_default();

    serde_json::to_string(&models).map_err(|e| FfiError::SpawnError {
        detail: format!("serialize error: {e}"),
    })
}

fn anthropic_models() -> String {
    serde_json::to_string(&serde_json::json!([
        {"id": "claude-opus-4-20250514", "name": "Claude Opus 4"},
        {"id": "claude-sonnet-4-20250514", "name": "Claude Sonnet 4"},
        {"id": "claude-haiku-4-20250506", "name": "Claude Haiku 4"},
        {"id": "claude-3-5-sonnet-20241022", "name": "Claude 3.5 Sonnet"},
    ]))
    .unwrap_or_else(|_| "[]".into())
}

fn default_base_url(provider: &str) -> String {
    match provider {
        "openai" | "openai-codex" => "https://api.openai.com".into(),
        "openrouter" => "https://openrouter.ai/api".into(),
        _ => "https://api.openai.com".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_anthropic_models_returns_json() {
        let result = anthropic_models();
        let parsed: Vec<serde_json::Value> = serde_json::from_str(&result).unwrap();
        assert!(!parsed.is_empty());
        assert!(parsed[0].get("id").is_some());
    }

    #[test]
    fn test_default_base_urls() {
        assert!(default_base_url("openai").contains("openai.com"));
        assert!(default_base_url("openrouter").contains("openrouter.ai"));
    }
}
```

**Step 2: Add export, REPL, wire to agent detail model dropdown refresh**

**Step 3: Commit**

```bash
git commit -m "feat(ffi): add model discovery via provider APIs"
```

---

## Phase 9D: Scheduling & Media

### Task 13: Cron add-at / add-every FFI

**Files:**
- Modify: `zeroclaw-android/zeroclaw-ffi/src/cron.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/lib.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/repl.rs`

**Step 1: Add inner functions to cron.rs**

Follow the existing `add_cron_job_inner` and `add_one_shot_job_inner` patterns:

```rust
/// Adds a one-shot cron job at a specific RFC3339 timestamp.
pub(crate) fn add_cron_job_at_inner(
    timestamp_rfc3339: String,
    command: String,
) -> Result<FfiCronJob, FfiError> {
    let body = serde_json::json!({
        "schedule": { "kind": "at", "at": timestamp_rfc3339 },
        "command": command,
    });
    let resp = crate::gateway_client::gateway_post("/api/cron", &body)?;
    Ok(parse_job_json(&resp))
}

/// Adds a fixed-interval repeating cron job.
pub(crate) fn add_cron_job_every_inner(
    interval_ms: u64,
    command: String,
) -> Result<FfiCronJob, FfiError> {
    let body = serde_json::json!({
        "schedule": { "kind": "every", "every_ms": interval_ms },
        "command": command,
    });
    let resp = crate::gateway_client::gateway_post("/api/cron", &body)?;
    Ok(parse_job_json(&resp))
}
```

**Step 2: Add exports to lib.rs**

```rust
/// Adds a one-shot cron job that fires at a specific RFC3339 timestamp.
#[uniffi::export]
pub fn add_cron_job_at(
    timestamp_rfc3339: String,
    command: String,
) -> Result<cron::FfiCronJob, FfiError> {
    std::panic::catch_unwind(|| cron::add_cron_job_at_inner(timestamp_rfc3339, command))
        .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: crate::panic_detail(&e) }))
}

/// Adds a fixed-interval repeating cron job (interval in milliseconds).
#[uniffi::export]
pub fn add_cron_job_every(
    interval_ms: u64,
    command: String,
) -> Result<cron::FfiCronJob, FfiError> {
    std::panic::catch_unwind(|| cron::add_cron_job_every_inner(interval_ms, command))
        .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: crate::panic_detail(&e) }))
}
```

**Step 3: Register REPL functions**

```rust
engine.register_fn("cron_add_at", |timestamp: String, command: String| -> String {
    match crate::cron::add_cron_job_at_inner(timestamp, command) {
        Ok(job) => serde_json::to_string(&serde_json::json!({"id": job.id}))
            .unwrap_or_else(|_| "{}".into()),
        Err(e) => format!("error: {e}"),
    }
});

engine.register_fn("cron_add_every", |ms: i64, command: String| -> String {
    match crate::cron::add_cron_job_every_inner(ms as u64, command) {
        Ok(job) => serde_json::to_string(&serde_json::json!({"id": job.id}))
            .unwrap_or_else(|_| "{}".into()),
        Err(e) => format!("error: {e}"),
    }
});
```

**Step 4: Expand AddCronJobDialog in CronJobsScreen.kt**

Add two new modes to the existing `SegmentedButton`:
- "At Time" mode: shows `DatePicker` + `TimePicker`, formats to RFC3339, calls `onAddAt(timestamp, command)`
- "Interval" mode: shows number input (milliseconds) with helper text showing "every X min", calls `onAddEvery(ms, command)`

Add callbacks `onAddAt` and `onAddEvery` to `AddCronJobDialog` signature. Update `CronJobsViewModel` with `addJobAt()` and `addJobEvery()` methods.

**Step 5: Tests, commit**

```bash
git commit -m "feat(ffi,ui): add cron add-at and add-every with UI support"
```

---

### Task 14: Transcription Settings Screen

**Step 1: Verify existing state**

`GlobalTomlConfig` fields (lines 242-246) and `ConfigTomlBuilder.appendTranscriptionSection()` (lines 892-903) already exist. Check if any existing screen renders these.

**Step 2: Create TranscriptionScreen.kt (if not already rendered)**

Follow `SecurityAdvancedScreen` pattern:
- Switch: Enabled
- Text field: API URL
- Dropdown: Model
- Text field: Language hint
- Number field: Max Duration (seconds)

**Step 3: Wire navigation, commit**

```bash
git commit -m "feat(ui): add transcription/voice input settings screen"
```

---

### Task 15: Provider Hot-Swap FFI + Banner Cleanup

**Files:**
- Modify: `zeroclaw-android/zeroclaw-ffi/src/lib.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/runtime.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/repl.rs`
- Modify: `app/src/main/java/com/zeroclaw/android/service/DaemonServiceBridge.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/agents/AgentDetailViewModel.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/component/RestartRequiredBanner.kt`

**Step 1: Add swap_provider_inner to runtime.rs**

```rust
/// Hot-swaps the default provider and model in the running daemon config.
///
/// Mutates `DaemonState.config` in-place without restarting the daemon.
pub(crate) fn swap_provider_inner(
    provider: String,
    model: String,
    api_key: Option<String>,
) -> Result<(), FfiError> {
    let mut guard = lock_daemon();
    let state = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "daemon not running".into(),
    })?;

    state.config.default_provider = Some(provider);
    state.config.default_model = Some(model);
    if let Some(key) = api_key {
        state.config.api_key = Some(key);
    }

    tracing::info!("Provider hot-swapped to {:?}/{:?}",
        state.config.default_provider, state.config.default_model);
    Ok(())
}
```

**Step 2: Add export to lib.rs**

```rust
/// Hot-swaps the default provider and model without restarting the daemon.
///
/// The change takes effect on the next message send. Does not persist
/// to disk; the Kotlin layer is responsible for persisting the setting
/// and rebuilding the TOML on next full restart.
#[uniffi::export]
pub fn swap_provider(
    provider: String,
    model: String,
    api_key: Option<String>,
) -> Result<(), FfiError> {
    std::panic::catch_unwind(|| runtime::swap_provider_inner(provider, model, api_key))
        .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: crate::panic_detail(&e) }))
}
```

**Step 3: Register REPL function**

```rust
engine.register_fn("swap_provider", |provider: String, model: String| -> String {
    match crate::runtime::swap_provider_inner(provider, model, None) {
        Ok(()) => "ok".into(),
        Err(e) => format!("error: {e}"),
    }
});
```

**Step 4: Add swapProvider to DaemonServiceBridge.kt**

```kotlin
    /**
     * Hot-swaps the default provider and model in the running daemon.
     *
     * On success, clears [restartRequired] since the change is already live.
     * On failure, falls back to marking restart required.
     *
     * @param provider Provider ID (e.g. "anthropic", "openai").
     * @param model Model ID (e.g. "claude-sonnet-4-20250514").
     * @param apiKey Optional API key override.
     * @return `true` if hot-swap succeeded.
     */
    suspend fun swapProvider(
        provider: String,
        model: String,
        apiKey: String? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                com.zeroclaw.ffi.swapProvider(provider, model, apiKey)
                _restartRequired.value = false
                true
            } catch (e: Exception) {
                Log.w(TAG, "Hot-swap failed, falling back to restart: ${e.message}")
                markRestartRequired()
                false
            }
        }
```

**Step 5: Update AgentDetailViewModel save handler**

In `AgentDetailViewModel`, when saving an agent, check if only provider/model changed:
- If yes: call `daemonBridge.swapProvider(provider, model, apiKey)` instead of `markRestartRequired()`
- If other fields changed (channels, system prompt, etc.): still call `markRestartRequired()`

**Step 6: Update RestartRequiredBanner text**

In `RestartRequiredBanner.kt` (line 64), change:
```kotlin
text = "Restart daemon to apply configuration changes",
```

**Step 7: Update test assertions**

In `SettingsScreenTest.kt`, update the string assertion from "Restart daemon to apply changes" to "Restart daemon to apply configuration changes".

**Step 8: Run all tests**

```bash
cd zeroclaw-android && /c/Users/Natal/.cargo/bin/cargo.exe test -p zeroclaw-ffi
./gradlew :app:testDebugUnitTest
```

**Step 9: Commit**

```bash
git commit -m "feat(ffi): add provider hot-swap, reduce restart banner triggers"
```

---

## Final Verification

### Task 16: Full Build + Test Verification

**Step 1: Run all Rust tests**

```bash
cd zeroclaw-android && /c/Users/Natal/.cargo/bin/cargo.exe test -p zeroclaw-ffi
```
Expected: 170+ tests pass (existing) + ~30 new tests

**Step 2: Run clippy**

```bash
/c/Users/Natal/.cargo/bin/cargo.exe clippy -p zeroclaw-ffi --all-targets -- -D warnings
```
Expected: No warnings

**Step 3: Run rustfmt**

```bash
/c/Users/Natal/.cargo/bin/cargo.exe fmt -p zeroclaw-ffi --check
```
Expected: No formatting issues

**Step 4: Run Kotlin lint**

```bash
./gradlew spotlessCheck && ./gradlew detekt
```
Expected: Clean

**Step 5: Build debug APK**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"
export ANDROID_HOME="/c/Users/Natal/AppData/Local/Android/Sdk"
export PATH="$HOME/.cargo/bin:$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

**Step 6: Run Kotlin unit tests**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: All pass

**Step 7: Commit version bump**

Update version to v0.0.33 in all 7 locations (see Release Checklist in MEMORY.md), commit.

---

## Execution Order Summary

| Task | Phase | Description | Depends On |
|------|-------|-------------|------------|
| 1 | 9A | E-Stop FFI (estop.rs) | - |
| 2 | 9A | E-Stop Kotlin UI | Task 1 |
| 3 | 9A | Resource Limits UI (verify/add) | - |
| 4 | 9A | OTP Gating UI (verify/add) | - |
| 5 | 9A | Doctor: Channel checks | - |
| 6 | 9A | Doctor: Runtime traces FFI + UI | - |
| 7 | 9B | Web Access Settings Screen | - |
| 8 | 9B | Multimodal Settings | - |
| 9 | 9B | Config Read API FFI | - |
| 10 | 9C | Auth Profiles FFI | - |
| 11 | 9C | Auth Profiles Kotlin UI | Task 10 |
| 12 | 9C | Model Discovery FFI + UI | - |
| 13 | 9D | Cron add-at/add-every FFI + UI | - |
| 14 | 9D | Transcription Settings Screen | - |
| 15 | 9D | Provider Hot-Swap + Banner Cleanup | - |
| 16 | ALL | Full Build + Test Verification | All |

Tasks 1, 3-10, 12-15 are independent and can be parallelized via subagents.
