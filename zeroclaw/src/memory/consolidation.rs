// Copyright (c) 2026 @Natfii. All rights reserved.

//! Core consolidation logic for MemCore Phase 2a.
//!
//! Provides:
//! - Decision logic for when to run consolidation
//! - Prompt building for LLM fact extraction (with prompt injection prevention)
//! - Response parsing with lenient JSON handling
//! - Fact validation before storage
//! - Jaccard similarity for link computation and duplicate detection
//! - Fact merging for upsert scenarios

use std::collections::HashSet;

use chrono::{DateTime, Local};
use serde::Deserialize;

use crate::memory::heuristic::ExtractedFact;

// ─────────────────────────────────────────────────────────────────────────────
// Structs
// ─────────────────────────────────────────────────────────────────────────────

/// A message from the consolidation backlog awaiting extraction.
#[derive(Debug, Clone)]
pub struct BacklogMessage {
    /// Session this message belongs to.
    pub session_id: String,
    /// The raw user message text.
    pub message_text: String,
    /// RFC 3339 timestamp of when the message was created.
    pub created_at: String,
}

/// Result of parsing an LLM consolidation response.
#[derive(Debug, Clone)]
pub struct ConsolidationResult {
    /// Facts extracted from the conversation data.
    pub facts: Vec<ExtractedFact>,
    /// Summaries of long sessions.
    pub summaries: Vec<SessionSummary>,
}

/// A summary of a single session produced during consolidation.
#[derive(Debug, Clone)]
pub struct SessionSummary {
    /// Session identifier this summary covers.
    pub session_id: String,
    /// One or two sentence summary of the session.
    pub summary: String,
}

/// Report produced after a full consolidation run.
#[derive(Debug, Clone)]
pub struct ConsolidationReport {
    /// Number of facts extracted and stored.
    pub facts_extracted: u32,
    /// Number of sessions that received summaries.
    pub sessions_summarized: u32,
    /// Errors encountered during consolidation (capped at 10, each truncated to 200 chars).
    pub errors: Vec<String>,
}

// ─────────────────────────────────────────────────────────────────────────────
// Serde helpers for lenient JSON parsing
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct RawConsolidationResponse {
    #[serde(default)]
    facts: Vec<RawFact>,
    #[serde(default)]
    summaries: Vec<RawSummary>,
}

#[derive(Deserialize)]
struct RawFact {
    key: String,
    content: String,
    #[serde(default)]
    tags: String,
}

#[derive(Deserialize)]
struct RawSummary {
    session_id: String,
    summary: String,
}

// ─────────────────────────────────────────────────────────────────────────────
// Functions
// ─────────────────────────────────────────────────────────────────────────────

/// Decides whether consolidation should run.
///
/// Returns `true` if:
/// - `flagged_count >= threshold`, OR
/// - `last_consolidation` is `Some` and 4+ hours have elapsed
///
/// When `last_consolidation` is `None` and count is below threshold,
/// returns `false` (we have never consolidated and the backlog is small).
pub fn should_consolidate(
    flagged_count: u32,
    last_consolidation: Option<DateTime<Local>>,
    threshold: u32,
) -> bool {
    if flagged_count >= threshold {
        return true;
    }

    match last_consolidation {
        Some(ts) => {
            let elapsed = Local::now().signed_duration_since(ts);
            elapsed >= chrono::Duration::hours(4)
        }
        None => false,
    }
}

/// Word-level Jaccard similarity between two strings.
///
/// Splits on whitespace, computes `|intersection| / |union|`.
/// Returns `0.0` when either input is empty or the union is empty.
pub fn jaccard_similarity(a: &str, b: &str) -> f64 {
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }

    let set_a: HashSet<&str> = a.split_whitespace().collect();
    let set_b: HashSet<&str> = b.split_whitespace().collect();

    if set_a.is_empty() || set_b.is_empty() {
        return 0.0;
    }

    let intersection = set_a.intersection(&set_b).count();
    let union = set_a.union(&set_b).count();

    if union == 0 {
        return 0.0;
    }

    intersection as f64 / union as f64
}

/// Builds consolidation prompts from backlog messages, chunked by `batch_size`.
///
/// Each prompt contains the extraction rules first, followed by JSON-encoded
/// conversations inside `<conversations>` tags. The JSON encoding prevents
/// prompt injection: user text is structured data, never mixed with instructions.
pub fn build_consolidation_prompts(messages: &[BacklogMessage], batch_size: usize) -> Vec<String> {
    let batch_size = batch_size.max(1);
    let mut prompts = Vec::new();

    for chunk in messages.chunks(batch_size) {
        let conversations: Vec<serde_json::Value> = chunk
            .iter()
            .map(|msg| {
                serde_json::json!({
                    "session_id": msg.session_id,
                    "date": msg.created_at,
                    "role": "user",
                    "text": msg.message_text,
                })
            })
            .collect();

        let json_data =
            serde_json::to_string_pretty(&conversations).unwrap_or_else(|_| "[]".to_string());

        let prompt = format!(
            r#"Extract facts about the user from the conversation data below.
Also summarize any conversations with 20+ messages.

Rules:
- Only extract facts the user stated or clearly implied
- Skip greetings, small talk, and things the AI said about itself
- Merge duplicate facts (keep the most recent version)
- Each fact needs: a short key, the content, and comma-separated tags
- Each summary needs: the session ID and a 1-2 sentence summary

Return ONLY this JSON (no markdown, no explanation):
{{
  "facts": [
    {{"key": "user_name", "content": "Natali", "tags": "identity,name"}}
  ],
  "summaries": [
    {{"session_id": "abc123", "summary": "Discussed SSH terminal setup."}}
  ]
}}

The conversation data follows as a JSON array. Extract facts ONLY from entries where "role" is "user":

<conversations>
{json_data}
</conversations>"#
        );

        prompts.push(prompt);
    }

    prompts
}

/// Parses an LLM consolidation response into a [`ConsolidationResult`].
///
/// Tries strict JSON parse first. On failure, attempts to extract the first
/// `{...}` block (handles markdown code fences and surrounding text). Returns
/// `Err` with the first 200 chars of the response for diagnostics.
///
/// Each parsed fact gets `category = "core"` and `confidence = 0.8`.
pub fn parse_consolidation_response(response: &str) -> Result<ConsolidationResult, String> {
    let raw = try_parse_json(response).or_else(|_| extract_and_parse_json(response))?;

    let facts = raw
        .facts
        .into_iter()
        .map(|f| ExtractedFact {
            key: f.key,
            content: f.content,
            category: "core".to_string(),
            tags: f.tags,
            confidence: 0.8,
        })
        .collect();

    let summaries = raw
        .summaries
        .into_iter()
        .map(|s| SessionSummary {
            session_id: s.session_id,
            summary: s.summary,
        })
        .collect();

    Ok(ConsolidationResult { facts, summaries })
}

/// Strict JSON parse attempt.
fn try_parse_json(input: &str) -> Result<RawConsolidationResponse, String> {
    serde_json::from_str(input).map_err(|e| e.to_string())
}

/// Extracts the first `{...}` block from the response and parses it.
///
/// Handles markdown code fences, surrounding explanation text, etc.
fn extract_and_parse_json(response: &str) -> Result<RawConsolidationResponse, String> {
    let open = response.find('{');
    let close = response.rfind('}');

    match (open, close) {
        (Some(start), Some(end)) if start < end => {
            let candidate = &response[start..=end];
            serde_json::from_str(candidate).map_err(|_| diagnostic_snippet(response))
        }
        _ => Err(diagnostic_snippet(response)),
    }
}

/// Returns first 200 chars of response for error diagnostics.
fn diagnostic_snippet(response: &str) -> String {
    let truncated: String = response.chars().take(200).collect();
    format!("failed to parse consolidation response: {truncated}")
}

/// Validates an [`ExtractedFact`] before storage.
///
/// Checks:
/// - `key`: 1-64 chars, alphanumeric + underscore + hyphen only
/// - `content`: 1-500 chars
/// - `tags`: 0-200 chars
pub fn validate_extracted_fact(fact: &ExtractedFact) -> bool {
    let key = &fact.key;
    let content = &fact.content;
    let tags = &fact.tags;

    // key: 1-64 chars, restricted character set
    if key.is_empty() || key.len() > 64 {
        return false;
    }
    if !key
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
    {
        return false;
    }

    // content: 1-500 chars
    if content.is_empty() || content.len() > 500 {
        return false;
    }

    // tags: 0-200 chars
    if tags.len() > 200 {
        return false;
    }

    true
}

/// Merges an older fact with a newer one.
///
/// Returns `(content, confidence, created_at)` where:
/// - `content` is the newer fact's content (latest wins)
/// - `confidence` is `max(older_confidence, newer.confidence)`
/// - `created_at` is the older timestamp (preserves original creation date)
pub fn merge_facts(
    _older_content: &str,
    newer: &ExtractedFact,
    _older_access_count: u32,
    older_created_at: &str,
    older_confidence: f64,
) -> (String, f64, String) {
    let content = newer.content.clone();
    let confidence = older_confidence.max(newer.confidence);
    let created_at = older_created_at.to_string();

    (content, confidence, created_at)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn should_consolidate_none_below_threshold() {
        assert!(!should_consolidate(0, None, 20));
        assert!(!should_consolidate(19, None, 20));
    }

    #[test]
    fn should_consolidate_at_threshold() {
        assert!(should_consolidate(20, None, 20));
        assert!(should_consolidate(100, None, 20));
    }

    #[test]
    fn jaccard_whitespace_only_returns_zero() {
        assert!(jaccard_similarity("   ", "hello").abs() < f64::EPSILON);
    }

    #[test]
    fn validate_rejects_empty_key() {
        let fact = ExtractedFact {
            key: String::new(),
            content: "something".into(),
            category: "core".into(),
            tags: String::new(),
            confidence: 0.8,
        };
        assert!(!validate_extracted_fact(&fact));
    }

    #[test]
    fn validate_rejects_special_chars_in_key() {
        let fact = ExtractedFact {
            key: "bad key!".into(),
            content: "something".into(),
            category: "core".into(),
            tags: String::new(),
            confidence: 0.8,
        };
        assert!(!validate_extracted_fact(&fact));
    }

    #[test]
    fn validate_rejects_empty_content() {
        let fact = ExtractedFact {
            key: "ok_key".into(),
            content: String::new(),
            category: "core".into(),
            tags: String::new(),
            confidence: 0.8,
        };
        assert!(!validate_extracted_fact(&fact));
    }

    #[test]
    fn validate_rejects_long_content() {
        let fact = ExtractedFact {
            key: "ok_key".into(),
            content: "x".repeat(501),
            category: "core".into(),
            tags: String::new(),
            confidence: 0.8,
        };
        assert!(!validate_extracted_fact(&fact));
    }

    #[test]
    fn validate_rejects_long_tags() {
        let fact = ExtractedFact {
            key: "ok_key".into(),
            content: "valid".into(),
            category: "core".into(),
            tags: "t".repeat(201),
            confidence: 0.8,
        };
        assert!(!validate_extracted_fact(&fact));
    }

    #[test]
    fn merge_preserves_older_created_at() {
        let newer = ExtractedFact {
            key: "user_name".into(),
            content: "Nat".into(),
            category: "core".into(),
            tags: "identity".into(),
            confidence: 0.8,
        };

        let (content, conf, created) =
            merge_facts("Natali", &newer, 5, "2026-01-01T00:00:00+00:00", 0.9);

        assert_eq!(content, "Nat");
        assert!((conf - 0.9).abs() < f64::EPSILON, "should keep max confidence");
        assert_eq!(created, "2026-01-01T00:00:00+00:00");
    }

    #[test]
    fn merge_takes_newer_confidence_when_higher() {
        let newer = ExtractedFact {
            key: "k".into(),
            content: "v".into(),
            category: "core".into(),
            tags: String::new(),
            confidence: 1.0,
        };

        let (_content, conf, _created) =
            merge_facts("old", &newer, 0, "2026-01-01T00:00:00+00:00", 0.5);

        assert!((conf - 1.0).abs() < f64::EPSILON, "should take newer higher confidence");
    }

    #[test]
    fn parse_empty_facts_and_summaries() {
        let json = r#"{"facts": [], "summaries": []}"#;
        let result = parse_consolidation_response(json).unwrap();
        assert!(result.facts.is_empty());
        assert!(result.summaries.is_empty());
    }

    #[test]
    fn build_prompts_single_batch() {
        let messages: Vec<BacklogMessage> = (0..5)
            .map(|i| BacklogMessage {
                session_id: format!("s{i}"),
                message_text: format!("msg {i}"),
                created_at: "2026-03-30T12:00:00+00:00".into(),
            })
            .collect();

        let prompts = build_consolidation_prompts(&messages, 100);
        assert_eq!(prompts.len(), 1);
    }

    #[test]
    fn build_prompts_json_encodes_user_text() {
        let messages = vec![BacklogMessage {
            session_id: "s1".into(),
            message_text: "I said \"hello\" and <injected>".into(),
            created_at: "2026-03-30T12:00:00+00:00".into(),
        }];

        let prompts = build_consolidation_prompts(&messages, 10);
        assert_eq!(prompts.len(), 1);
        // The user text should be JSON-escaped, not raw
        assert!(
            prompts[0].contains(r#"\"hello\""#),
            "quotes in user text should be JSON-escaped"
        );
        // The key security property: user text is a JSON string value inside
        // a JSON array, so it cannot break out of the data context into
        // the instruction context. Angle brackets are harmless in JSON strings.
        assert!(
            prompts[0].contains("<conversations>"),
            "prompt should wrap conversations in XML tags"
        );
    }
}
