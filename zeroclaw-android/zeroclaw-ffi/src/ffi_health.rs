/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Lightweight health tracking shim for the Android FFI daemon.
//!
//! Upstream v0.1.6 made the `zeroclaw::health` module `pub(crate)`,
//! so we maintain our own minimal health state for the component
//! supervisors in [`crate::runtime`].
//!
//! This shim is intentionally simple: a global `HashMap` behind a
//! `Mutex`, recording status, last error, and restart count per
//! component name.  The snapshot functions return data in the same
//! shape the Kotlin bridge expects.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

/// Global health state, initialised on first access.
static HEALTH: OnceLock<Mutex<HealthState>> = OnceLock::new();

/// Internal mutable state.
struct HealthState {
    /// When the health tracker was first accessed (approximates daemon start).
    start_time: Instant,
    /// Per-component health records.
    components: HashMap<String, ComponentState>,
}

/// Per-component mutable record.
struct ComponentState {
    status: String,
    last_error: Option<String>,
    restart_count: u64,
}

/// Snapshot of a single component's health, returned by [`snapshot`].
pub struct ComponentHealth {
    /// Status string: `"ok"` or `"error"`.
    pub status: String,
    /// Last error message, if the component is in error state.
    pub last_error: Option<String>,
    /// Cumulative restart count.
    pub restart_count: u64,
}

/// Full health snapshot, returned by [`snapshot`].
pub struct HealthSnapshot {
    /// Host process ID.
    pub pid: u32,
    /// Seconds since the health tracker was initialised.
    pub uptime_seconds: u64,
    /// Per-component health records.
    pub components: HashMap<String, ComponentHealth>,
}

/// Returns a reference to the health state, creating it on first call.
fn state() -> &'static Mutex<HealthState> {
    HEALTH.get_or_init(|| {
        Mutex::new(HealthState {
            start_time: Instant::now(),
            components: HashMap::new(),
        })
    })
}

/// Acquires the health mutex, recovering from poison if a prior thread
/// panicked while holding it.  Same pattern as [`crate::runtime::lock_daemon`].
fn lock_health() -> std::sync::MutexGuard<'static, HealthState> {
    state().lock().unwrap_or_else(|e| {
        tracing::warn!("Health mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Marks the named component as healthy.
pub fn mark_component_ok(name: &str) {
    let mut guard = lock_health();
    let entry = guard
        .components
        .entry(name.to_string())
        .or_insert_with(|| ComponentState {
            status: "ok".into(),
            last_error: None,
            restart_count: 0,
        });
    entry.status = "ok".into();
}

/// Marks the named component as in error state with a detail message.
pub fn mark_component_error(name: &str, detail: impl ToString) {
    let mut guard = lock_health();
    let entry = guard
        .components
        .entry(name.to_string())
        .or_insert_with(|| ComponentState {
            status: "error".into(),
            last_error: None,
            restart_count: 0,
        });
    entry.status = "error".into();
    entry.last_error = Some(detail.to_string());
}

/// Increments the restart counter for the named component.
pub fn bump_component_restart(name: &str) {
    let mut guard = lock_health();
    if let Some(entry) = guard.components.get_mut(name) {
        entry.restart_count = entry.restart_count.saturating_add(1);
    }
}

/// Returns a point-in-time snapshot of all component health.
pub fn snapshot() -> HealthSnapshot {
    let g = lock_health();
    let components = g
        .components
        .iter()
        .map(|(k, v)| {
            (
                k.clone(),
                ComponentHealth {
                    status: v.status.clone(),
                    last_error: v.last_error.clone(),
                    restart_count: v.restart_count,
                },
            )
        })
        .collect();
    HealthSnapshot {
        pid: std::process::id(),
        uptime_seconds: g.start_time.elapsed().as_secs(),
        components,
    }
}

/// Returns the health snapshot as a JSON value.
///
/// Shape matches what [`crate::runtime::get_status_inner`] and the
/// Kotlin bridge expect:
/// ```json
/// {
///   "pid": 12345,
///   "uptime_seconds": 60,
///   "components": {
///     "gateway": { "status": "ok", "last_error": null, "restart_count": 0 }
///   }
/// }
/// ```
pub fn snapshot_json() -> serde_json::Value {
    let snap = snapshot();
    let mut components = serde_json::Map::new();
    for (name, ch) in &snap.components {
        components.insert(
            name.clone(),
            serde_json::json!({
                "status": ch.status,
                "last_error": ch.last_error,
                "restart_count": ch.restart_count,
            }),
        );
    }
    serde_json::json!({
        "pid": snap.pid,
        "uptime_seconds": snap.uptime_seconds,
        "components": components,
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_mark_ok_and_snapshot() {
        mark_component_ok("test_comp");
        let snap = snapshot();
        assert_eq!(snap.pid, std::process::id());
        let comp = snap.components.get("test_comp").unwrap();
        assert_eq!(comp.status, "ok");
        assert!(comp.last_error.is_none());
    }

    #[test]
    fn test_mark_error() {
        mark_component_error("err_comp", "something broke");
        let snap = snapshot();
        let comp = snap.components.get("err_comp").unwrap();
        assert_eq!(comp.status, "error");
        assert_eq!(comp.last_error.as_deref(), Some("something broke"));
    }

    #[test]
    fn test_bump_restart() {
        mark_component_ok("restart_comp");
        bump_component_restart("restart_comp");
        bump_component_restart("restart_comp");
        let snap = snapshot();
        let comp = snap.components.get("restart_comp").unwrap();
        assert_eq!(comp.restart_count, 2);
    }

    #[test]
    fn test_snapshot_json_shape() {
        mark_component_ok("json_comp");
        let json = snapshot_json();
        assert!(json["pid"].is_u64());
        assert!(json["uptime_seconds"].is_u64());
        assert!(json["components"]["json_comp"]["status"].is_string());
    }
}
