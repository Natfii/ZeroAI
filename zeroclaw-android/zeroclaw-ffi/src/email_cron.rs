/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Email-check cron job synchronisation.
//!
//! Manages the lifecycle of scheduled email-check cron jobs by talking to
//! the gateway REST API. Each configured `check_time` (`HH:MM`) becomes a
//! daily cron job whose command carries an `[email_check_scheduled]` marker
//! so that this module can identify and replace its own jobs without
//! disturbing user-created cron entries.

use crate::cron;
use crate::error::FfiError;

/// Marker embedded in the cron command string so we can distinguish
/// email-check jobs from user-created ones.
const EMAIL_CRON_MARKER: &str = "[email_check_scheduled]";

/// Synchronises email-check cron jobs with the daemon's cron subsystem.
///
/// 1. Lists all existing cron jobs via the gateway.
/// 2. Removes any whose command contains the [`EMAIL_CRON_MARKER`].
/// 3. For each entry in `check_times` (expected `HH:MM` 24-hour format),
///    adds a new daily cron job with schedule `MM HH * * *`.
///
/// Passing an empty `check_times` slice removes all email-check jobs
/// without adding new ones (used when the email feature is disabled).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
/// Returns [`FfiError::SpawnError`] if a gateway HTTP call fails.
pub(crate) fn sync_email_cron_jobs(
    check_times: &[String],
    timezone: Option<&str>,
) -> Result<(), FfiError> {
    // Step 1: list existing cron jobs.
    let existing = cron::list_cron_jobs_inner()?;

    // Step 2: remove any jobs that carry our marker.
    for job in &existing {
        if job.command.contains(EMAIL_CRON_MARKER) {
            cron::remove_cron_job_inner(job.id.clone())?;
        }
    }

    // Step 3: add a new job for each check time.
    let tz_label = timezone.unwrap_or("UTC");
    for time in check_times {
        let (hour, minute) = parse_hh_mm(time)?;
        let schedule = format!("{minute} {hour} * * *");
        let command = format!(
            "/prompt {EMAIL_CRON_MARKER} Check the email inbox and summarize any \
             new unread messages. If there are urgent or important messages, \
             highlight them. Timezone context: {tz_label}."
        );
        cron::add_cron_job_inner(schedule, command)?;
    }

    Ok(())
}

/// Parses an `"HH:MM"` string into `(hour, minute)` integers.
///
/// Assumes the caller has already validated the format via
/// [`zeroclaw::config::schema::validate_check_times`]; returns
/// [`FfiError::InvalidArgument`] if parsing still fails.
fn parse_hh_mm(time: &str) -> Result<(u8, u8), FfiError> {
    let parts: Vec<&str> = time.split(':').collect();
    if parts.len() != 2 {
        return Err(FfiError::InvalidArgument {
            detail: format!("expected HH:MM, got '{time}'"),
        });
    }
    let hour: u8 = parts[0].parse().map_err(|_| FfiError::InvalidArgument {
        detail: format!("invalid hour in '{time}'"),
    })?;
    let minute: u8 = parts[1].parse().map_err(|_| FfiError::InvalidArgument {
        detail: format!("invalid minute in '{time}'"),
    })?;
    Ok((hour, minute))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_hh_mm_valid() {
        assert_eq!(parse_hh_mm("08:30").unwrap(), (8, 30));
        assert_eq!(parse_hh_mm("00:00").unwrap(), (0, 0));
        assert_eq!(parse_hh_mm("23:59").unwrap(), (23, 59));
    }

    #[test]
    fn test_parse_hh_mm_invalid_format() {
        assert!(parse_hh_mm("0830").is_err());
        assert!(parse_hh_mm("08:30:00").is_err());
        assert!(parse_hh_mm("").is_err());
    }

    #[test]
    fn test_parse_hh_mm_non_numeric() {
        assert!(parse_hh_mm("ab:cd").is_err());
    }

    #[test]
    fn test_sync_email_cron_jobs_daemon_not_running() {
        let result = sync_email_cron_jobs(&["08:00".to_string()], None);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_sync_email_cron_jobs_empty_removes_only() {
        // With no daemon, this still errors at the list step.
        let result = sync_email_cron_jobs(&[], Some("America/New_York"));
        assert!(result.is_err());
    }

    #[test]
    fn test_email_cron_marker_is_stable() {
        // Guard against accidental marker changes that would orphan jobs.
        assert_eq!(EMAIL_CRON_MARKER, "[email_check_scheduled]");
    }
}
