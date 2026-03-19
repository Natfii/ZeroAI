// Copyright (c) 2026 @Natfii. All rights reserved.

//! Integration tests for the messages bridge SQLite store.

use std::sync::Arc;
use tempfile::TempDir;
use zeroclaw::messages_bridge::store::MessagesBridgeStore;
use zeroclaw::messages_bridge::types::{BridgedConversation, BridgedMessage, MessageType};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn make_conv(id: &str, name: &str, allowed: bool, ts: i64) -> BridgedConversation {
    BridgedConversation {
        id: id.to_string(),
        display_name: name.to_string(),
        is_group: false,
        last_message_preview: "preview".to_string(),
        last_message_timestamp: ts,
        agent_allowed: allowed,
        window_start: None,
    }
}

fn make_msg(id: &str, conv_id: &str, sender: &str, body: &str, ts: i64) -> BridgedMessage {
    BridgedMessage {
        id: id.to_string(),
        conversation_id: conv_id.to_string(),
        sender_name: sender.to_string(),
        body: body.to_string(),
        timestamp: ts,
        is_outgoing: false,
        message_type: MessageType::Text,
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/// Full lifecycle: upsert conversations, store messages, allow one, query it.
#[test]
fn test_full_lifecycle() {
    let tmp = TempDir::new().unwrap();
    let store = MessagesBridgeStore::open(tmp.path()).unwrap();

    // Upsert 3 conversations (none allowed initially).
    store.upsert_conversation(&make_conv("c1", "Alice", false, 1000)).unwrap();
    store.upsert_conversation(&make_conv("c2", "Bob", false, 2000)).unwrap();
    store.upsert_conversation(&make_conv("c3", "Carol", false, 3000)).unwrap();

    let convs = store.list_conversations().unwrap();
    assert_eq!(convs.len(), 3, "expected 3 conversations after upsert");

    // Store 10 messages distributed across the 3 conversations.
    let messages: Vec<BridgedMessage> = (0..10)
        .map(|i| {
            let conv_id = match i % 3 {
                0 => "c1",
                1 => "c2",
                _ => "c3",
            };
            make_msg(&format!("m{i}"), conv_id, "sender", &format!("body {i}"), i * 100)
        })
        .collect();
    store.store_messages(&messages).unwrap();

    // Allow only c1.
    store.set_allowed("c1", true, None).unwrap();

    // Query c1 — should return all messages belonging to it.
    let results = store.query_messages("c1", None, 100).unwrap();
    let expected_count = messages.iter().filter(|m| m.conversation_id == "c1").count();
    assert_eq!(
        results.len(),
        expected_count,
        "query_messages returned unexpected count"
    );

    // Results must be ordered by timestamp descending.
    let timestamps: Vec<i64> = results.iter().map(|m| m.timestamp).collect();
    let mut sorted = timestamps.clone();
    sorted.sort_by(|a, b| b.cmp(a));
    assert_eq!(timestamps, sorted, "messages not in descending timestamp order");

    // Querying a not-allowed conversation must fail.
    assert!(
        store.query_messages("c2", None, 100).is_err(),
        "query_messages should error for non-allowed conversation"
    );
}

/// FTS5 search returns only messages that match the keyword.
#[test]
fn test_fts5_search_across_conversations() {
    let tmp = TempDir::new().unwrap();
    let store = MessagesBridgeStore::open(tmp.path()).unwrap();

    store.upsert_conversation(&make_conv("c1", "Alice", true, 5000)).unwrap();
    store.upsert_conversation(&make_conv("c2", "Bob", true, 6000)).unwrap();

    let messages = vec![
        make_msg("m1", "c1", "Alice", "xylophone concert tonight", 1000),
        make_msg("m2", "c1", "Alice", "meeting at noon", 2000),
        make_msg("m3", "c2", "Bob", "xylophone rehearsal tomorrow", 3000),
        make_msg("m4", "c2", "Bob", "lunch plans for today", 4000),
    ];
    store.store_messages(&messages).unwrap();

    // Search for the unique keyword that appears in both conversations.
    let results = store.search("xylophone", None, 50).unwrap();
    assert_eq!(results.len(), 2, "expected exactly 2 messages matching 'xylophone'");
    assert!(
        results.iter().all(|m| m.body.contains("xylophone")),
        "all results should contain the search keyword"
    );

    // Search for a keyword that appears in only one message.
    let results = store.search("rehearsal", None, 50).unwrap();
    assert_eq!(results.len(), 1, "expected exactly 1 message matching 'rehearsal'");
    assert_eq!(results[0].id, "m3");

    // Search for a term that matches nothing.
    let results = store.search("zxqwerty", None, 50).unwrap();
    assert!(results.is_empty(), "search for unknown term should return empty");
}

/// After wipe, list_conversations is empty and messages are gone.
#[test]
fn test_wipe_clears_everything() {
    let tmp = TempDir::new().unwrap();
    let store = MessagesBridgeStore::open(tmp.path()).unwrap();

    store.upsert_conversation(&make_conv("c1", "Alice", true, 1000)).unwrap();
    store.upsert_conversation(&make_conv("c2", "Bob", true, 2000)).unwrap();

    let messages = vec![
        make_msg("m1", "c1", "Alice", "hello world unique_term", 1000),
        make_msg("m2", "c2", "Bob", "another message unique_term", 2000),
    ];
    store.store_messages(&messages).unwrap();

    // Verify data is present before wipe.
    assert_eq!(store.list_conversations().unwrap().len(), 2);
    assert_eq!(store.query_messages("c1", None, 100).unwrap().len(), 1);

    // Wipe everything.
    store.wipe().unwrap();

    // Conversations must be gone.
    assert!(
        store.list_conversations().unwrap().is_empty(),
        "list_conversations should be empty after wipe"
    );

    // FTS index must also be cleared.
    let fts_results = store.search("unique_term", None, 50).unwrap();
    assert!(fts_results.is_empty(), "FTS index should be empty after wipe");
}

/// Two threads can write to different conversations concurrently without panics.
#[test]
fn test_concurrent_access() {
    let tmp = TempDir::new().unwrap();
    let store = Arc::new(MessagesBridgeStore::open(tmp.path()).unwrap());

    // Seed both conversations before spawning threads.
    store.upsert_conversation(&make_conv("ca", "Thread-A", false, 0)).unwrap();
    store.upsert_conversation(&make_conv("cb", "Thread-B", false, 0)).unwrap();

    let store_a = Arc::clone(&store);
    let store_b = Arc::clone(&store);

    let handle_a = std::thread::spawn(move || {
        let messages: Vec<BridgedMessage> = (0..20)
            .map(|i| make_msg(&format!("a{i}"), "ca", "writer-a", &format!("msg {i}"), i))
            .collect();
        store_a.store_messages(&messages).unwrap();
    });

    let handle_b = std::thread::spawn(move || {
        let messages: Vec<BridgedMessage> = (0..20)
            .map(|i| make_msg(&format!("b{i}"), "cb", "writer-b", &format!("msg {i}"), i))
            .collect();
        store_b.store_messages(&messages).unwrap();
    });

    handle_a.join().expect("thread A panicked");
    handle_b.join().expect("thread B panicked");

    // Allow both conversations so we can query them.
    store.set_allowed("ca", true, None).unwrap();
    store.set_allowed("cb", true, None).unwrap();

    let results_a = store.query_messages("ca", None, 100).unwrap();
    let results_b = store.query_messages("cb", None, 100).unwrap();

    assert_eq!(results_a.len(), 20, "conversation ca should have 20 messages");
    assert_eq!(results_b.len(), 20, "conversation cb should have 20 messages");
}
