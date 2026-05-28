// Copyright (c) 2026 @Natfii. All rights reserved.

//! Memory recall and history compaction helpers for the live agent
//! session.
//!
//! These functions are factored out of [`crate::session`] so the
//! agent-loop file can stay focused on orchestration. They are async by
//! nature because both memory recall and compaction summarisation hit
//! the provider/storage layer.

use std::fmt::Write;

use zeroclaw::memory::Memory;
use zeroclaw::providers::{ChatMessage, ModelProvider};

use crate::session::{
    AgentLoopOutcome, COMPACTION_KEEP_RECENT, COMPACTION_MAX_SOURCE_CHARS,
    COMPACTION_MAX_SUMMARY_CHARS,
};
use crate::session_text::truncate_chars;

/// Queries the memory backend for entries relevant to the user message
/// and formats them as a context preamble string.
///
/// Mirrors upstream `build_context()` but simplified for the FFI
/// session. Entries whose key matches the assistant autosave pattern are
/// skipped to avoid injecting raw LLM output back as context.
///
/// Returns an empty string if no relevant memories are found or the
/// memory query fails.
pub(crate) async fn build_memory_context(mem: &dyn Memory, query: &str) -> String {
    let Ok(entries) = mem.recall(query, 5, None, None, None).await else {
        return String::new();
    };

    let relevant: Vec<_> = entries
        .iter()
        .filter(|e| !zeroclaw::memory::is_assistant_autosave_key(&e.key))
        .filter(|e| match e.score {
            Some(score) => score >= 0.3,
            None => true,
        })
        .collect();

    if relevant.is_empty() {
        return String::new();
    }

    let mut context = String::from("[Memory context]\n");
    for entry in &relevant {
        let _ = writeln!(context, "- {}: {}", entry.key, entry.content);
    }
    context.push('\n');

    context
}

/// Automatically compacts conversation history when it exceeds
/// `max_history` non-system messages.
///
/// Counts the non-system messages, summarises everything older than the
/// most recent [`COMPACTION_KEEP_RECENT`] turns via the provider, and
/// replaces the compacted span with a single `[Compaction summary]`
/// assistant message. Returns `true` if compaction occurred.
///
/// # Errors
///
/// Returns [`AgentLoopOutcome::Error`] on provider failures that cannot
/// be recovered via local fallback.
pub(crate) async fn auto_compact_history(
    history: &mut Vec<ChatMessage>,
    provider: &dyn ModelProvider,
    model: &str,
    max_history: usize,
) -> Result<bool, AgentLoopOutcome> {
    let has_system = history.first().is_some_and(|m| m.role == "system");
    let non_system_count = if has_system {
        history.len().saturating_sub(1)
    } else {
        history.len()
    };

    if non_system_count <= max_history {
        return Ok(false);
    }

    let start = usize::from(has_system);
    let keep_recent = COMPACTION_KEEP_RECENT.min(non_system_count);
    let compact_count = non_system_count.saturating_sub(keep_recent);
    if compact_count == 0 {
        return Ok(false);
    }

    let compact_end = start + compact_count;
    let to_compact: Vec<ChatMessage> = history[start..compact_end].to_vec();
    let transcript = build_compaction_transcript(&to_compact);

    let summariser_system = "You are a conversation compaction engine. Summarize older chat \
        history into concise context for future turns. Preserve: user preferences, commitments, \
        decisions, unresolved tasks, key facts. Omit: filler, repeated chit-chat, verbose tool \
        logs. Output plain text bullet points only.";

    let summariser_user = format!(
        "Summarize the following conversation history for context preservation. \
         Keep it short (max 12 bullet points).\n\n{transcript}"
    );

    let summary_raw = provider
        .chat_with_system(Some(summariser_system), &summariser_user, model, Some(0.2))
        .await
        .unwrap_or_else(|_| truncate_chars(&transcript, COMPACTION_MAX_SUMMARY_CHARS));

    let summary = truncate_chars(&summary_raw, COMPACTION_MAX_SUMMARY_CHARS);

    let summary_msg = ChatMessage::assistant(format!("[Compaction summary]\n{}", summary.trim()));
    history.splice(start..compact_end, std::iter::once(summary_msg));

    Ok(true)
}

/// Trims conversation history to prevent unbounded growth.
///
/// Preserves the system prompt (first message if role=system) and the
/// most recent `max_history` non-system messages, draining the oldest
/// entries.
#[allow(dead_code)] // Reserved for future integration into session_send flow.
pub(crate) fn trim_history(history: &mut Vec<ChatMessage>, max_history: usize) {
    let has_system = history.first().is_some_and(|m| m.role == "system");
    let non_system_count = if has_system {
        history.len().saturating_sub(1)
    } else {
        history.len()
    };

    if non_system_count <= max_history {
        return;
    }

    let start = usize::from(has_system);
    let to_remove = non_system_count - max_history;
    history.drain(start..start + to_remove);
}

/// Builds a transcript of messages for the compaction summariser.
///
/// Each message is formatted as `"ROLE: content"` on its own line. The
/// output is capped at [`COMPACTION_MAX_SOURCE_CHARS`] characters.
pub(crate) fn build_compaction_transcript(messages: &[ChatMessage]) -> String {
    let mut transcript = String::new();
    for msg in messages {
        let role = msg.role.to_uppercase();
        let _ = writeln!(transcript, "{role}: {}", msg.content.trim());
    }

    if transcript.chars().count() > COMPACTION_MAX_SOURCE_CHARS {
        truncate_chars(&transcript, COMPACTION_MAX_SOURCE_CHARS)
    } else {
        transcript
    }
}
