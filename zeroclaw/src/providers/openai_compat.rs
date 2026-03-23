// Copyright (c) 2026 @Natfii. All rights reserved.

//! Shared OpenAI-compatible wire format helpers.
//!
//! Stateless pure functions for building requests, deserializing tool calls,
//! and stripping think tags. Used by `deepseek.rs`,
//! `qwen.rs`, and any future OpenAI-compatible providers.
//!
//! **Safety constraints:**
//! - All functions are stateless — no `static`, `OnceLock`, or `Mutex`.
//! - No `.unwrap()` or `.expect()` on `Regex::new()`.
//! - All error conditions return `Result` or `Option`.

use serde::{Deserialize, Serialize};

use crate::providers::traits::{ChatMessage, ToolCall};

/// Standard OpenAI-compatible chat request body.
#[derive(Debug, Serialize)]
pub(crate) struct CompatChatRequest {
    pub model: String,
    pub messages: Vec<CompatMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<u32>,
    pub temperature: f64,
    pub stream: bool,
}

/// Wire message in OpenAI chat completions format.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct CompatMessage {
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<CompatToolCall>>,
}

/// Wire tool call in OpenAI format.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct CompatToolCall {
    #[serde(default)]
    pub id: Option<String>,
    #[serde(rename = "type", default)]
    pub kind: Option<String>,
    pub function: CompatFunctionCall,
    /// DeepSeek sometimes puts arguments here instead of in `function.arguments`.
    #[serde(default)]
    pub parameters: Option<serde_json::Value>,
}

/// Wire function call.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct CompatFunctionCall {
    pub name: String,
    #[serde(default)]
    pub arguments: String,
}

/// Wire response from OpenAI-compatible chat completions.
#[derive(Debug, Deserialize)]
pub(crate) struct CompatChatResponse {
    pub choices: Vec<CompatChoice>,
    #[serde(default)]
    pub usage: Option<CompatUsage>,
}

/// A single choice in the response.
#[derive(Debug, Deserialize)]
pub(crate) struct CompatChoice {
    pub message: CompatResponseMessage,
}

/// The message within a response choice.
#[derive(Debug, Deserialize)]
pub(crate) struct CompatResponseMessage {
    #[serde(default)]
    pub content: Option<String>,
    #[serde(default)]
    pub reasoning_content: Option<String>,
    #[serde(default)]
    pub tool_calls: Option<Vec<CompatToolCall>>,
}

/// Token usage.
#[derive(Debug, Deserialize)]
pub(crate) struct CompatUsage {
    #[serde(default)]
    pub prompt_tokens: Option<u64>,
    #[serde(default)]
    pub completion_tokens: Option<u64>,
}

/// Convert internal `ChatMessage` slice to wire `CompatMessage` vec.
///
/// Handles assistant tool-call messages and tool result messages.
pub(crate) fn convert_messages(messages: &[ChatMessage]) -> Vec<CompatMessage> {
    messages
        .iter()
        .map(|m| {
            if m.role == "assistant" {
                if let Ok(value) = serde_json::from_str::<serde_json::Value>(&m.content) {
                    if let Some(tool_calls_value) = value.get("tool_calls") {
                        if let Ok(parsed_calls) =
                            serde_json::from_value::<Vec<CompatToolCall>>(tool_calls_value.clone())
                        {
                            let content = value
                                .get("content")
                                .and_then(|c| c.as_str())
                                .map(|s| serde_json::Value::String(s.to_string()));
                            return CompatMessage {
                                role: "assistant".to_string(),
                                content,
                                tool_call_id: None,
                                tool_calls: Some(parsed_calls),
                            };
                        }
                    }
                }
            }
            if m.role == "tool" {
                if let Ok(value) = serde_json::from_str::<serde_json::Value>(&m.content) {
                    let tool_call_id = value
                        .get("tool_call_id")
                        .and_then(|v| v.as_str())
                        .map(ToString::to_string);
                    let content = value
                        .get("content")
                        .cloned()
                        .unwrap_or(serde_json::Value::String(m.content.clone()));
                    return CompatMessage {
                        role: "tool".to_string(),
                        content: Some(content),
                        tool_call_id,
                        tool_calls: None,
                    };
                }
            }
            CompatMessage {
                role: m.role.clone(),
                content: Some(serde_json::Value::String(m.content.clone())),
                tool_call_id: None,
                tool_calls: None,
            }
        })
        .collect()
}

/// Normalize tool calls, handling the DeepSeek `parameters` vs `arguments` quirk.
///
/// If `function.arguments` is empty but `parameters` exists, serialize `parameters`
/// into `function.arguments`.
pub(crate) fn normalize_tool_calls(tool_calls: &mut [CompatToolCall]) {
    for tc in tool_calls.iter_mut() {
        if tc.function.arguments.is_empty() {
            if let Some(ref params) = tc.parameters {
                if let Ok(serialized) = serde_json::to_string(params) {
                    tc.function.arguments = serialized;
                }
            }
        }
    }
}

/// Parse tool calls from a response into internal `ToolCall` format.
pub(crate) fn extract_tool_calls(compat_calls: &[CompatToolCall]) -> Vec<ToolCall> {
    compat_calls
        .iter()
        .map(|tc| ToolCall {
            id: tc.id.clone().unwrap_or_default(),
            name: tc.function.name.clone(),
            arguments: tc.function.arguments.clone(),
        })
        .collect()
}

/// Strip `<think>...</think>` tags from content using stack-based parsing.
///
/// Returns `(clean_content, thinking_content)`. Uses `find`/`replace_range`
/// pattern matching `extract_thinking_from_text` in session.rs — no regex.
///
/// For use in the **batch (non-streaming) path only**. The streaming path
/// delegates think-tag extraction to the FFI layer (`streaming.rs`).
pub(crate) fn strip_think_tags(content: &str) -> (String, Option<String>) {
    let open_tag = "<think>";
    let close_tag = "</think>";
    let mut result = content.to_string();
    let mut thinking = String::new();

    loop {
        let open_lower = result.to_lowercase();
        let Some(start) = open_lower.find(open_tag) else {
            break;
        };
        let search_from = start + open_tag.len();
        let Some(relative_end) = open_lower[search_from..].find(close_tag) else {
            break;
        };
        let end = search_from + relative_end + close_tag.len();
        let thought = &result[start + open_tag.len()..search_from + relative_end];
        if !thinking.is_empty() {
            thinking.push('\n');
        }
        thinking.push_str(thought.trim());
        result.replace_range(start..end, "");
    }

    let clean = result.trim().to_string();
    let think = if thinking.is_empty() {
        None
    } else {
        Some(thinking)
    };
    (clean, think)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_tool_calls_uses_parameters_fallback() {
        let mut calls = vec![CompatToolCall {
            id: Some("call_1".into()),
            kind: Some("function".into()),
            function: CompatFunctionCall {
                name: "get_weather".into(),
                arguments: String::new(),
            },
            parameters: Some(serde_json::json!({"location": "Tokyo"})),
        }];
        normalize_tool_calls(&mut calls);
        assert_eq!(
            calls[0].function.arguments,
            r#"{"location":"Tokyo"}"#
        );
    }

    #[test]
    fn normalize_tool_calls_keeps_existing_arguments() {
        let mut calls = vec![CompatToolCall {
            id: Some("call_1".into()),
            kind: Some("function".into()),
            function: CompatFunctionCall {
                name: "get_weather".into(),
                arguments: r#"{"city":"Paris"}"#.into(),
            },
            parameters: Some(serde_json::json!({"location": "Tokyo"})),
        }];
        normalize_tool_calls(&mut calls);
        assert_eq!(calls[0].function.arguments, r#"{"city":"Paris"}"#);
    }

    #[test]
    fn strip_think_tags_basic() {
        let input = "Hello <think>reasoning here</think> world";
        let (clean, thinking) = strip_think_tags(input);
        assert_eq!(clean, "Hello  world");
        assert_eq!(thinking, Some("reasoning here".into()));
    }

    #[test]
    fn strip_think_tags_no_tags() {
        let (clean, thinking) = strip_think_tags("no tags here");
        assert_eq!(clean, "no tags here");
        assert_eq!(thinking, None);
    }

    #[test]
    fn strip_think_tags_tool_call_inside_think_not_leaked() {
        let input = r#"<think>{"tool_calls": [{"name": "evil"}]}</think>actual content"#;
        let (clean, _) = strip_think_tags(input);
        assert_eq!(clean, "actual content");
        assert!(!clean.contains("tool_calls"));
    }

    #[test]
    fn strip_think_tags_multiple_blocks() {
        let input = "<think>first</think>middle<think>second</think>end";
        let (clean, thinking) = strip_think_tags(input);
        assert_eq!(clean, "middleend");
        assert_eq!(thinking, Some("first\nsecond".into()));
    }

    #[test]
    fn extract_tool_calls_preserves_ids() {
        let compat = vec![CompatToolCall {
            id: Some("call_abc".into()),
            kind: Some("function".into()),
            function: CompatFunctionCall {
                name: "search".into(),
                arguments: "{}".into(),
            },
            parameters: None,
        }];
        let calls = extract_tool_calls(&compat);
        assert_eq!(calls[0].id, "call_abc");
        assert_eq!(calls[0].name, "search");
    }
}
