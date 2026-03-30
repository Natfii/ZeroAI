// Copyright (c) 2026 @Natfii. All rights reserved.

//! Server-Sent Events (SSE) stream for real-time event delivery.
//!
//! Wraps the broadcast channel in AppState to deliver events to web dashboard clients.
//! Includes memory event helpers for the Brain Visualizer (fact lifecycle,
//! consolidation progress) with per-fact debouncing on `memory:fact_accessed`.

use super::{require_pairing_auth, AppState};
use axum::{
    extract::State,
    http::HeaderMap,
    response::{
        sse::{Event, KeepAlive, Sse},
        IntoResponse,
    },
};
use std::collections::HashMap;
use std::convert::Infallible;
use std::sync::{LazyLock, Mutex};
use std::time::Instant;
use tokio::sync::broadcast;
use tokio_stream::wrappers::BroadcastStream;
use tokio_stream::StreamExt;

/// Tracks the last time a `memory:fact_accessed` event was emitted for each
/// fact ID. Guards against flooding the SSE stream when the same fact is
/// recalled repeatedly in a short window.
static FACT_ACCESS_DEBOUNCE: LazyLock<Mutex<HashMap<String, Instant>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Minimum interval (in seconds) between consecutive `memory:fact_accessed`
/// events for the same fact ID.
const FACT_ACCESS_DEBOUNCE_SECS: u64 = 5;

/// GET /api/events — SSE event stream
pub async fn handle_sse_events(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if let Err(error) = require_pairing_auth(&state, &headers) {
        return error.into_response();
    }

    let rx = state.event_tx.subscribe();
    let stream = BroadcastStream::new(rx).filter_map(
        |result: Result<
            serde_json::Value,
            tokio_stream::wrappers::errors::BroadcastStreamRecvError,
        >| {
            match result {
                Ok(value) => Some(Ok::<_, Infallible>(
                    Event::default().data(value.to_string()),
                )),
                Err(_) => None,
            }
        },
    );

    Sse::new(stream)
        .keep_alive(KeepAlive::default())
        .into_response()
}

// ---------------------------------------------------------------------------
// Memory SSE event helpers
// ---------------------------------------------------------------------------

/// Emit a memory-related SSE event.
///
/// Builds a JSON envelope with `type`, `data`, and `timestamp` fields and
/// sends it on the broadcast channel. Receivers that have been dropped are
/// silently ignored.
pub fn emit_memory_event(
    tx: &broadcast::Sender<serde_json::Value>,
    event_type: &str,
    data: &serde_json::Value,
) {
    let event = serde_json::json!({
        "type": event_type,
        "data": data,
        "timestamp": chrono::Utc::now().to_rfc3339(),
    });
    let _ = tx.send(event);
}

/// Emit a `memory:fact_created` event.
pub fn emit_fact_created(
    tx: &broadcast::Sender<serde_json::Value>,
    fact_id: &str,
    fact_key: &str,
) {
    emit_memory_event(
        tx,
        "memory:fact_created",
        &serde_json::json!({
            "fact_id": fact_id,
            "key": fact_key,
        }),
    );
}

/// Emit a `memory:fact_accessed` event with per-fact debouncing.
///
/// If the same `fact_id` was emitted less than
/// [`FACT_ACCESS_DEBOUNCE_SECS`] seconds ago the call is a no-op.
pub fn emit_fact_accessed(
    tx: &broadcast::Sender<serde_json::Value>,
    fact_id: &str,
    fact_key: &str,
) {
    let now = Instant::now();
    let mut map = FACT_ACCESS_DEBOUNCE
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if let Some(last) = map.get(fact_id) {
        if now.duration_since(*last).as_secs() < FACT_ACCESS_DEBOUNCE_SECS {
            return;
        }
    }
    map.insert(fact_id.to_string(), now);
    drop(map); // release lock before send
    emit_memory_event(
        tx,
        "memory:fact_accessed",
        &serde_json::json!({
            "fact_id": fact_id,
            "key": fact_key,
        }),
    );
}

/// Emit a `memory:consolidation_complete` event.
pub fn emit_consolidation_complete(
    tx: &broadcast::Sender<serde_json::Value>,
    facts_merged: u64,
    facts_created: u64,
) {
    emit_memory_event(
        tx,
        "memory:consolidation_complete",
        &serde_json::json!({
            "facts_merged": facts_merged,
            "facts_created": facts_created,
        }),
    );
}

/// Broadcast observer that forwards events to the SSE broadcast channel.
pub struct BroadcastObserver {
    inner: Box<dyn crate::observability::Observer>,
    tx: tokio::sync::broadcast::Sender<serde_json::Value>,
}

impl BroadcastObserver {
    pub fn new(
        inner: Box<dyn crate::observability::Observer>,
        tx: tokio::sync::broadcast::Sender<serde_json::Value>,
    ) -> Self {
        Self { inner, tx }
    }
}

impl crate::observability::Observer for BroadcastObserver {
    fn record_event(&self, event: &crate::observability::ObserverEvent) {
        self.inner.record_event(event);

        let json = match event {
            crate::observability::ObserverEvent::LlmRequest {
                provider, model, ..
            } => serde_json::json!({
                "type": "llm_request",
                "provider": provider,
                "model": model,
                "timestamp": chrono::Utc::now().to_rfc3339(),
            }),
            crate::observability::ObserverEvent::ToolCall {
                tool,
                duration,
                success,
            } => serde_json::json!({
                "type": "tool_call",
                "tool": tool,
                "duration_ms": duration.as_millis(),
                "success": success,
                "timestamp": chrono::Utc::now().to_rfc3339(),
            }),
            crate::observability::ObserverEvent::ToolCallStart { tool } => serde_json::json!({
                "type": "tool_call_start",
                "tool": tool,
                "timestamp": chrono::Utc::now().to_rfc3339(),
            }),
            crate::observability::ObserverEvent::Error { component, message } => {
                serde_json::json!({
                    "type": "error",
                    "component": component,
                    "message": message,
                    "timestamp": chrono::Utc::now().to_rfc3339(),
                })
            }
            crate::observability::ObserverEvent::AgentStart { provider, model } => {
                serde_json::json!({
                    "type": "agent_start",
                    "provider": provider,
                    "model": model,
                    "timestamp": chrono::Utc::now().to_rfc3339(),
                })
            }
            crate::observability::ObserverEvent::AgentEnd {
                provider,
                model,
                duration,
                tokens_used,
                cost_usd,
            } => serde_json::json!({
                "type": "agent_end",
                "provider": provider,
                "model": model,
                "duration_ms": duration.as_millis(),
                "tokens_used": tokens_used,
                "cost_usd": cost_usd,
                "timestamp": chrono::Utc::now().to_rfc3339(),
            }),
            _ => return,
        };

        let _ = self.tx.send(json);
    }

    fn record_metric(&self, metric: &crate::observability::traits::ObserverMetric) {
        self.inner.record_metric(metric);
    }

    fn flush(&self) {
        self.inner.flush();
    }

    fn name(&self) -> &str {
        "broadcast"
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}
