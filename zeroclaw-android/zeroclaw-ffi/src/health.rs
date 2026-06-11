/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Structured health detail for the Android dashboard.
//!
//! Uses [`crate::ffi_health`] for local component tracking since
//! the upstream `zeroclaw::health` module is `pub(crate)` in v0.1.6.

use crate::error::FfiError;

/// Per-component health status.
#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
pub struct FfiComponentHealth {
    /// Component name (e.g. "gateway", "scheduler").
    pub name: String,
    /// Status string: "ok", "error", or "starting".
    pub status: String,
    /// Last error message, if any.
    pub last_error: Option<String>,
    /// Number of times this component has been restarted.
    pub restart_count: u64,
}

/// Full daemon health detail with per-component breakdown.
#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
pub struct FfiHealthDetail {
    /// Whether the daemon process is currently running.
    pub daemon_running: bool,
    /// Process ID of the host application.
    pub pid: u32,
    /// Daemon uptime in seconds.
    pub uptime_seconds: u64,
    /// Health status of each supervised component.
    pub components: Vec<FfiComponentHealth>,
}

/// Returns structured health detail for all daemon components.
pub(crate) fn get_health_detail_inner() -> Result<FfiHealthDetail, FfiError> {
    let daemon_running = crate::runtime::is_daemon_running()?;

    let snapshot = crate::ffi_health::snapshot();
    let mut components: Vec<FfiComponentHealth> = snapshot
        .components
        .into_iter()
        .map(|(name, ch)| FfiComponentHealth {
            name,
            status: ch.status,
            last_error: ch.last_error,
            restart_count: ch.restart_count,
        })
        .collect();
    components.extend(search_engine_components());

    Ok(FfiHealthDetail {
        daemon_running,
        pid: snapshot.pid,
        uptime_seconds: snapshot.uptime_seconds,
        components,
    })
}

/// Maps live meta-search engine health into component rows named
/// `web_search/<engine>` so the Doctor screen surfaces them without any
/// dedicated UI work. Engines with no recorded activity yet are omitted.
fn search_engine_components() -> Vec<FfiComponentHealth> {
    zeroclaw_tools::metasearch::health::global()
        .snapshot()
        .into_iter()
        .map(|engine| FfiComponentHealth {
            name: format!("web_search/{}", engine.engine_id),
            status: if engine.condition == "healthy" {
                "ok".to_owned()
            } else {
                "error".to_owned()
            },
            last_error: engine.last_error,
            restart_count: 0,
        })
        .collect()
}

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Returns structured health detail for all daemon components.
    ///
    /// Unlike `get_status` (raw JSON), this returns typed component-level
    /// data including restart counts and last errors.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not
    /// running, or [`crate::FfiError::InternalPanic`] if native code
    /// panics.
    fn get_health_detail() -> FfiHealthDetail = get_health_detail_inner
);

crate::ffi_export!(
    /// Returns health for a single named component.
    ///
    /// Returns `None` if no component with the given name exists.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_component_health(name: String) -> Option<FfiComponentHealth> = get_component_health_ffi
);

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn get_component_health_ffi(
    name: String,
) -> Result<Option<FfiComponentHealth>, FfiError> {
    Ok(get_component_health_inner(name))
}

/// Returns health for a single named component.
pub(crate) fn get_component_health_inner(name: String) -> Option<FfiComponentHealth> {
    let snapshot = crate::ffi_health::snapshot();
    snapshot.components.get(&name).map(|ch| FfiComponentHealth {
        name,
        status: ch.status.clone(),
        last_error: ch.last_error.clone(),
        restart_count: ch.restart_count,
    })
}

/// Health of one on-device meta search engine.
#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
pub struct FfiSearchEngineHealth {
    /// Engine spec id (e.g. "ddg_html").
    pub engine_id: String,
    /// Human-readable engine name (e.g. "DuckDuckGo").
    pub display_name: String,
    /// Condition: "healthy", "backoff", or "layout_suspect".
    pub condition: String,
    /// Most recent failure description, if any.
    pub last_error: Option<String>,
    /// Unix seconds of the last successful search, if any.
    pub last_ok_unix: Option<u64>,
    /// Unix seconds of the last adopted self-repair, if any.
    pub repaired_at_unix: Option<u64>,
    /// Seconds of backoff remaining when the engine is backing off.
    pub backoff_remaining_secs: Option<u64>,
}

crate::ffi_export!(
    /// Returns per-engine health for the on-device meta search backend.
    ///
    /// One row per bundled engine, joined with live health where the engine
    /// has been queried this session; untouched engines report `"healthy"`
    /// with no timestamps. Backing data for the Web Search plugin detail
    /// screen's engine-status rows.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_search_engine_health() -> Vec<FfiSearchEngineHealth> = get_search_engine_health_ffi
);

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn get_search_engine_health_ffi() -> Result<Vec<FfiSearchEngineHealth>, FfiError> {
    Ok(get_search_engine_health_inner())
}

/// Builds the per-engine health rows from bundled specs plus the live
/// health registry.
pub(crate) fn get_search_engine_health_inner() -> Vec<FfiSearchEngineHealth> {
    let live: std::collections::HashMap<String, _> =
        zeroclaw_tools::metasearch::health::global()
            .snapshot()
            .into_iter()
            .map(|engine| (engine.engine_id.clone(), engine))
            .collect();
    zeroclaw_tools::metasearch::spec::bundled_specs()
        .unwrap_or_default()
        .into_iter()
        .map(|spec| match live.get(&spec.id) {
            Some(engine) => FfiSearchEngineHealth {
                engine_id: spec.id,
                display_name: spec.display_name,
                condition: engine.condition.to_owned(),
                last_error: engine.last_error.clone(),
                last_ok_unix: engine.last_ok_unix,
                repaired_at_unix: engine.repaired_at_unix,
                backoff_remaining_secs: engine.backoff_remaining_secs,
            },
            None => FfiSearchEngineHealth {
                engine_id: spec.id,
                display_name: spec.display_name,
                condition: "healthy".to_owned(),
                last_error: None,
                last_ok_unix: None,
                repaired_at_unix: None,
                backoff_remaining_secs: None,
            },
        })
        .collect()
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_get_health_detail_returns_struct() {
        let detail = get_health_detail_inner().unwrap();
        // Daemon is not running in tests
        assert!(!detail.daemon_running);
        assert_eq!(detail.pid, std::process::id());
    }

    #[test]
    fn test_get_component_health_missing() {
        let result = get_component_health_inner("nonexistent_health_test".into());
        assert!(result.is_none());
    }

    #[test]
    fn test_search_engine_health_covers_all_bundled_engines() {
        let rows = get_search_engine_health_inner();
        let ids: Vec<&str> = rows.iter().map(|r| r.engine_id.as_str()).collect();
        assert_eq!(ids, ["ddg_html", "mojeek", "wikipedia", "marginalia"]);
        assert!(rows.iter().all(|r| !r.display_name.is_empty()));
    }

    #[test]
    fn test_search_engine_health_reflects_recorded_failures() {
        zeroclaw_tools::metasearch::health::global().record_failure(
            "mojeek",
            &zeroclaw_tools::metasearch::executor::EngineFailure::Blocked("HTTP 429".into()),
        );
        let rows = get_search_engine_health_inner();
        let mojeek = rows.iter().find(|r| r.engine_id == "mojeek").unwrap();
        assert_eq!(mojeek.condition, "backoff");
        assert!(mojeek.last_error.as_deref().unwrap_or("").contains("429"));
        assert!(mojeek.backoff_remaining_secs.is_some());

        let detail = get_health_detail_inner().unwrap();
        assert!(
            detail
                .components
                .iter()
                .any(|c| c.name == "web_search/mojeek" && c.status == "error"),
            "doctor component join must surface the failing engine"
        );

        zeroclaw_tools::metasearch::health::global().record_ok("mojeek");
    }
}
