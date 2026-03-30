// Copyright (c) 2026 @Natfii. All rights reserved.

//! Consolidation module integration tests.
//!
//! Covers: should_consolidate triggers, Jaccard similarity, prompt building,
//! response parsing, fact validation, and merge_facts.
//!
//! Run with: cargo test --test consolidation_integration

use chrono::Local;

use zeroclaw::memory::consolidation::{
    build_consolidation_prompts, jaccard_similarity, merge_facts, parse_consolidation_response,
    should_consolidate, validate_extracted_fact, BacklogMessage,
};
use zeroclaw::memory::heuristic::ExtractedFact;

// ─────────────────────────────────────────────────────────────────────────────
// should_consolidate tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn should_consolidate_count_threshold() {
    assert!(
        should_consolidate(20, None, 20),
        "count == threshold should trigger"
    );
    assert!(
        !should_consolidate(19, None, 20),
        "count < threshold should NOT trigger when last_consolidation is None"
    );
}

#[test]
fn should_consolidate_time_threshold() {
    let five_hours_ago = Local::now() - chrono::Duration::hours(5);
    assert!(
        should_consolidate(5, Some(five_hours_ago), 20),
        "5 hours since last consolidation should trigger"
    );

    let one_hour_ago = Local::now() - chrono::Duration::hours(1);
    assert!(
        !should_consolidate(5, Some(one_hour_ago), 20),
        "1 hour since last consolidation + low count should NOT trigger"
    );
}

#[test]
fn should_consolidate_recent_low_count() {
    let one_hour_ago = Local::now() - chrono::Duration::hours(1);
    assert!(
        !should_consolidate(5, Some(one_hour_ago), 20),
        "recent consolidation + low count should NOT trigger"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Jaccard similarity tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn jaccard_identical() {
    let score = jaccard_similarity("hello world", "hello world");
    assert!(
        (score - 1.0).abs() < f64::EPSILON,
        "identical strings should have Jaccard = 1.0, got {score}"
    );
}

#[test]
fn jaccard_disjoint() {
    let score = jaccard_similarity("hello world", "foo bar");
    assert!(
        score.abs() < f64::EPSILON,
        "disjoint strings should have Jaccard = 0.0, got {score}"
    );
}

#[test]
fn jaccard_partial() {
    let score = jaccard_similarity("hello world", "hello there");
    // intersection = {"hello"}, union = {"hello", "world", "there"} → 1/3
    let expected = 1.0 / 3.0;
    assert!(
        (score - expected).abs() < 1e-9,
        "expected ~{expected}, got {score}"
    );
}

#[test]
fn jaccard_empty_returns_zero() {
    assert!(
        jaccard_similarity("", "hello").abs() < f64::EPSILON,
        "empty first string should return 0.0"
    );
    assert!(
        jaccard_similarity("hello", "").abs() < f64::EPSILON,
        "empty second string should return 0.0"
    );
    assert!(
        jaccard_similarity("", "").abs() < f64::EPSILON,
        "both empty should return 0.0"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// parse_consolidation_response tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn parse_consolidation_valid_json() {
    let json = r#"{
        "facts": [
            {"key": "user_name", "content": "Natali", "tags": "identity,name"}
        ],
        "summaries": [
            {"session_id": "abc123", "summary": "Discussed SSH terminal setup."}
        ]
    }"#;

    let result = parse_consolidation_response(json).expect("valid JSON should parse");
    assert_eq!(result.facts.len(), 1);
    assert_eq!(result.facts[0].key, "user_name");
    assert_eq!(result.facts[0].content, "Natali");
    assert_eq!(result.facts[0].tags, "identity,name");
    assert_eq!(result.facts[0].category, "core");
    assert!((result.facts[0].confidence - 0.8).abs() < f64::EPSILON);
    assert_eq!(result.summaries.len(), 1);
    assert_eq!(result.summaries[0].session_id, "abc123");
}

#[test]
fn parse_consolidation_extracts_from_markdown() {
    let response = "Here are the extracted facts:\n\n```json\n{\n  \"facts\": [\n    {\"key\": \"user_lang\", \"content\": \"Rust\", \"tags\": \"tool\"}\n  ],\n  \"summaries\": []\n}\n```\nDone!";

    let result = parse_consolidation_response(response).expect("markdown-wrapped should parse");
    assert_eq!(result.facts.len(), 1);
    assert_eq!(result.facts[0].key, "user_lang");
    assert_eq!(result.facts[0].content, "Rust");
}

#[test]
fn parse_consolidation_rejects_garbage() {
    let result = parse_consolidation_response("not json at all");
    assert!(result.is_err(), "garbage input should return Err");
    let err = result.unwrap_err();
    assert!(
        err.contains("not json"),
        "error should contain first chars of input, got: {err}"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// validate_extracted_fact tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn validate_fact_rejects_long_key() {
    let fact = ExtractedFact {
        key: "a".repeat(100),
        content: "valid content".into(),
        category: "core".into(),
        tags: "tag".into(),
        confidence: 0.8,
    };
    assert!(
        !validate_extracted_fact(&fact),
        "100-char key should be rejected"
    );
}

#[test]
fn validate_fact_accepts_valid() {
    let fact = ExtractedFact {
        key: "user_name".into(),
        content: "Natali".into(),
        category: "core".into(),
        tags: "identity,name".into(),
        confidence: 0.8,
    };
    assert!(validate_extracted_fact(&fact), "valid fact should pass");
}

// ─────────────────────────────────────────────────────────────────────────────
// build_consolidation_prompts tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn build_prompts_chunks_correctly() {
    let messages: Vec<BacklogMessage> = (0..50)
        .map(|i| BacklogMessage {
            session_id: format!("sess_{i}"),
            message_text: format!("message {i}"),
            created_at: "2026-03-30T12:00:00+00:00".into(),
        })
        .collect();

    let prompts = build_consolidation_prompts(&messages, 30);
    assert_eq!(
        prompts.len(),
        2,
        "50 messages at batch_size=30 should produce 2 prompts, got {}",
        prompts.len()
    );

    // Each prompt should contain the extraction rules and <conversations> tags
    for prompt in &prompts {
        assert!(
            prompt.contains("Extract facts about the user"),
            "prompt should contain extraction rules"
        );
        assert!(
            prompt.contains("<conversations>"),
            "prompt should contain <conversations> tag"
        );
        assert!(
            prompt.contains("</conversations>"),
            "prompt should contain closing </conversations> tag"
        );
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// merge_facts tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn merge_facts_keeps_newer_content_and_older_timestamp() {
    let newer = ExtractedFact {
        key: "user_name".into(),
        content: "Nat".into(),
        category: "core".into(),
        tags: "identity".into(),
        confidence: 0.8,
    };

    let (content, confidence, created_at) =
        merge_facts("Natali", &newer, 5, "2026-01-01T00:00:00+00:00", 0.9);

    assert_eq!(content, "Nat", "should use newer content");
    assert!(
        (confidence - 0.9).abs() < f64::EPSILON,
        "should keep max confidence (older was higher)"
    );
    assert_eq!(
        created_at, "2026-01-01T00:00:00+00:00",
        "should preserve older created_at"
    );
}
