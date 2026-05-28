// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for `crate::events`.

#![allow(clippy::unwrap_used)]

use super::*;
use std::sync::Arc;
use std::sync::atomic::AtomicUsize;
use std::time::Duration;

/// Drains any leftover events from the shared ring buffer.
///
/// Tests share process-global state, so each test drains first.
fn drain_buffer() {
    lock_event_buffer().clear();
}

/// A test listener that counts callback invocations.
struct CountingListener {
    count: Arc<AtomicUsize>,
}

impl FfiEventListener for CountingListener {
    fn on_event(&self, _event_json: String) {
        self.count.fetch_add(1, Ordering::SeqCst);
    }
}

#[test]
fn test_register_unregister_roundtrip() {
    // Register a counting listener.
    let count = Arc::new(AtomicUsize::new(0));
    let listener: Arc<dyn FfiEventListener> = Arc::new(CountingListener {
        count: count.clone(),
    });
    register_event_listener_inner(listener).unwrap();

    // Fire an event — listener should receive it.
    let observer = AndroidObserver;
    observer.record_event(&ObserverEvent::HeartbeatTick);
    assert!(
        count.load(Ordering::SeqCst) >= 1,
        "listener should have received at least one event"
    );

    // Unregister, then fire another event and check that the count
    // does not increase. We wait briefly to let any in-flight
    // `record_event` calls from parallel tests that may have cloned
    // the Arc before our unregister to finish their callbacks.
    unregister_event_listener_inner().unwrap();
    std::thread::sleep(std::time::Duration::from_millis(10));
    let snapshot = count.load(Ordering::SeqCst);
    observer.record_event(&ObserverEvent::HeartbeatTick);
    std::thread::sleep(std::time::Duration::from_millis(10));

    // After unregister, new events from this test must not reach
    // the listener. The snapshot already accounts for any straggler
    // callbacks from parallel tests that cloned the Arc pre-unregister.
    assert_eq!(
        count.load(Ordering::SeqCst),
        snapshot,
        "listener should not receive events after unregister"
    );
}

#[test]
fn test_get_recent_events_returns_valid_json() {
    drain_buffer();

    let observer = AndroidObserver;
    observer.record_event(&ObserverEvent::HeartbeatTick);
    observer.record_event(&ObserverEvent::TurnComplete);

    let json_str = get_recent_events_inner(10).unwrap();
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    let arr = parsed.as_array().unwrap();
    // At least our 2 events; may be more if another test's events
    // landed between drain_buffer() and our record_event() calls.
    assert!(arr.len() >= 2, "expected >= 2 events, got {}", arr.len());

    // Verify structure of each event.
    for event in arr {
        assert!(event.get("id").is_some());
        assert!(event.get("timestamp_ms").is_some());
        assert!(event.get("kind").is_some());
        assert!(event.get("data").is_some());
    }
}

#[test]
fn test_get_recent_events_respects_limit() {
    drain_buffer();

    let observer = AndroidObserver;
    for _ in 0..5 {
        observer.record_event(&ObserverEvent::HeartbeatTick);
    }

    let json_str = get_recent_events_inner(3).unwrap();
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed.as_array().unwrap().len(), 3);
}

#[test]
fn test_format_event_json_llm_response() {
    let event = ObserverEvent::LlmResponse {
        model_provider: "openai".into(),
        model: "gpt-4".into(),
        duration: Duration::from_millis(150),
        success: true,
        error_message: None,
        input_tokens: Some(100),
        output_tokens: Some(50),
    };
    let json_str = format_event_json(42, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["id"], 42);
    assert_eq!(parsed["kind"], "llm_response");
    assert_eq!(parsed["data"]["provider"], "openai");
    assert_eq!(parsed["data"]["success"], true);
    assert!(parsed["data"]["error"].is_null());
    assert_eq!(parsed["data"]["input_tokens"], 100);
    assert_eq!(parsed["data"]["output_tokens"], 50);
}

#[test]
fn test_format_event_json_llm_response_with_error() {
    let event = ObserverEvent::LlmResponse {
        model_provider: "anthropic".into(),
        model: "claude-sonnet-4".into(),
        duration: Duration::from_secs(1),
        success: false,
        error_message: Some("rate limited".into()),
        input_tokens: None,
        output_tokens: None,
    };
    let json_str = format_event_json(99, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "llm_response");
    assert_eq!(parsed["data"]["error"], "rate limited");
    assert_eq!(parsed["data"]["success"], false);
    assert!(parsed["data"]["input_tokens"].is_null());
    assert!(parsed["data"]["output_tokens"].is_null());
}

#[test]
fn test_format_event_json_tool_call() {
    let event = ObserverEvent::ToolCall {
        tool: "shell".into(),
        tool_call_id: None,
        duration: Duration::from_millis(250),
        success: true,
        arguments: None,
        result: None,
    };
    let json_str = format_event_json(7, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "tool_call");
    assert_eq!(parsed["data"]["tool"], "shell");
    assert_eq!(parsed["data"]["duration_ms"], 250);
}

#[test]
fn test_format_event_json_heartbeat_tick() {
    let json_str = format_event_json(0, &ObserverEvent::HeartbeatTick);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "heartbeat_tick");
    assert_eq!(parsed["data"], serde_json::json!({}));
}

#[test]
fn test_format_event_json_error_with_quotes() {
    let event = ObserverEvent::Error {
        component: "gateway".into(),
        message: r#"failed to parse "config""#.into(),
    };
    let json_str = format_event_json(1, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "error");
    assert!(
        parsed["data"]["message"]
            .as_str()
            .unwrap()
            .contains("config")
    );
}

#[test]
fn test_format_event_json_agent_end() {
    let event = ObserverEvent::AgentEnd {
        model_provider: "anthropic".into(),
        model: "claude-sonnet-4".into(),
        duration: Duration::from_secs(5),
        tokens_used: Some(1200),
        cost_usd: Some(0.042),
    };
    let json_str = format_event_json(3, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "agent_end");
    assert_eq!(parsed["data"]["provider"], "anthropic");
    assert_eq!(parsed["data"]["model"], "claude-sonnet-4");
    assert_eq!(parsed["data"]["tokens"], 1200);
    assert_eq!(parsed["data"]["duration_ms"], 5000);
    assert_eq!(parsed["data"]["cost_usd"], 0.042);
}

#[test]
fn test_format_event_json_agent_end_no_tokens() {
    let event = ObserverEvent::AgentEnd {
        model_provider: "openai".into(),
        model: "gpt-4o".into(),
        duration: Duration::from_millis(100),
        tokens_used: None,
        cost_usd: None,
    };
    let json_str = format_event_json(4, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert!(parsed["data"]["tokens"].is_null());
    assert!(parsed["data"]["cost_usd"].is_null());
}

#[test]
fn test_ring_buffer_drops_oldest() {
    drain_buffer();

    let observer = AndroidObserver;
    for _ in 0..(EVENT_BUFFER_CAPACITY + 50) {
        observer.record_event(&ObserverEvent::HeartbeatTick);
    }

    let buf = lock_event_buffer();
    assert!(
        buf.len() <= EVENT_BUFFER_CAPACITY,
        "buffer exceeded capacity: {} > {}",
        buf.len(),
        EVENT_BUFFER_CAPACITY,
    );
}

#[test]
fn test_android_observer_name() {
    let observer = AndroidObserver;
    assert_eq!(observer.name(), "android");
}

#[test]
fn test_android_observer_as_any_downcast() {
    let observer = AndroidObserver;
    let any_ref = observer.as_any();
    assert!(
        any_ref.downcast_ref::<AndroidObserver>().is_some(),
        "as_any() should allow downcasting back to AndroidObserver"
    );
}

#[test]
fn test_escape_json_string() {
    assert_eq!(escape_json_string(r#"a"b"#), r#"a\"b"#);
    assert_eq!(escape_json_string(r"a\b"), r"a\\b");
    assert_eq!(escape_json_string("plain"), "plain");
    assert_eq!(escape_json_string("line\nbreak"), "line\\nbreak");
    assert_eq!(escape_json_string("car\rret"), "car\\rret");
    assert_eq!(escape_json_string("tab\there"), "tab\\there");
    assert_eq!(escape_json_string("null\x00byte"), "null\\u0000byte");
    assert_eq!(escape_json_string("bell\x07ring"), "bell\\u0007ring");
}

#[test]
fn test_format_event_json_llm_request() {
    let event = ObserverEvent::LlmRequest {
        model_provider: "openai".into(),
        model: "gpt-4o".into(),
        messages_count: 5,
    };
    let json_str = format_event_json(10, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "llm_request");
    assert_eq!(parsed["data"]["provider"], "openai");
    assert_eq!(parsed["data"]["model"], "gpt-4o");
    assert_eq!(parsed["data"]["messages"], 5);
}

#[test]
fn test_format_event_json_tool_call_start() {
    let event = ObserverEvent::ToolCallStart {
        tool: "web_search".into(),
        tool_call_id: None,
        arguments: None,
    };
    let json_str = format_event_json(11, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "tool_call_start");
    assert_eq!(parsed["data"]["tool"], "web_search");
}

#[test]
fn test_format_event_json_channel_message() {
    let event = ObserverEvent::ChannelMessage {
        channel: "discord".into(),
        direction: "inbound".into(),
    };
    let json_str = format_event_json(12, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "channel_message");
    assert_eq!(parsed["data"]["channel"], "discord");
    assert_eq!(parsed["data"]["direction"], "inbound");
}

#[test]
fn test_format_event_json_agent_start() {
    let event = ObserverEvent::AgentStart {
        model_provider: "anthropic".into(),
        model: "claude-sonnet-4".into(),
    };
    let json_str = format_event_json(13, &event);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "agent_start");
    assert_eq!(parsed["data"]["provider"], "anthropic");
    assert_eq!(parsed["data"]["model"], "claude-sonnet-4");
}

#[test]
fn test_format_event_json_turn_complete() {
    let json_str = format_event_json(14, &ObserverEvent::TurnComplete);
    let parsed: serde_json::Value = serde_json::from_str(&json_str).unwrap();
    assert_eq!(parsed["kind"], "turn_complete");
    assert_eq!(parsed["data"], serde_json::json!({}));
}

#[test]
fn test_get_recent_events_empty() {
    drain_buffer();
    let json_str = get_recent_events_inner(10).unwrap();
    assert_eq!(json_str, "[]");
}
