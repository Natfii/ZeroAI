// Copyright (c) 2026 @Natfii. All rights reserved.

//! Script audit-record helpers and error normalisation.

use crate::scripting::capabilities::{ScriptExecutionSession, CAPABILITY_DENIED_SENTINEL};
use crate::scripting::manifest::{ScriptAuditRecord, ScriptError, ScriptValidation};
use rhai::{Array, Dynamic};
use std::sync::{Arc, Mutex};

pub(crate) fn validation_failure_audit(
    validation: &ScriptValidation,
    detail: String,
) -> ScriptAuditRecord {
    ScriptAuditRecord {
        script_name: validation.manifest_name.clone(),
        runtime: validation.runtime.clone(),
        success: false,
        requested_capabilities: validation.requested_capabilities.clone(),
        attempted_capabilities: Vec::new(),
        used_capabilities: Vec::new(),
        missing_capabilities: validation.missing_capabilities.clone(),
        warnings: validation.warnings.clone(),
        error: Some(detail),
        duration_ms: 0,
    }
}

pub(crate) fn consume_audit_record(
    session: Arc<Mutex<ScriptExecutionSession>>,
    error: Option<String>,
) -> Result<ScriptAuditRecord, ScriptError> {
    let mutex = Arc::into_inner(session).ok_or_else(|| ScriptError::InternalState {
        detail: "script execution session still had outstanding references".to_string(),
    })?;
    let session = mutex.into_inner().map_err(|_| ScriptError::InternalState {
        detail: "script execution session mutex poisoned".to_string(),
    })?;
    Ok(session.audit_record(error.is_none(), error))
}

/// No-op hook for script audit events.
///
/// Upstream's `runtime_trace::record_event` was replaced with a
/// `LogEvent`-based API (`zeroclaw_log::record_event(LogEvent)`) that
/// requires explicit per-event-type variants. The Android scripting
/// layer does not yet need persisted audit telemetry — script
/// successes and failures already flow back through the FFI return
/// value. This stub keeps the call-site stable so re-introducing
/// audit logging later is a single-function change.
pub(crate) fn record_script_audit_event(_event_type: &str, _record: &ScriptAuditRecord) {
    // Intentional no-op — see KDoc above.
}

pub(crate) fn script_eval_error(detail: String) -> ScriptError {
    if let Some(encoded) = detail
        .split(CAPABILITY_DENIED_SENTINEL)
        .nth(1)
        .map(str::trim)
    {
        let encoded = encoded.trim_start_matches([':', '|']);
        let mut parts = encoded.splitn(3, '|');
        if let (Some(operation), Some(capability)) = (parts.next(), parts.next()) {
            return ScriptError::CapabilityDenied {
                operation: operation.to_string(),
                capability: capability.to_string(),
            };
        }
    }
    if detail.contains("capability denied") {
        ScriptError::ValidationError { detail }
    } else {
        ScriptError::HostError {
            operation: "script.eval".to_string(),
            detail,
        }
    }
}

pub(crate) fn dynamic_to_string(value: Dynamic) -> String {
    if value.is_unit() {
        return "ok".to_string();
    }
    if let Ok(string) = value.clone().into_string() {
        return string;
    }
    value.to_string()
}

pub(crate) fn array_to_strings(values: Array) -> Vec<String> {
    values
        .into_iter()
        .map(|value| value.into_string().unwrap_or_default())
        .collect()
}
