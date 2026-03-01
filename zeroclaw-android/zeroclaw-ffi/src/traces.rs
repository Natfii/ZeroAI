/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Runtime trace reader for the `ZeroClaw` daemon.
//!
//! Reads JSONL trace events from the workspace state directory.
//! Upstream stores traces at `{workspace}/state/runtime-trace.jsonl`
//! (see `zeroclaw/src/observability/runtime_trace.rs`).

use crate::error::FfiError;
use crate::runtime;

/// Queries runtime trace events from the JSONL file.
///
/// Reads `{workspace}/state/runtime-trace.jsonl`, applies optional
/// filters, and returns the newest `limit` events as a JSON array string.
///
/// # Arguments
///
/// * `filter` - Optional case-insensitive substring match on message/payload.
/// * `event_type` - Optional exact match on `event_type` field.
/// * `limit` - Maximum number of events to return (newest first).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// or [`FfiError::SpawnError`] if the trace file cannot be read or
/// the result cannot be serialised.
pub(crate) fn query_traces_inner(
    filter: Option<String>,
    event_type: Option<String>,
    limit: u32,
) -> Result<String, FfiError> {
    let workspace_dir = runtime::with_daemon_config(|c| c.workspace_dir.clone())?;
    let trace_path = workspace_dir.join("state").join("runtime-trace.jsonl");

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
            if let Some(ref et) = event_type
                && ev.get("event_type").and_then(|v| v.as_str()) != Some(et.as_str())
            {
                return false;
            }
            if let Some(ref f) = filter_lower {
                let msg = ev
                    .get("message")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_lowercase();
                let payload = ev
                    .get("payload")
                    .map(|v| v.to_string().to_lowercase())
                    .unwrap_or_default();
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
#[allow(clippy::unwrap_used)]
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
