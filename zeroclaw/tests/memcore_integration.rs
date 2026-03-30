// Copyright (c) 2026 @Natfii. All rights reserved.

//! MemCore integration tests — end-to-end round-trips with real SQLite databases.
//!
//! Covers: schema migration, heuristic extraction, scoring functions,
//! store/recall round-trips, and error handling.
//!
//! Run with: cargo test --test memcore_integration -- --test-threads=1

use std::sync::Arc;
use tempfile::TempDir;

use zeroclaw::memory::consolidation::jaccard_similarity;
use zeroclaw::memory::heuristic::extract_facts;
use zeroclaw::memory::scoring::{
    apply_boosts, category_half_life, combined_score, frequency_score, recency_score, should_prune,
};
use zeroclaw::memory::sqlite::SqliteMemory;
use zeroclaw::memory::traits::{Memory, MemoryCategory};

/// Creates a fresh [`SqliteMemory`] instance backed by a temporary directory.
///
/// Returns both the [`TempDir`] guard (to keep the directory alive) and the
/// memory instance.
fn create_test_memory() -> (TempDir, SqliteMemory) {
    let dir = TempDir::new().expect("failed to create temp dir");
    let mem = SqliteMemory::new(dir.path()).expect("SqliteMemory::new failed");
    (dir, mem)
}

// ─────────────────────────────────────────────────────────────────────────────
// Schema migration tests
// ─────────────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn migrate_fresh_db() {
    let (_dir, memory) = create_test_memory();
    assert!(
        memory.health_check().await,
        "fresh SqliteMemory should pass health check"
    );
}

#[tokio::test]
async fn migrate_and_store() {
    let (_dir, memory) = create_test_memory();

    memory
        .store("greeting", "hello world", MemoryCategory::Core, None)
        .await
        .expect("store should succeed");

    let entry = memory
        .get("greeting")
        .await
        .expect("get should succeed")
        .expect("entry should exist");

    assert_eq!(entry.key, "greeting");
    assert_eq!(entry.content, "hello world");
}

#[tokio::test]
async fn migrate_and_store_with_metadata() {
    let (_dir, memory) = create_test_memory();

    memory
        .store_with_metadata(
            "user_name",
            "Natali",
            MemoryCategory::Core,
            None,
            0.9,
            "heuristic",
            "identity,name",
            365,
        )
        .await
        .expect("store_with_metadata should succeed");

    let entry = memory
        .get("user_name")
        .await
        .expect("get should succeed")
        .expect("entry should exist after store_with_metadata");

    assert_eq!(entry.key, "user_name");
    assert_eq!(entry.content, "Natali");
    assert_eq!(entry.category, MemoryCategory::Core);
}

// ─────────────────────────────────────────────────────────────────────────────
// Heuristic extraction tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn heuristic_name_extraction() {
    let facts = extract_facts("My name is Natali");
    assert_eq!(facts.len(), 1, "should extract exactly 1 fact");
    assert_eq!(facts[0].key, "user_name");
    assert_eq!(facts[0].content, "Natali");
    assert_eq!(facts[0].category, "core");
    assert_eq!(facts[0].tags, "identity,name");
    assert!((facts[0].confidence - 0.9).abs() < f64::EPSILON);
}

#[test]
fn heuristic_multiple_facts() {
    let facts = extract_facts("I'm Natali, I live in Seattle");
    assert!(
        facts.len() >= 2,
        "expected at least 2 facts, got {}",
        facts.len()
    );

    let keys: Vec<&str> = facts.iter().map(|f| f.key.as_str()).collect();
    assert!(keys.contains(&"user_name"), "should extract user_name");
    assert!(
        keys.contains(&"user_location"),
        "should extract user_location"
    );
}

#[test]
fn heuristic_no_match() {
    let facts = extract_facts("How do I fix this bug?");
    assert!(
        facts.is_empty(),
        "generic question should not extract any facts"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Scoring tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn scoring_combined_weights() {
    let score = combined_score(1.0, 1.0, 1.0);
    assert!(
        (score - 1.0).abs() < f64::EPSILON,
        "combined_score(1,1,1) should be 1.0, got {score}"
    );

    let zero = combined_score(0.0, 0.0, 0.0);
    assert!(
        zero.abs() < f64::EPSILON,
        "combined_score(0,0,0) should be 0.0, got {zero}"
    );

    let mid = combined_score(0.5, 0.5, 0.5);
    assert!(
        (mid - 0.5).abs() < f64::EPSILON,
        "combined_score(0.5,0.5,0.5) should be 0.5, got {mid}"
    );
}

#[test]
fn scoring_half_lives() {
    assert_eq!(
        category_half_life(&MemoryCategory::Core),
        365,
        "Core half-life should be 365 days"
    );
    assert_eq!(
        category_half_life(&MemoryCategory::Daily),
        7,
        "Daily half-life should be 7 days"
    );
    assert_eq!(
        category_half_life(&MemoryCategory::Conversation),
        1,
        "Conversation half-life should be 1 day"
    );
    assert_eq!(
        category_half_life(&MemoryCategory::Custom("project".into())),
        90,
        "Custom half-life should be 90 days"
    );
}

#[test]
fn scoring_prune_decision() {
    assert!(
        should_prune(0.01, 1),
        "low recency + low access count should trigger prune"
    );
    assert!(
        !should_prune(0.5, 10),
        "high recency + high access count should not prune"
    );
    assert!(
        !should_prune(0.01, 5),
        "low recency but high access count should not prune"
    );
    assert!(
        !should_prune(0.10, 1),
        "recency above threshold should not prune"
    );
}

#[test]
fn scoring_recency_and_frequency_sanity() {
    let fresh = recency_score(Some(&chrono::Utc::now().to_rfc3339()), 365);
    assert!(
        (fresh - 1.0).abs() < 0.01,
        "just-accessed score should be ~1.0, got {fresh}"
    );

    let none = recency_score(None, 365);
    assert!(
        (none - 0.5).abs() < f64::EPSILON,
        "no timestamp should return 0.5, got {none}"
    );

    assert!(
        frequency_score(0).abs() < f64::EPSILON,
        "zero accesses should score 0.0"
    );
    assert!(
        (frequency_score(20) - 1.0).abs() < f64::EPSILON,
        "20 accesses should score 1.0"
    );
    assert!(
        (frequency_score(100) - 1.0).abs() < f64::EPSILON,
        "100 accesses should be capped at 1.0"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// apply_boosts tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn boost_core_recent_frequent() {
    let now = chrono::Local::now().to_rfc3339();
    let score = apply_boosts(0.5, &MemoryCategory::Core, Some(&now), 10);
    // 0.5 * 1.5 * 1.2 * 1.1 = 0.99
    assert!((score - 0.99).abs() < 0.01, "expected ~0.99, got {score}");
}

#[test]
fn boost_core_only() {
    let score = apply_boosts(0.5, &MemoryCategory::Core, None, 2);
    // 0.5 * 1.5 = 0.75 (no recency, no frequency boost)
    assert!((score - 0.75).abs() < 0.01, "expected ~0.75, got {score}");
}

#[test]
fn boost_no_boosts_apply() {
    let old = "2020-01-01T00:00:00+00:00";
    let score = apply_boosts(0.8, &MemoryCategory::Daily, Some(old), 2);
    // No boosts: not Core, not recent, count <= 5
    assert!((score - 0.8).abs() < 0.01, "expected ~0.8, got {score}");
}

#[test]
fn boost_preserves_zero() {
    let now = chrono::Local::now().to_rfc3339();
    assert_eq!(
        apply_boosts(0.0, &MemoryCategory::Core, Some(&now), 10),
        0.0,
        "zero base score should remain 0.0 after boosts"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Store + recall round-trip tests
// ─────────────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn store_and_recall_basic() {
    let (_dir, mem) = create_test_memory();

    mem.store(
        "lang",
        "User prefers Rust programming",
        MemoryCategory::Core,
        None,
    )
    .await
    .unwrap();
    mem.store(
        "food",
        "User likes sushi for lunch",
        MemoryCategory::Core,
        None,
    )
    .await
    .unwrap();
    mem.store(
        "editor",
        "Uses VS Code with rust-analyzer",
        MemoryCategory::Core,
        None,
    )
    .await
    .unwrap();

    let results = mem.recall("Rust programming", 10, None).await.unwrap();
    assert!(
        !results.is_empty(),
        "recall for 'Rust programming' should return results"
    );
    assert!(
        results.iter().any(|e| e.content.contains("Rust")),
        "results should include Rust-related entry"
    );
}

#[tokio::test]
async fn store_upsert() {
    let (_dir, mem) = create_test_memory();

    mem.store("pref", "likes Rust", MemoryCategory::Core, None)
        .await
        .unwrap();
    mem.store("pref", "loves Rust", MemoryCategory::Core, None)
        .await
        .unwrap();

    let count = mem.count().await.unwrap();
    assert_eq!(count, 1, "upsert should not create duplicate entries");

    let entry = mem.get("pref").await.unwrap().expect("entry should exist");
    assert_eq!(
        entry.content, "loves Rust",
        "upsert should update content to latest value"
    );
}

#[tokio::test]
async fn forget_removes() {
    let (_dir, mem) = create_test_memory();

    mem.store("secret", "API key: sk-1234", MemoryCategory::Core, None)
        .await
        .unwrap();
    assert_eq!(mem.count().await.unwrap(), 1);

    let removed = mem.forget("secret").await.unwrap();
    assert!(removed, "forget should return true for existing key");
    assert_eq!(
        mem.count().await.unwrap(),
        0,
        "count should be 0 after forget"
    );
}

#[tokio::test]
async fn category_filter() {
    let (_dir, mem) = create_test_memory();

    mem.store("core1", "core fact alpha", MemoryCategory::Core, None)
        .await
        .unwrap();
    mem.store("core2", "core fact beta", MemoryCategory::Core, None)
        .await
        .unwrap();
    mem.store(
        "daily1",
        "daily note gamma",
        MemoryCategory::Daily,
        None,
    )
    .await
    .unwrap();
    mem.store(
        "conv1",
        "conversation delta",
        MemoryCategory::Conversation,
        None,
    )
    .await
    .unwrap();

    let core = mem.list(Some(&MemoryCategory::Core), None).await.unwrap();
    assert_eq!(core.len(), 2, "should have 2 Core entries");

    let daily = mem.list(Some(&MemoryCategory::Daily), None).await.unwrap();
    assert_eq!(daily.len(), 1, "should have 1 Daily entry");

    let conv = mem
        .list(Some(&MemoryCategory::Conversation), None)
        .await
        .unwrap();
    assert_eq!(conv.len(), 1, "should have 1 Conversation entry");

    let all = mem.list(None, None).await.unwrap();
    assert_eq!(all.len(), 4, "unfiltered list should return all 4 entries");
}

#[tokio::test]
async fn count_correct() {
    let (_dir, mem) = create_test_memory();
    let n = 25;

    for i in 0..n {
        mem.store(
            &format!("key_{i}"),
            &format!("content {i}"),
            MemoryCategory::Core,
            None,
        )
        .await
        .unwrap();
    }

    let count = mem.count().await.unwrap();
    assert_eq!(count, n, "memory_count() should return {n}, got {count}");
}

// ─────────────────────────────────────────────────────────────────────────────
// Error handling tests
// ─────────────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn empty_query_recall() {
    let (_dir, mem) = create_test_memory();

    mem.store("fact", "some content", MemoryCategory::Core, None)
        .await
        .unwrap();

    let results = mem.recall("", 10, None).await.unwrap();
    assert!(
        results.is_empty(),
        "empty query should return empty results, got {} entries",
        results.len()
    );
}

#[tokio::test]
async fn unicode_content() {
    let (_dir, mem) = create_test_memory();

    let emoji_content = "User loves coding \u{1F980}\u{1F338}\u{1F680}";
    let cjk_content = "\u{7528}\u{6237}\u{559C}\u{6B22}\u{7528}Rust\u{7F16}\u{7A0B}";
    let mixed = "Natali \u{2764}\u{FE0F} Rust \u{1F1E7}\u{1F1F7} \u{4F60}\u{597D}";

    mem.store("emoji", emoji_content, MemoryCategory::Core, None)
        .await
        .unwrap();
    mem.store("cjk", cjk_content, MemoryCategory::Core, None)
        .await
        .unwrap();
    mem.store("mixed", mixed, MemoryCategory::Core, None)
        .await
        .unwrap();

    let emoji_entry = mem
        .get("emoji")
        .await
        .unwrap()
        .expect("emoji entry should exist");
    assert_eq!(
        emoji_entry.content, emoji_content,
        "emoji content should round-trip correctly"
    );

    let cjk_entry = mem
        .get("cjk")
        .await
        .unwrap()
        .expect("CJK entry should exist");
    assert_eq!(
        cjk_entry.content, cjk_content,
        "CJK content should round-trip correctly"
    );

    let mixed_entry = mem
        .get("mixed")
        .await
        .unwrap()
        .expect("mixed entry should exist");
    assert_eq!(
        mixed_entry.content, mixed,
        "mixed unicode content should round-trip correctly"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Schema table existence tests (Phase 2a)
// ─────────────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn consolidation_backlog_table_exists() {
    let (_dir, mem) = create_test_memory();

    let conn = mem.conn.lock();
    let count: i64 = conn
        .query_row("SELECT COUNT(*) FROM consolidation_backlog", [], |r| r.get(0))
        .expect("consolidation_backlog table should exist after init_schema");
    assert_eq!(count, 0, "fresh consolidation_backlog should be empty");
}

#[tokio::test]
async fn memory_links_table_exists() {
    let (_dir, mem) = create_test_memory();

    let conn = mem.conn.lock();
    let count: i64 = conn
        .query_row("SELECT COUNT(*) FROM memory_links", [], |r| r.get(0))
        .expect("memory_links table should exist after init_schema");
    assert_eq!(count, 0, "fresh memory_links should be empty");
}

#[tokio::test]
async fn concurrent_access() {
    let (_dir, mem) = create_test_memory();
    let mem = Arc::new(mem);

    let mut handles = Vec::new();
    for i in 0..10 {
        let mem_clone = mem.clone();
        handles.push(tokio::spawn(async move {
            mem_clone
                .store(
                    &format!("concurrent_{i}"),
                    &format!("content from task {i}"),
                    MemoryCategory::Core,
                    None,
                )
                .await
                .expect("concurrent store should not panic");
        }));
    }

    for handle in handles {
        handle.await.expect("task should not panic");
    }

    let count = mem.count().await.unwrap();
    assert_eq!(
        count, 10,
        "all 10 concurrent stores should succeed, got {count}"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// memory_links integration tests
// ─────────────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn store_with_metadata_creates_memory_links() {
    let (_dir, mem) = create_test_memory();

    mem.store_with_metadata(
        "user_name",
        "The user's name is Alice",
        MemoryCategory::Core,
        None,
        0.9,
        "heuristic",
        "identity,personal,user",
        365,
    )
    .await
    .expect("first store should succeed");

    mem.store_with_metadata(
        "user_role",
        "The user is a software engineer",
        MemoryCategory::Core,
        None,
        0.85,
        "heuristic",
        "identity,professional,user",
        365,
    )
    .await
    .expect("second store should succeed");

    let conn = mem.conn.lock();
    let link_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM memory_links", [], |r| r.get(0))
        .expect("memory_links query should succeed");
    assert!(
        link_count > 0,
        "facts sharing tags 'identity' and 'user' should have created links"
    );

    let similarity: f64 = conn
        .query_row(
            "SELECT similarity FROM memory_links LIMIT 1",
            [],
            |r| r.get(0),
        )
        .expect("should have at least one link");
    assert!(
        similarity > 0.3,
        "link similarity should be above threshold, got {similarity}"
    );
}

#[tokio::test]
async fn store_with_metadata_no_links_for_disjoint_tags() {
    let (_dir, mem) = create_test_memory();

    mem.store_with_metadata(
        "fact_a",
        "The sky is blue",
        MemoryCategory::Daily,
        None,
        0.7,
        "heuristic",
        "weather,sky",
        7,
    )
    .await
    .expect("first store should succeed");

    mem.store_with_metadata(
        "fact_b",
        "Rust is a programming language",
        MemoryCategory::Daily,
        None,
        0.7,
        "heuristic",
        "programming,rust",
        7,
    )
    .await
    .expect("second store should succeed");

    let conn = mem.conn.lock();
    let link_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM memory_links", [], |r| r.get(0))
        .expect("memory_links query should succeed");
    assert_eq!(
        link_count, 0,
        "disjoint tags should produce no links"
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// recall_scored boost integration tests
// ─────────────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn recall_scored_core_fact_boosted_above_daily() {
    let (_dir, mem) = create_test_memory();

    mem.store_with_metadata(
        "core_fact",
        "Rust is the user's primary language",
        MemoryCategory::Core,
        None,
        0.9,
        "heuristic",
        "programming,rust",
        365,
    )
    .await
    .unwrap();

    mem.store_with_metadata(
        "daily_fact",
        "Rust compiler updated yesterday",
        MemoryCategory::Daily,
        None,
        0.9,
        "heuristic",
        "programming,update",
        7,
    )
    .await
    .unwrap();

    let results = mem.recall_scored("Rust programming", 10, None).await.unwrap();
    assert!(
        results.len() >= 2,
        "should recall at least 2 facts, got {}",
        results.len()
    );

    let core_entry = results.iter().find(|e| e.entry.key == "core_fact");
    let daily_entry = results.iter().find(|e| e.entry.key == "daily_fact");
    assert!(core_entry.is_some(), "core_fact should be recalled");
    assert!(daily_entry.is_some(), "daily_fact should be recalled");

    let core_score = core_entry.unwrap().combined_score;
    let daily_score = daily_entry.unwrap().combined_score;
    assert!(
        core_score > daily_score,
        "Core fact ({core_score:.4}) should rank above Daily fact ({daily_score:.4}) due to 1.5x boost"
    );
}
