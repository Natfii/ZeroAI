/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Cron job CRUD operations for the Android dashboard.
//!
//! Upstream v0.1.6 made the `zeroclaw::cron` module `pub(crate)`, so
//! all operations are now routed through the gateway REST API on the
//! localhost loopback (`/api/cron`).

use crate::error::FfiError;
use crate::gateway_client;

/// A cron job record suitable for transfer across the FFI boundary.
///
/// Fields are parsed from the gateway JSON response rather than the
/// upstream `CronJob` struct (which is no longer accessible).
#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
pub struct FfiCronJob {
    /// Unique identifier for this job.
    pub id: String,
    /// Cron expression (e.g. `"0 0/5 * * *"`) or one-shot delay marker.
    pub expression: String,
    /// Command string that the scheduler will execute.
    pub command: String,
    /// Epoch milliseconds of the next scheduled run.
    pub next_run_ms: i64,
    /// Epoch milliseconds of the last completed run, if any.
    pub last_run_ms: Option<i64>,
    /// Status string from the last run (e.g. `"ok"`, `"error: ..."`).
    pub last_status: Option<String>,
    /// Whether this job is currently paused (inverse of upstream `enabled`).
    pub paused: bool,
    /// Whether this job self-deletes after a single run (upstream `delete_after_run`).
    pub one_shot: bool,
}

/// Parses a cron job JSON object from the gateway response into an [`FfiCronJob`].
fn parse_job_json(obj: &serde_json::Value) -> FfiCronJob {
    let next_run_ms = obj["next_run"]
        .as_str()
        .and_then(|s| chrono::DateTime::parse_from_rfc3339(s).ok())
        .map_or(0, |dt| dt.timestamp_millis());

    let last_run_ms = obj["last_run"]
        .as_str()
        .and_then(|s| chrono::DateTime::parse_from_rfc3339(s).ok())
        .map(|dt| dt.timestamp_millis());

    let enabled = obj["enabled"].as_bool().unwrap_or(true);

    let command = obj["command"].as_str().unwrap_or("");
    let prompt = obj["prompt"].as_str().unwrap_or("");
    let display_command = if command.is_empty() && !prompt.is_empty() {
        prompt
    } else {
        command
    };

    FfiCronJob {
        id: obj["id"].as_str().unwrap_or("").to_string(),
        expression: obj["expression"]
            .as_str()
            .or_else(|| obj["schedule"].as_str())
            .unwrap_or("")
            .to_string(),
        command: display_command.to_string(),
        next_run_ms,
        last_run_ms,
        last_status: obj["last_status"].as_str().map(String::from),
        paused: !enabled,
        one_shot: obj["delete_after_run"].as_bool().unwrap_or(false),
    }
}

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Lists all cron jobs registered with the running daemon.
    ///
    /// Requires the daemon to be running so the cron SQLite database is accessible.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] on database access failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn list_cron_jobs() -> Vec<FfiCronJob> = list_cron_jobs_inner
);

crate::ffi_export!(
    /// Retrieves a single cron job by its identifier.
    ///
    /// Returns `None` if no job with the given `id` exists.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] on database access failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_cron_job(id: String) -> Option<FfiCronJob> = get_cron_job_inner
);

crate::ffi_export!(
    /// Adds a new recurring cron job with the given expression and command.
    ///
    /// The `expression` must be a valid cron expression (e.g. `"0 0/5 * * *"`).
    /// The `command` is the prompt or action the scheduler will execute.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] on invalid expression or database failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn add_cron_job(expression: String, command: String) -> FfiCronJob = add_cron_job_inner
);

crate::ffi_export!(
    /// Adds a one-shot job that fires once after the given delay.
    ///
    /// The `delay` string uses human-readable durations (e.g. `"5m"`, `"2h"`).
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] on invalid delay or database failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn add_one_shot_job(delay: String, command: String) -> FfiCronJob = add_one_shot_job_inner
);

crate::ffi_export!(
    /// Adds a one-shot cron job that fires at a specific RFC 3339 timestamp.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] on invalid timestamp or database failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn add_cron_job_at(timestamp_rfc3339: String, command: String) -> FfiCronJob = add_cron_job_at_inner
);

crate::ffi_export!(
    /// Adds a fixed-interval repeating cron job.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] on database failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn add_cron_job_every(interval_ms: u64, command: String) -> FfiCronJob = add_cron_job_every_inner
);

crate::ffi_export!(
    /// Removes a cron job by its identifier.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] if the job does not exist or database fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn remove_cron_job(id: String) -> () = remove_cron_job_inner
);

crate::ffi_export!(
    /// Pauses a cron job so it will not fire until resumed.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] if the job does not exist or database fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn pause_cron_job(id: String) -> () = pause_cron_job_inner
);

crate::ffi_export!(
    /// Resumes a previously paused cron job.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] if the job does not exist or database fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn resume_cron_job(id: String) -> () = resume_cron_job_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

/// Lists all cron jobs registered with the daemon.
pub(crate) fn list_cron_jobs_inner() -> Result<Vec<FfiCronJob>, FfiError> {
    let json = gateway_client::gateway_get("/api/cron")?;
    let jobs = json["jobs"]
        .as_array()
        .map(|arr| arr.iter().map(parse_job_json).collect())
        .unwrap_or_default();
    Ok(jobs)
}

/// Retrieves a single cron job by its identifier.
///
/// The gateway does not expose a single-job endpoint, so we fetch the
/// full list and filter. Returns `None` if the job is not found.
pub(crate) fn get_cron_job_inner(id: String) -> Result<Option<FfiCronJob>, FfiError> {
    let jobs = list_cron_jobs_inner()?;
    Ok(jobs.into_iter().find(|j| j.id == id))
}

/// Resolves the agent alias every cron write must run as.
///
/// The gateway's `/api/cron` POST/PATCH bodies require a configured
/// `agent` alias (there is no implicit default). This mirrors the
/// daemon's own selection via [`Config::resolved_runtime_agent_alias`]:
/// prefer the `"default"` agent, else the first enabled agent.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or no
/// agent is configured.
fn resolve_cron_agent() -> Result<String, FfiError> {
    crate::runtime::with_daemon_config(|c| {
        c.resolved_runtime_agent_alias().map(str::to_string)
    })?
    .ok_or_else(|| FfiError::StateError {
        detail: "no agent configured; cannot schedule a cron job".to_string(),
    })
}

/// Posts a cron-add body (with the resolved agent injected) and parses the result.
fn post_cron_job(schedule: String, command: String) -> Result<FfiCronJob, FfiError> {
    let agent = resolve_cron_agent()?;
    let body = serde_json::json!({
        "agent": agent,
        "schedule": schedule,
        "command": command,
    });
    let json = gateway_client::gateway_post("/api/cron", &body)?;
    Ok(parse_job_json(&json["job"]))
}

/// Adds a new recurring cron job.
pub(crate) fn add_cron_job_inner(
    expression: String,
    command: String,
) -> Result<FfiCronJob, FfiError> {
    post_cron_job(expression, command)
}

/// Adds a one-shot job that fires after the given delay string.
///
/// Encodes an `@once <delay>` schedule that the gateway maps to a
/// self-deleting [`Schedule::At`].
pub(crate) fn add_one_shot_job_inner(
    delay: String,
    command: String,
) -> Result<FfiCronJob, FfiError> {
    post_cron_job(format!("@once {delay}"), command)
}

/// Adds a one-shot cron job that fires at a specific RFC 3339 timestamp.
///
/// Encodes an `@at <rfc3339>` schedule that the gateway maps to a
/// self-deleting [`Schedule::At`].
pub(crate) fn add_cron_job_at_inner(
    timestamp_rfc3339: String,
    command: String,
) -> Result<FfiCronJob, FfiError> {
    post_cron_job(format!("@at {timestamp_rfc3339}"), command)
}

/// Adds a fixed-interval repeating cron job.
///
/// Encodes an `@every <ms>ms` schedule that the gateway maps to a
/// [`Schedule::Every`].
pub(crate) fn add_cron_job_every_inner(
    interval_ms: u64,
    command: String,
) -> Result<FfiCronJob, FfiError> {
    post_cron_job(format!("@every {interval_ms}ms"), command)
}

/// Removes a cron job by its identifier.
///
/// A missing job surfaces as the gateway's `404 Not Found`.
pub(crate) fn remove_cron_job_inner(id: String) -> Result<(), FfiError> {
    let path = format!("/api/cron/{id}");
    let _ = gateway_client::gateway_delete(&path)?;
    Ok(())
}

/// Toggles a cron job's `enabled` flag via `PATCH /api/cron/{id}`.
fn patch_cron_enabled(id: &str, enabled: bool) -> Result<(), FfiError> {
    let agent = resolve_cron_agent()?;
    let body = serde_json::json!({
        "agent": agent,
        "enabled": enabled,
    });
    let path = format!("/api/cron/{id}");
    let _ = gateway_client::gateway_patch(&path, &body)?;
    Ok(())
}

/// Pauses a cron job so it will not fire until resumed.
pub(crate) fn pause_cron_job_inner(id: String) -> Result<(), FfiError> {
    patch_cron_enabled(&id, false)
}

/// Resumes a previously paused cron job.
pub(crate) fn resume_cron_job_inner(id: String) -> Result<(), FfiError> {
    patch_cron_enabled(&id, true)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_list_cron_jobs_not_running() {
        let result = list_cron_jobs_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_get_cron_job_not_running() {
        let result = get_cron_job_inner("some-id".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_add_cron_job_not_running() {
        let result = add_cron_job_inner("0 * * * *".into(), "echo hello".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_add_one_shot_job_not_running() {
        let result = add_one_shot_job_inner("5m".into(), "echo once".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_add_cron_job_at_not_running() {
        let result = add_cron_job_at_inner("2026-12-31T23:59:59Z".into(), "echo at-time".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_add_cron_job_every_not_running() {
        let result = add_cron_job_every_inner(60_000, "echo every-min".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_remove_cron_job_not_running() {
        let result = remove_cron_job_inner("some-id".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_pause_cron_job_not_running() {
        let result = pause_cron_job_inner("some-id".into());
        assert!(result.is_err());
    }

    #[test]
    fn test_resume_cron_job_not_running() {
        let result = resume_cron_job_inner("some-id".into());
        assert!(result.is_err());
    }

    #[test]
    fn test_parse_job_json() {
        let json = serde_json::json!({
            "id": "abc-123",
            "expression": "*/5 * * * *",
            "command": "echo test",
            "next_run": "2026-01-01T00:00:00Z",
            "last_run": null,
            "last_status": "ok",
            "enabled": false,
            "delete_after_run": false,
        });
        let job = parse_job_json(&json);
        assert_eq!(job.id, "abc-123");
        assert_eq!(job.expression, "*/5 * * * *");
        assert_eq!(job.command, "echo test");
        assert!(job.paused);
        assert!(!job.one_shot);
        assert!(job.last_run_ms.is_none());
        assert_eq!(job.last_status.as_deref(), Some("ok"));
    }

    #[test]
    fn test_parse_agent_job_shows_prompt_as_command() {
        let json = serde_json::json!({
            "id": "agent-456",
            "expression": "*/5 * * * *",
            "command": "",
            "prompt": "Fetch the latest news and summarize it",
            "job_type": "agent",
            "next_run": "2026-01-01T00:00:00Z",
            "enabled": true,
            "delete_after_run": false,
        });
        let job = parse_job_json(&json);
        assert_eq!(job.id, "agent-456");
        assert_eq!(job.expression, "*/5 * * * *");
        assert_eq!(job.command, "Fetch the latest news and summarize it");
        assert!(!job.paused);
    }
}
