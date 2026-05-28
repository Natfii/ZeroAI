// Copyright (c) 2026 @Natfii. All rights reserved.

//! Pure text helpers used by the live agent session.
//!
//! Functions in this module are intentionally side-effect free: they
//! consume strings and configuration values and return new strings or
//! parsed structs. Lifting them out of `session.rs` keeps the agent-loop
//! file focused on orchestration.

use std::fmt::Write;

use zeroclaw::providers::ToolCall;

/// Tag names treated as thinking/reasoning blocks.
///
/// Content inside these tags is extracted from the response `text` field
/// and forwarded to the thinking card instead of being streamed as
/// visible response text.
///
/// Different models use different tag conventions:
/// - DeepSeek-R1, Qwen: `<think>...</think>`
/// - Some fine-tuned models: `<thinking>...</thinking>`
/// - Claude artifacts: `<analysis>`, `<reflection>`, `<inner_monologue>`
const THINKING_TAG_NAMES: &[&str] = &[
    "think",
    "thinking",
    "analysis",
    "reflection",
    "inner_monologue",
    "reasoning",
];

/// Composes a user message with embedded `[IMAGE:...]` markers.
///
/// When `image_data` is empty the original `text` is returned unchanged.
/// Otherwise each base64-encoded image is appended as an
/// `[IMAGE:data:<mime>;base64,<payload>]` marker. The upstream provider's
/// `to_message_content` parser recognises these markers and converts
/// them to multimodal content parts.
pub(crate) fn compose_multimodal_message(
    text: &str,
    image_data: &[String],
    mime_types: &[String],
) -> String {
    if image_data.is_empty() {
        return text.to_string();
    }

    let mut buf =
        String::with_capacity(text.len() + image_data.iter().map(String::len).sum::<usize>() + 256);
    buf.push_str(text);

    for (data, mime) in image_data.iter().zip(mime_types.iter()) {
        buf.push_str("\n\n[IMAGE:data:");
        buf.push_str(mime);
        buf.push_str(";base64,");
        buf.push_str(data);
        buf.push(']');
    }

    buf
}

/// Appends Android-specific identity fields to the system prompt.
///
/// The upstream AIEOS renderer only outputs agent identity (name, bio,
/// personality). Android onboarding also stores `user_name`, `timezone`,
/// and `communication_style` inside the `identity` JSON object. These
/// fields are silently dropped by serde because they don't exist in the
/// upstream `IdentitySection` struct.
///
/// This function parses the raw `aieos_inline` JSON, extracts those
/// extra fields, and appends a "## User Context" section to the prompt.
pub(crate) fn append_android_identity_extras(
    prompt: &mut String,
    identity_config: &zeroclaw::config::IdentityConfig,
) {
    let Some(ref inline) = identity_config.aieos_inline else {
        return;
    };

    let Ok(root) = serde_json::from_str::<serde_json::Value>(inline) else {
        return;
    };

    let identity_obj = match root.get("identity") {
        Some(v) => v,
        None => &root,
    };

    let user_name = identity_obj
        .get("user_name")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let timezone = identity_obj
        .get("timezone")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let comm_style = identity_obj
        .get("communication_style")
        .and_then(|v| v.as_str())
        .unwrap_or("");

    if user_name.is_empty() && timezone.is_empty() && comm_style.is_empty() {
        return;
    }

    prompt.push_str("\n## User Context\n\n");
    if !user_name.is_empty() {
        let _ = writeln!(prompt, "**User's name:** {user_name}");
    }
    if !timezone.is_empty() {
        let _ = writeln!(prompt, "**Timezone:** {timezone}");
    }
    if !comm_style.is_empty() {
        let _ = writeln!(prompt, "**Preferred communication style:** {comm_style}");
    }
}

/// Truncates a string to `max_chars` characters, appending `"..."` if
/// truncated.
pub(crate) fn truncate_chars(s: &str, max_chars: usize) -> String {
    match s.char_indices().nth(max_chars) {
        Some((idx, _)) => {
            let truncated = &s[..idx];
            format!("{}...", truncated.trim_end())
        }
        None => s.to_string(),
    }
}

/// Extracts a short hint from tool call arguments for the progress display.
///
/// For `shell` tools, shows the `command` field. For file tools, shows
/// the `path` field. For other tools, shows the `action` or `query`
/// field.
pub(crate) fn truncate_tool_args_hint(tool_name: &str, arguments_json: &str) -> String {
    let args: serde_json::Value =
        serde_json::from_str(arguments_json).unwrap_or(serde_json::json!({}));

    let hint = match tool_name {
        "shell" => args.get("command").and_then(|v| v.as_str()),
        "file_read" | "file_write" => args.get("path").and_then(|v| v.as_str()),
        _ => args
            .get("action")
            .and_then(|v| v.as_str())
            .or_else(|| args.get("query").and_then(|v| v.as_str())),
    };

    match hint {
        Some(s) => truncate_chars(s, 60),
        None => String::new(),
    }
}

/// Extracts thinking/reasoning blocks from model response text.
///
/// Scans `text` for matched pairs of tags listed in
/// [`THINKING_TAG_NAMES`], collects their inner content, and returns a
/// tuple of `(clean_text, thinking_content)`. The clean text has the tag
/// blocks removed (with surrounding whitespace collapsed), ready for
/// streaming to the user. The thinking content is the concatenation of
/// all extracted blocks, suitable for `FfiSessionListener::on_thinking`.
///
/// Matching is case-insensitive. Nested or overlapping tags of the same
/// kind are handled greedily (the outermost pair wins).
pub(crate) fn extract_thinking_from_text(text: &str) -> (String, String) {
    let mut clean = text.to_string();
    let mut thinking = String::new();

    for tag in THINKING_TAG_NAMES {
        loop {
            let lower = clean.to_lowercase();
            let open_tag = format!("<{tag}>");
            let close_tag = format!("</{tag}>");

            let Some(open_start) = lower.find(&open_tag) else {
                break;
            };
            let content_start = open_start + open_tag.len();
            let Some(close_start) = lower[content_start..].find(&close_tag) else {
                break;
            };
            let close_end = content_start + close_start + close_tag.len();

            let inner = &clean[content_start..content_start + close_start];
            let trimmed = inner.trim();
            if !trimmed.is_empty() {
                if !thinking.is_empty() {
                    thinking.push('\n');
                }
                thinking.push_str(trimmed);
            }

            clean.replace_range(open_start..close_end, "");
        }
    }

    let clean = clean.trim().to_string();
    (clean, thinking)
}

/// Parses `<tool_call>` XML tags from prompt-guided model responses.
///
/// When the provider does not support native tool calling, upstream
/// injects a `## Tool Use Protocol` section into the system prompt that
/// instructs the model to emit tool calls as `<tool_call>{...}</tool_call>`.
/// Upstream's `Provider::chat()` default implementation returns
/// `tool_calls: Vec::new()` in that mode — it never parses the XML tags
/// from the response text. This function fills that gap.
///
/// Returns a tuple of `(clean_text, parsed_tool_calls)` where
/// `clean_text` has the `<tool_call>` blocks removed.
pub(crate) fn parse_xml_tool_calls(text: &str) -> (String, Vec<ToolCall>) {
    let mut calls = Vec::new();
    let mut clean = text.to_string();
    let mut counter = 0u32;

    loop {
        let lower = clean.to_lowercase();
        let Some(open_idx) = lower.find("<tool_call>") else {
            break;
        };
        let Some(close_idx) = lower[open_idx..].find("</tool_call>") else {
            break;
        };
        let close_abs = open_idx + close_idx;
        let inner_start = open_idx + "<tool_call>".len();
        let inner = clean[inner_start..close_abs].trim();

        if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(inner) {
            let name = parsed
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let arguments = match parsed.get("arguments") {
                Some(v) => v.to_string(),
                None => "{}".to_string(),
            };

            if !name.is_empty() {
                counter += 1;
                calls.push(ToolCall {
                    id: format!("xmltc_{counter}"),
                    name,
                    arguments,
                    extra_content: None,
                });
            }
        }

        let end = close_abs + "</tool_call>".len();
        clean.replace_range(open_idx..end, "");
    }

    if !calls.is_empty() {
        clean = clean.trim().to_string();
    }

    (clean, calls)
}
