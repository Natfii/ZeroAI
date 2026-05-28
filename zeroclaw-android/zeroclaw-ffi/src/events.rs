/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Observer event bridge with UniFFI callback interface.
//!
//! Implements the upstream [`Observer`] trait to capture events from the
//! `ZeroClaw` daemon and forward them to Kotlin via a registered callback.
//! A fixed-capacity ring buffer retains recent events for on-demand queries.
//!
//! ## Observer Wiring
//!
//! [`AndroidObserver`] is currently wired into the heartbeat worker via
//! [`MultiObserver`]. The gateway and agent runner create their own
//! observers internally from config, so only heartbeat-triggered events
//! flow through this bridge. Wiring the remaining components requires
//! upstream support for a global observer registry or dependency injection.

use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};

use zeroclaw::observability::traits::{Observer, ObserverEvent, ObserverMetric};

use crate::error::FfiError;

/// Ring buffer capacity for event history.
const EVENT_BUFFER_CAPACITY: usize = 500;

/// Callback interface that Kotlin implements to receive events.
///
/// The generated Kotlin class calls back into Rust from the UI thread,
/// but [`on_event`](FfiEventListener::on_event) is invoked from a Rust
/// background thread, so implementations must be thread-safe.
#[uniffi::export(callback_interface)]
pub trait FfiEventListener: Send + Sync {
    /// Called from a Rust background thread with a JSON-encoded event.
    fn on_event(&self, event_json: String);
}

/// Global listener slot.
static LISTENER: OnceLock<Mutex<Option<Arc<dyn FfiEventListener>>>> = OnceLock::new();

/// Global event ring buffer.
static EVENT_BUFFER: OnceLock<Mutex<VecDeque<String>>> = OnceLock::new();

/// Thread-safe event counter for unique IDs.
#[allow(dead_code)]
static EVENT_COUNTER: AtomicU64 = AtomicU64::new(0);

/// Maps tool names to their owning skill names for event enrichment.
static TOOL_SKILL_MAP: std::sync::LazyLock<RwLock<HashMap<String, String>>> =
    std::sync::LazyLock::new(|| RwLock::new(HashMap::new()));

/// Registers tool-to-skill mappings for event enrichment.
///
/// Replaces any existing mappings. Each tuple is `(tool_name, skill_name)`.
///
/// Called during daemon startup after skills are loaded so that
/// `ToolCall` and `ToolCallStart` events can carry a `skill_name` field.
// Not yet called from `start_daemon_inner` because the upstream `skills`
// module is `pub(crate)`, blocking access to `Skill::tools()` from this
// crate. Once upstream exposes a public skill-tool listing API, wire
// this in after `load_skills_with_config` so `ToolCall`/`ToolCallStart`
// events can carry a `skill_name` field.
#[allow(dead_code)]
pub(crate) fn register_skill_tool_names(mappings: Vec<(String, String)>) {
    if let Ok(mut map) = TOOL_SKILL_MAP.write() {
        map.clear();
        for (tool_name, skill_name) in mappings {
            map.insert(tool_name, skill_name);
        }
    }
}

/// Looks up the skill name that owns a given tool.
fn skill_for_tool(tool_name: &str) -> Option<String> {
    TOOL_SKILL_MAP.read().ok()?.get(tool_name).cloned()
}

/// Returns a reference to the listener mutex, initialising on first access.
fn listener_slot() -> &'static Mutex<Option<Arc<dyn FfiEventListener>>> {
    LISTENER.get_or_init(|| Mutex::new(None))
}

/// Returns a reference to the event buffer mutex, initialising on first access.
fn event_buffer() -> &'static Mutex<VecDeque<String>> {
    EVENT_BUFFER.get_or_init(|| Mutex::new(VecDeque::with_capacity(EVENT_BUFFER_CAPACITY)))
}

/// Acquires the event listener mutex with poison recovery.
///
/// Uses the same `unwrap_or_else(|e| e.into_inner())` pattern as
/// [`crate::runtime::lock_daemon`] to prevent permanent failure after
/// a panic in the observer callback thread.
fn lock_listener() -> std::sync::MutexGuard<'static, Option<Arc<dyn FfiEventListener>>> {
    listener_slot().lock().unwrap_or_else(|e| {
        tracing::warn!("Event listener mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Acquires the event buffer mutex with poison recovery.
fn lock_event_buffer() -> std::sync::MutexGuard<'static, VecDeque<String>> {
    event_buffer().lock().unwrap_or_else(|e| {
        tracing::warn!("Event buffer mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Observer implementation that forwards events to the registered callback.
///
/// Events are serialised to JSON strings, stored in the ring buffer, and
/// optionally forwarded to a Kotlin listener if one is registered.
/// Metrics are intentionally not forwarded to conserve battery.
#[allow(dead_code)]
pub(crate) struct AndroidObserver;

impl Observer for AndroidObserver {
    fn record_event(&self, event: &ObserverEvent) {
        let id = EVENT_COUNTER.fetch_add(1, Ordering::Relaxed);
        let json = format_event_json(id, event);

        // Buffer the event.
        {
            let mut buf = lock_event_buffer();
            if buf.len() >= EVENT_BUFFER_CAPACITY {
                buf.pop_front();
            }
            buf.push_back(json.clone());
        }

        // Clone the Arc outside the lock to avoid holding the mutex across
        // the foreign callback (which could re-enter register/unregister).
        let maybe_listener = lock_listener().as_ref().map(Arc::clone);
        if let Some(listener) = maybe_listener {
            listener.on_event(json);
        }
    }

    fn record_metric(&self, _metric: &ObserverMetric) {
        // Metrics are intentionally not forwarded to Android (battery concern).
    }

    // Upstream `Observer` trait returns `&str` with an implicit lifetime;
    // clippy suggests `&'static str` but we cannot change the trait signature.
    #[allow(clippy::unnecessary_literal_bound)]
    fn name(&self) -> &str {
        "android"
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

/// Registers a Kotlin-side event listener.
///
// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Unregisters the current event listener.
    ///
    /// After this call, events are still buffered in the ring buffer but
    /// no longer forwarded to Kotlin.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateCorrupted`] if internal state is
    /// poisoned, or [`crate::FfiError::InternalPanic`] if native code
    /// panics.
    fn unregister_event_listener() -> () = unregister_event_listener_inner
);

crate::ffi_export!(
    /// Returns the most recent events as a JSON array.
    ///
    /// Events are ordered chronologically (oldest first). The `limit`
    /// parameter caps how many events to return.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateCorrupted`] if internal state is
    /// poisoned, or [`crate::FfiError::InternalPanic`] if native code
    /// panics.
    fn get_recent_events(limit: u32) -> String = get_recent_events_inner
);

crate::ffi_export!(
    /// Registers a Kotlin-side event listener for daemon-emitted events.
    ///
    /// Only one listener can be registered at a time. A new listener
    /// replaces the previous one.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateCorrupted`] if internal state is
    /// poisoned, or [`crate::FfiError::InternalPanic`] if native code
    /// panics.
    fn register_event_listener(listener: Box<dyn FfiEventListener>) -> () = register_event_listener_boxed
);

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn register_event_listener_boxed(
    listener: Box<dyn FfiEventListener>,
) -> Result<(), FfiError> {
    register_event_listener_inner(Arc::from(listener))
}

/// Only one listener can be registered at a time. A new listener replaces
/// the previous one. Accepts an [`Arc`] so the caller can convert from the
/// UniFFI `Box<dyn FfiEventListener>` at the FFI boundary.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn register_event_listener_inner(
    listener: Arc<dyn FfiEventListener>,
) -> Result<(), FfiError> {
    let mut slot = lock_listener();
    *slot = Some(listener);
    Ok(())
}

/// Unregisters the current event listener.
///
/// After this call, events are still buffered but no longer forwarded.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn unregister_event_listener_inner() -> Result<(), FfiError> {
    let mut slot = lock_listener();
    *slot = None;
    Ok(())
}

/// Emits a custom event with an arbitrary `kind` and JSON `data` string.
///
/// The event is buffered in the ring buffer and forwarded to the Kotlin
/// listener if one is registered.  Use this for application-level events
/// that do not map to an [`ObserverEvent`] variant (e.g. capability
/// approval requests).
pub(crate) fn emit_custom_event(kind: &str, data_json: &str) {
    let id = EVENT_COUNTER.fetch_add(1, Ordering::Relaxed);
    let now_ms = chrono::Utc::now().timestamp_millis();
    let escaped_kind = escape_json_string(kind);
    let json = format!(
        r#"{{"id":{id},"timestamp_ms":{now_ms},"kind":"{escaped_kind}","data":{data_json}}}"#
    );

    // Buffer the event.
    {
        let mut buf = lock_event_buffer();
        if buf.len() >= EVENT_BUFFER_CAPACITY {
            buf.pop_front();
        }
        buf.push_back(json.clone());
    }

    // Forward to Kotlin listener if registered.
    let maybe_listener = lock_listener().as_ref().map(Arc::clone);
    if let Some(listener) = maybe_listener {
        listener.on_event(json);
    }
}

/// Returns the most recent events as a JSON array string.
///
/// Events are ordered chronologically (oldest first). The `limit`
/// parameter caps how many events to return.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn get_recent_events_inner(limit: u32) -> Result<String, FfiError> {
    let buf = lock_event_buffer();
    let start = buf.len().saturating_sub(limit as usize);
    let json = buf.range(start..).cloned().collect::<Vec<_>>().join(",");
    Ok(format!("[{json}]"))
}

/// Serialises an [`ObserverEvent`] to a JSON string with metadata.
///
/// Uses manual formatting rather than serde to avoid per-event allocation
/// overhead from the `serde_json` value tree.
#[allow(dead_code)]
fn format_event_json(id: u64, event: &ObserverEvent) -> String {
    let now_ms = chrono::Utc::now().timestamp_millis();
    let (kind, data) = event_to_kind_and_data(event);
    format!(r#"{{"id":{id},"timestamp_ms":{now_ms},"kind":"{kind}","data":{data}}}"#)
}

/// Formats an [`ObserverEvent::LlmResponse`] variant as a JSON string.
#[allow(dead_code)]
fn format_llm_response(
    provider: &str,
    model: &str,
    duration: &std::time::Duration,
    success: bool,
    error_message: Option<&String>,
    input_tokens: Option<u64>,
    output_tokens: Option<u64>,
) -> String {
    let error_json = error_message.map_or_else(
        || "null".to_string(),
        |e| format!(r#""{}""#, escape_json_string(e)),
    );
    let in_tok = input_tokens.map_or_else(|| "null".to_string(), |t| t.to_string());
    let out_tok = output_tokens.map_or_else(|| "null".to_string(), |t| t.to_string());
    format!(
        r#"{{"provider":"{}","model":"{}","duration_ms":{},"success":{success},"error":{error_json},"input_tokens":{in_tok},"output_tokens":{out_tok}}}"#,
        escape_json_string(provider),
        escape_json_string(model),
        duration.as_millis()
    )
}

/// Formats an [`ObserverEvent::AgentEnd`] variant as a JSON string.
#[allow(dead_code)]
fn format_agent_end(
    provider: &str,
    model: &str,
    duration: &std::time::Duration,
    tokens_used: Option<u64>,
    cost_usd: Option<f64>,
) -> String {
    let tokens_json = tokens_used.map_or_else(|| "null".to_string(), |t| t.to_string());
    let cost_json = cost_usd.map_or_else(|| "null".to_string(), |c| format!("{c}"));
    format!(
        r#"{{"provider":"{}","model":"{}","duration_ms":{},"tokens":{tokens_json},"cost_usd":{cost_json}}}"#,
        escape_json_string(provider),
        escape_json_string(model),
        duration.as_millis()
    )
}

/// Converts an [`ObserverEvent`] to a `(kind, data_json)` pair.
#[allow(clippy::match_same_arms, dead_code)]
fn event_to_kind_and_data(event: &ObserverEvent) -> (&'static str, String) {
    match event {
        ObserverEvent::LlmRequest {
            model_provider: provider,
            model,
            messages_count,
        } => (
            "llm_request",
            format!(
                r#"{{"provider":"{}","model":"{}","messages":{messages_count}}}"#,
                escape_json_string(provider),
                escape_json_string(model)
            ),
        ),
        ObserverEvent::LlmResponse {
            model_provider: provider,
            model,
            duration,
            success,
            error_message,
            input_tokens,
            output_tokens,
        } => (
            "llm_response",
            format_llm_response(
                provider,
                model,
                duration,
                *success,
                error_message.as_ref(),
                *input_tokens,
                *output_tokens,
            ),
        ),
        ObserverEvent::ToolCall {
            tool,
            duration,
            success,
            tool_call_id: _,
            arguments: _,
            result: _,
        } => {
            let skill_json = skill_for_tool(tool)
                .map(|s| format!(r#","skill_name":"{}""#, escape_json_string(&s)))
                .unwrap_or_default();
            (
                "tool_call",
                format!(
                    r#"{{"tool":"{}","duration_ms":{},"success":{success}{skill_json}}}"#,
                    escape_json_string(tool),
                    duration.as_millis()
                ),
            )
        }
        ObserverEvent::ToolCallStart { tool, tool_call_id: _, arguments: _ } => {
            let skill_json = skill_for_tool(tool)
                .map(|s| format!(r#","skill_name":"{}""#, escape_json_string(&s)))
                .unwrap_or_default();
            (
                "tool_call_start",
                format!(r#"{{"tool":"{}"{skill_json}}}"#, escape_json_string(tool)),
            )
        }
        ObserverEvent::ChannelMessage { channel, direction } => (
            "channel_message",
            format!(
                r#"{{"channel":"{}","direction":"{}"}}"#,
                escape_json_string(channel),
                escape_json_string(direction)
            ),
        ),
        ObserverEvent::Error { component, message } => (
            "error",
            format!(
                r#"{{"component":"{}","message":"{}"}}"#,
                escape_json_string(component),
                escape_json_string(message)
            ),
        ),
        ObserverEvent::HeartbeatTick => ("heartbeat_tick", "{}".to_string()),
        ObserverEvent::TurnComplete => ("turn_complete", "{}".to_string()),
        ObserverEvent::AgentStart { model_provider: provider, model } => (
            "agent_start",
            format!(
                r#"{{"provider":"{}","model":"{}"}}"#,
                escape_json_string(provider),
                escape_json_string(model)
            ),
        ),
        ObserverEvent::AgentEnd {
            model_provider: provider,
            model,
            duration,
            tokens_used,
            cost_usd,
        } => (
            "agent_end",
            format_agent_end(provider, model, duration, *tokens_used, *cost_usd),
        ),
        // Upstream's `ObserverEvent` enum has variants beyond what the
        // Android observer surfaces (CacheHit, CacheMiss,
        // DeploymentStarted, etc.). They map to a generic "unknown"
        // placeholder here — add explicit arms when the Android UI
        // grows an observer surface for them.
        _ => ("unknown", "{}".to_string()),
    }
}

/// Escapes a string for safe embedding inside a JSON string literal.
///
/// Handles double quotes, backslashes, common whitespace escapes
/// (`\n`, `\r`, `\t`), and all remaining control characters (`\x00`-`\x1F`)
/// via `\uXXXX` notation.
#[allow(dead_code)]
pub(crate) fn escape_json_string(s: &str) -> String {
    use std::fmt::Write;
    let mut out = String::with_capacity(s.len() + 4);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04X}", c as u32);
            }
            c => out.push(c),
        }
    }
    out
}


#[cfg(test)]
#[path = "events_tests.rs"]
mod tests;
