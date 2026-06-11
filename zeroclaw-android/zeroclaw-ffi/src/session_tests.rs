// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for [`crate::session`]. Loaded via
//! `#[path = "session_tests.rs"] mod tests;` from `session.rs`.

#![allow(clippy::unwrap_used)]

use super::*;
use crate::session_history::{build_compaction_transcript, trim_history};
use crate::session_text::truncate_chars;
use std::sync::Mutex as StdMutex;

/// A test listener that records all callback invocations as strings.
///
/// Each event is formatted as `"callback_name:payload"` and pushed
/// onto the internal vector for later assertion.
struct RecordingListener {
    /// Accumulated event strings.
    events: StdMutex<Vec<String>>,
}

impl RecordingListener {
    /// Creates a new empty recording listener.
    fn new() -> Self {
        Self {
            events: StdMutex::new(Vec::new()),
        }
    }

    /// Returns a snapshot of all recorded events.
    fn events(&self) -> Vec<String> {
        self.events.lock().unwrap().clone()
    }
}

impl FfiSessionListener for RecordingListener {
    fn on_thinking(&self, text: String) {
        self.events.lock().unwrap().push(format!("thinking:{text}"));
    }

    fn on_response_chunk(&self, text: String) {
        self.events
            .lock()
            .unwrap()
            .push(format!("response_chunk:{text}"));
    }

    fn on_tool_start(&self, name: String, arguments_hint: String) {
        self.events
            .lock()
            .unwrap()
            .push(format!("tool_start:{name}:{arguments_hint}"));
    }

    fn on_tool_result(&self, name: String, success: bool, duration_secs: u64) {
        self.events
            .lock()
            .unwrap()
            .push(format!("tool_result:{name}:{success}:{duration_secs}"));
    }

    fn on_tool_output(&self, name: String, output: String) {
        self.events
            .lock()
            .unwrap()
            .push(format!("tool_output:{name}:{output}"));
    }

    fn on_progress(&self, phase: FfiProgressPhase) {
        self.events
            .lock()
            .unwrap()
            .push(format!("progress:{phase:?}"));
    }

    fn on_progress_clear(&self) {
        self.events
            .lock()
            .unwrap()
            .push("progress_clear".to_string());
    }

    fn on_compaction(&self, summary: String) {
        self.events
            .lock()
            .unwrap()
            .push(format!("compaction:{summary}"));
    }

    fn on_complete(&self, full_response: String) {
        self.events
            .lock()
            .unwrap()
            .push(format!("complete:{full_response}"));
    }

    fn on_error(&self, error: String) {
        self.events.lock().unwrap().push(format!("error:{error}"));
    }

    fn on_cancelled(&self) {
        self.events.lock().unwrap().push("cancelled".to_string());
    }
}

// ── truncate_chars tests ────────────────────────────────────────

#[test]
fn test_truncate_chars_short_string() {
    let result = truncate_chars("hello", 10);
    assert_eq!(result, "hello");
}

#[test]
fn test_truncate_chars_long_string() {
    let input = "a".repeat(100);
    let result = truncate_chars(&input, 10);
    assert!(result.ends_with("..."));
    assert!(result.len() <= 14); // 10 chars + "..."
}

// ── trim_history tests ──────────────────────────────────────────

#[test]
fn test_trim_history_within_limit() {
    let mut history = vec![
        ChatMessage::system("system"),
        ChatMessage::user("hello"),
        ChatMessage::assistant("hi"),
    ];
    trim_history(&mut history, 10);
    assert_eq!(history.len(), 3);
}

#[test]
fn test_trim_history_exceeds_limit() {
    let mut history = vec![ChatMessage::system("system")];
    for i in 0..10 {
        history.push(ChatMessage::user(format!("msg {i}")));
    }
    assert_eq!(history.len(), 11); // 1 system + 10 user

    trim_history(&mut history, 5);
    assert_eq!(history.len(), 6); // 1 system + 5 user
    assert_eq!(history[0].role, "system");
    assert_eq!(history[1].content, "msg 5");
}

#[test]
fn test_trim_history_no_system_prompt() {
    let mut history: Vec<ChatMessage> = (0..10)
        .map(|i| ChatMessage::user(format!("msg {i}")))
        .collect();

    trim_history(&mut history, 3);
    assert_eq!(history.len(), 3);
    assert_eq!(history[0].content, "msg 7");
}

// ── build_compaction_transcript tests ────────────────────────────

#[test]
fn test_build_compaction_transcript_basic() {
    let messages = vec![
        ChatMessage::user("What is Rust?"),
        ChatMessage::assistant("Rust is a systems programming language."),
    ];
    let transcript = build_compaction_transcript(&messages);
    assert!(transcript.contains("USER: What is Rust?"));
    assert!(transcript.contains("ASSISTANT: Rust is a systems programming language."));
}

// ── truncate_tool_args_hint tests ───────────────────────────────

#[test]
fn test_truncate_tool_args_hint_shell() {
    let hint = truncate_tool_args_hint("shell", r#"{"command":"ls -la"}"#);
    assert_eq!(hint, "ls -la");
}

#[test]
fn test_truncate_tool_args_hint_file_read() {
    let hint = truncate_tool_args_hint("file_read", r#"{"path":"/etc/hosts"}"#);
    assert_eq!(hint, "/etc/hosts");
}

#[test]
fn test_truncate_tool_args_hint_unknown_tool() {
    let hint = truncate_tool_args_hint("unknown", r#"{"query":"search term"}"#);
    assert_eq!(hint, "search term");
}

#[test]
fn test_truncate_tool_args_hint_invalid_json() {
    let hint = truncate_tool_args_hint("shell", "not json");
    assert!(hint.is_empty());
}

// ── build_native_assistant_history tests ─────────────────────────

#[test]
fn test_build_native_assistant_history_basic() {
    let calls = vec![zeroclaw::providers::ToolCall {
        id: "call_123".into(),
        name: "shell".into(),
        arguments: r#"{"command":"ls"}"#.into(),
        extra_content: None,
    }];

    let result = build_native_assistant_history("Let me check", &calls, None);
    let parsed: serde_json::Value = serde_json::from_str(&result).unwrap();

    assert_eq!(parsed["content"], "Let me check");
    assert_eq!(parsed["tool_calls"][0]["id"], "call_123");
    assert_eq!(parsed["tool_calls"][0]["function"]["name"], "shell");
    assert!(parsed.get("reasoning_content").is_none());
}

#[test]
fn test_build_native_assistant_history_with_reasoning() {
    let calls = vec![zeroclaw::providers::ToolCall {
        id: "call_456".into(),
        name: "file_read".into(),
        arguments: r#"{"path":"test.rs"}"#.into(),
        extra_content: None,
    }];

    let result =
        build_native_assistant_history("Reading file", &calls, Some("thinking about it"));
    let parsed: serde_json::Value = serde_json::from_str(&result).unwrap();

    assert_eq!(parsed["reasoning_content"], "thinking about it");
}

// ── stream_response_text tests ──────────────────────────────────

#[test]
fn test_stream_response_text_short() {
    let recording = Arc::new(RecordingListener::new());
    let listener: Arc<dyn FfiSessionListener> = recording.clone();
    let token = CancellationToken::new();

    let result = stream_response_text("Hello world", &listener, &token);
    assert!(result.is_ok());

    let events = recording.events();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0], "response_chunk:Hello world");
}

#[test]
fn test_stream_response_text_cancelled() {
    let recording = Arc::new(RecordingListener::new());
    let listener: Arc<dyn FfiSessionListener> = recording.clone();
    let token = CancellationToken::new();
    token.cancel();

    let result = stream_response_text("Hello world", &listener, &token);
    assert!(result.is_err());
}

// ── session lifecycle unit tests (no daemon) ────────────────────

#[test]
fn test_session_send_no_session() {
    *lock_session() = None;
    let listener = Arc::new(RecordingListener::new());
    let result = session_send_inner("hello".into(), vec![], vec![], listener);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("no active session"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_session_send_oversized_message() {
    let listener = Arc::new(RecordingListener::new());
    let big_message = "x".repeat(MAX_MESSAGE_BYTES + 1);
    let result = session_send_inner(big_message, vec![], vec![], listener);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::ConfigError { detail } => {
            assert!(detail.contains("too large"));
        }
        other => panic!("expected ConfigError, got {other:?}"),
    }
}

#[test]
fn test_session_send_mismatched_image_arrays() {
    let listener = Arc::new(RecordingListener::new());
    let result = session_send_inner("hi".into(), vec!["base64data".into()], vec![], listener);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::ConfigError { detail } => {
            assert!(detail.contains("image_data length"));
        }
        other => panic!("expected ConfigError, got {other:?}"),
    }
}

#[test]
fn test_session_send_too_many_images() {
    let listener = Arc::new(RecordingListener::new());
    let images = vec!["img".to_string(); MAX_SESSION_IMAGES + 1];
    let mimes = vec!["image/png".to_string(); MAX_SESSION_IMAGES + 1];
    let result = session_send_inner("hi".into(), images, mimes, listener);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::ConfigError { detail } => {
            assert!(detail.contains("too many images"));
        }
        other => panic!("expected ConfigError, got {other:?}"),
    }
}

#[test]
fn test_compose_multimodal_message_no_images() {
    let result = compose_multimodal_message("hello world", &[], &[]);
    assert_eq!(result, "hello world");
}

#[test]
fn test_compose_multimodal_message_with_images() {
    let result =
        compose_multimodal_message("describe this", &["abc123".into()], &["image/png".into()]);
    assert!(result.starts_with("describe this"));
    assert!(result.contains("[IMAGE:data:image/png;base64,abc123]"));
}

#[test]
fn test_session_cancel_no_send() {
    session_cancel_inner();
}

#[test]
fn test_session_clear_no_session() {
    *lock_session() = None;
    let result = session_clear_inner();
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("no active session"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_session_history_no_session() {
    *lock_session() = None;
    let result = session_history_inner();
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("no active session"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_session_destroy_no_session() {
    *lock_session() = None;
    let result = session_destroy_inner();
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("no active session"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

// ── append_android_identity_extras tests ────────────────────────

#[test]
fn test_android_identity_extras_user_name() {
    let config = zeroclaw::config::IdentityConfig {
        format: "aieos".into(),
        aieos_path: None,
        aieos_inline: Some(
            r#"{"identity":{"names":{"first":"Nova"},"user_name":"Alice","timezone":"US/Eastern","communication_style":"casual"}}"#.into(),
        ),
    };
    let mut prompt = String::from("## Identity\n\n**Name:** Nova\n");
    append_android_identity_extras(&mut prompt, &config);
    assert!(prompt.contains("**User's name:** Alice"));
    assert!(prompt.contains("**Timezone:** US/Eastern"));
    assert!(prompt.contains("**Preferred communication style:** casual"));
}

#[test]
fn test_android_identity_extras_empty_inline() {
    let config = zeroclaw::config::IdentityConfig {
        format: "aieos".into(),
        aieos_path: None,
        aieos_inline: None,
    };
    let mut prompt = String::from("base prompt");
    append_android_identity_extras(&mut prompt, &config);
    assert_eq!(prompt, "base prompt");
}

#[test]
fn test_android_identity_extras_no_extra_fields() {
    let config = zeroclaw::config::IdentityConfig {
        format: "aieos".into(),
        aieos_path: None,
        aieos_inline: Some(r#"{"identity":{"names":{"first":"Nova"}}}"#.into()),
    };
    let mut prompt = String::from("base prompt");
    append_android_identity_extras(&mut prompt, &config);
    assert_eq!(prompt, "base prompt");
}

// ── SessionStateGuard tests ────────────────────────────────────

#[test]
fn test_guard_take_disarms_drop() {
    let history = vec![ChatMessage::user("hello")];
    let guard = SessionStateGuard::new(history, vec![]);

    let (h, t) = guard.take().unwrap();
    assert_eq!(h.len(), 1);
    assert!(t.is_empty());
    // Drop runs here but is a no-op (defused).
}

#[test]
fn test_guard_state_mut_provides_references() {
    let history = vec![ChatMessage::user("one")];
    let mut guard = SessionStateGuard::new(history, vec![]);

    let (h, _t) = guard.state_mut().unwrap();
    h.push(ChatMessage::assistant("two"));
    assert_eq!(h.len(), 2);

    let (taken_h, _) = guard.take().unwrap();
    assert_eq!(taken_h.len(), 2);
    assert_eq!(taken_h[1].content, "two");
}

#[test]
fn test_guard_drop_without_take_keeps_state() {
    // Verify that dropping a guard without calling take() does NOT
    // consume the state (it's available for the Drop impl to use).
    // The actual SESSION restoration is tested implicitly through
    // session_send_inner's panic-safety.
    let history = vec![ChatMessage::user("preserved")];
    let guard = SessionStateGuard::new(history, vec![]);
    // Drop fires here — without a live SESSION it's a no-op,
    // but critically it does NOT panic.
    drop(guard);
}

#[test]
#[ignore = "flaky under parallel execution due to shared SESSION mutex"]
fn test_guard_drop_restores_session_on_panic() {
    *lock_session() = None;

    {
        let mut guard = lock_session();
        *guard = Some(Session {
            history: vec![ChatMessage::user("preserved")],
            config: zeroclaw::Config::default(),
            system_prompt: String::new(),
            model: String::new(),
            temperature: 0.7,
            provider_name: String::new(),
            tools_registry: vec![],
        });
    }

    let _panic_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let (history, tools) = {
            let mut guard = lock_session();
            let session = guard.as_mut().unwrap();
            (
                std::mem::take(&mut session.history),
                std::mem::take(&mut session.tools_registry),
            )
        };
        let _state_guard = SessionStateGuard::new(history, tools);
        panic!("simulated unwind");
    }));

    {
        let guard = lock_session();
        let session = guard.as_ref().expect("session should exist");
        assert_eq!(session.history.len(), 1);
    }

    *lock_session() = None;
}

#[test]
fn test_poisoned_cancel_token_recovery() {
    let _panic_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _guard = CANCEL_TOKEN.lock().unwrap();
        panic!("poison the mutex");
    }));

    let mut guard = lock_cancel_token();
    *guard = Some(CancellationToken::new());
    assert!(guard.is_some());
    *guard = None;
}

// ── extract_thinking_from_text tests ────────────────────────────

#[test]
fn test_extract_thinking_basic_think_tag() {
    let input = "<think>Planning my approach</think>Here is the answer.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Here is the answer.");
    assert_eq!(thinking, "Planning my approach");
}

#[test]
fn test_extract_thinking_case_insensitive() {
    let input = "<THINK>Uppercase tags</THINK>Result text.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Result text.");
    assert_eq!(thinking, "Uppercase tags");
}

#[test]
fn test_extract_thinking_mixed_case() {
    let input = "<Think>Mixed case</Think>Output.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Output.");
    assert_eq!(thinking, "Mixed case");
}

#[test]
fn test_extract_thinking_multiple_blocks() {
    let input = "<think>First thought</think>Middle text<think>Second thought</think>End.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Middle textEnd.");
    assert_eq!(thinking, "First thought\nSecond thought");
}

#[test]
fn test_extract_thinking_no_tags() {
    let input = "Plain response with no thinking tags.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Plain response with no thinking tags.");
    assert_eq!(thinking, "");
}

#[test]
fn test_extract_thinking_empty_tag() {
    let input = "<think></think>Just the answer.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Just the answer.");
    assert_eq!(thinking, "");
}

#[test]
fn test_extract_thinking_whitespace_only_tag() {
    let input = "<think>   \n  </think>Answer.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Answer.");
    assert_eq!(thinking, "");
}

#[test]
fn test_extract_thinking_different_tag_types() {
    let input = "<thinking>Deep analysis</thinking>Response here.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Response here.");
    assert_eq!(thinking, "Deep analysis");
}

#[test]
fn test_extract_thinking_reflection_tag() {
    let input = "<reflection>Checking my work</reflection>Final answer.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Final answer.");
    assert_eq!(thinking, "Checking my work");
}

#[test]
fn test_extract_thinking_unclosed_tag_preserved() {
    let input = "<think>Unclosed thinking block without end tag";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "<think>Unclosed thinking block without end tag");
    assert_eq!(thinking, "");
}

#[test]
fn test_extract_thinking_preserves_whitespace() {
    let input = "Before  <think>Thought</think>  After";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Before    After");
    assert_eq!(thinking, "Thought");
}

#[test]
fn test_extract_thinking_multiline_content() {
    let input = "<think>\nLine 1\nLine 2\nLine 3\n</think>The response.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "The response.");
    assert_eq!(thinking, "Line 1\nLine 2\nLine 3");
}

#[test]
fn extract_thinking_reasoning_tag() {
    let input = "<reasoning>Step 1: check input\nStep 2: validate</reasoning>Final answer.";
    let (clean, thinking) = extract_thinking_from_text(input);
    assert_eq!(clean, "Final answer.");
    assert_eq!(thinking, "Step 1: check input\nStep 2: validate");
}

// ── parse_xml_tool_calls tests ──────────────────────────────────

#[test]
fn test_parse_xml_single_tool_call() {
    let input =
        r#"<tool_call>{"name": "web_search", "arguments": {"query": "rust lang"}}</tool_call>"#;
    let (clean, calls) = parse_xml_tool_calls(input);
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].name, "web_search");
    assert!(calls[0].arguments.contains("rust lang"));
    assert_eq!(calls[0].id, "xmltc_1");
    assert_eq!(clean.trim(), "");
}

#[test]
fn test_parse_xml_multiple_tool_calls() {
    let input = concat!(
        r#"<tool_call>{"name": "web_search", "arguments": {"query": "a"}}</tool_call>"#,
        " ",
        r#"<tool_call>{"name": "web_fetch", "arguments": {"url": "https://example.com"}}</tool_call>"#,
    );
    let (clean, calls) = parse_xml_tool_calls(input);
    assert_eq!(calls.len(), 2);
    assert_eq!(calls[0].name, "web_search");
    assert_eq!(calls[0].id, "xmltc_1");
    assert_eq!(calls[1].name, "web_fetch");
    assert_eq!(calls[1].id, "xmltc_2");
    assert_eq!(clean.trim(), "");
}

#[test]
fn test_parse_xml_no_tool_calls() {
    let input = "Just a normal response with no tool calls.";
    let (clean, calls) = parse_xml_tool_calls(input);
    assert!(calls.is_empty());
    assert_eq!(clean, input);
}

#[test]
fn test_parse_xml_malformed_json_skipped() {
    let input = "<tool_call>this is not json</tool_call>";
    let (clean, calls) = parse_xml_tool_calls(input);
    assert!(calls.is_empty());
    assert_eq!(clean.trim(), "");
}

#[test]
fn test_parse_xml_case_insensitive_tags() {
    let input =
        r#"<Tool_Call>{"name": "web_search", "arguments": {"query": "test"}}</TOOL_CALL>"#;
    let (clean, calls) = parse_xml_tool_calls(input);
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].name, "web_search");
    assert_eq!(clean.trim(), "");
}

#[test]
fn test_parse_xml_mixed_text_and_calls() {
    let input = concat!(
        "Let me search for that. ",
        r#"<tool_call>{"name": "web_search", "arguments": {"query": "weather"}}</tool_call>"#,
        " I found the results.",
    );
    let (clean, calls) = parse_xml_tool_calls(input);
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].name, "web_search");
    assert_eq!(clean, "Let me search for that.  I found the results.");
}

#[test]
fn test_parse_xml_missing_name_skipped() {
    let input = r#"<tool_call>{"arguments": {"key": "val"}}</tool_call>"#;
    let (_, calls) = parse_xml_tool_calls(input);
    assert!(calls.is_empty());
}

#[test]
fn test_parse_xml_empty_name_skipped() {
    let input = r#"<tool_call>{"name": "", "arguments": {}}</tool_call>"#;
    let (_, calls) = parse_xml_tool_calls(input);
    assert!(calls.is_empty());
}

#[test]
fn test_parse_xml_missing_arguments_defaults_to_empty() {
    let input = r#"<tool_call>{"name": "list_tools"}</tool_call>"#;
    let (_, calls) = parse_xml_tool_calls(input);
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].name, "list_tools");
    assert_eq!(calls[0].arguments, "{}");
}

#[test]
fn test_parse_xml_unclosed_tag_ignored() {
    let input = r#"<tool_call>{"name": "web_search", "arguments": {"q": "x"}}"#;
    let (clean, calls) = parse_xml_tool_calls(input);
    assert!(calls.is_empty());
    assert_eq!(clean, input);
}

#[test]
fn test_parse_xml_multiline_call() {
    let input = "<tool_call>\n{\n  \"name\": \"recall_memory\",\n  \"arguments\": {\"query\": \"user prefs\"}\n}\n</tool_call>";
    let (_, calls) = parse_xml_tool_calls(input);
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].name, "recall_memory");
    assert!(calls[0].arguments.contains("user prefs"));
}

