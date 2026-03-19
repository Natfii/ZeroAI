// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

//! End-to-end integration test for the Discord archive system.
//!
//! Validates: archive creation, message storage, FTS5 search,
//! context injection, channel stats, and cleanup.

use tempfile::TempDir;
use zeroclaw::memory::discord_archive::{ArchiveMessage, DiscordArchive};

#[test]
fn test_full_archive_search_context_flow() {
    let dir = TempDir::new().unwrap();
    let archive = DiscordArchive::open(dir.path()).unwrap();

    // 1. Configure a channel
    archive
        .configure_channel("c1", "g1", "general", "7d")
        .unwrap();
    let configs = archive.list_channel_configs().unwrap();
    assert_eq!(configs.len(), 1);
    assert_eq!(configs[0].channel_name, "general");

    // 2. Simulate batch insert (as the buffer would do)
    let now = chrono::Utc::now().timestamp();
    let messages: Vec<ArchiveMessage> = (0..20)
        .map(|i| ArchiveMessage {
            id: format!("{i}"),
            channel_id: "c1".into(),
            guild_id: "g1".into(),
            author_id: if i % 2 == 0 { "u1".into() } else { "u2".into() },
            author_name: if i % 2 == 0 {
                "alice".into()
            } else {
                "bob".into()
            },
            content: format!("message about deployment issue {i}"),
            timestamp: now - (20 - i) * 60,
        })
        .collect();
    archive.store_messages(&messages).unwrap();

    // 3. Verify message count
    assert_eq!(archive.message_count("c1").unwrap(), 20);

    // 4. Verify FTS search works
    let results = archive.search("deployment", None, None, 10).unwrap();
    assert!(!results.is_empty());
    assert!(results.iter().all(|r| r.content.contains("deployment")));

    // 5. Verify search filters by channel
    archive
        .store_messages(&[ArchiveMessage {
            id: "other_1".into(),
            channel_id: "c2".into(),
            guild_id: "g1".into(),
            author_id: "u3".into(),
            author_name: "charlie".into(),
            content: "deployment in another channel".into(),
            timestamp: now,
        }])
        .unwrap();
    let filtered = archive.search("deployment", Some("c1"), None, 50).unwrap();
    assert!(filtered.iter().all(|r| r.channel_id == "c1"));

    // 6. Verify context injection
    let context = zeroclaw::agent::memory_loader::build_discord_context(
        &archive,
        "how is the deployment",
        3600,
        5,
        3,
    )
    .unwrap();
    assert!(context.contains("[Discord context]"));
    assert!(context.contains("deployment"));

    // 7. Verify stats
    let stats = archive.channel_stats("c1").unwrap();
    assert_eq!(stats.message_count, 20);
    assert_eq!(stats.top_authors.len(), 2);
    assert!(stats.top_authors.iter().any(|(name, _)| name == "alice"));
    assert!(stats.top_authors.iter().any(|(name, _)| name == "bob"));
    assert_eq!(stats.top_authors[0].1, 10); // each author has 10 messages

    // 8. Verify sync state
    archive
        .update_sync_state("c1", Some("0"), Some("19"), true)
        .unwrap();
    let sync = archive.get_sync_state("c1").unwrap().unwrap();
    assert!(sync.backfill_done);
    assert_eq!(sync.oldest_id.as_deref(), Some("0"));

    // 9. Verify cleanup
    archive.remove_channel("c1").unwrap();
    assert_eq!(archive.message_count("c1").unwrap(), 0);
    assert!(
        archive.list_channel_configs().unwrap().is_empty()
            || archive
                .list_channel_configs()
                .unwrap()
                .iter()
                .all(|c| c.channel_id != "c1")
    );
    assert!(archive.get_sync_state("c1").unwrap().is_none());
}

#[test]
fn test_search_tool_integration() {
    let dir = TempDir::new().unwrap();
    let archive = DiscordArchive::open(dir.path()).unwrap();
    let now = chrono::Utc::now().timestamp();

    archive
        .store_messages(&[
            ArchiveMessage {
                id: "1".into(),
                channel_id: "c1".into(),
                guild_id: "g1".into(),
                author_id: "u1".into(),
                author_name: "alice".into(),
                content: "the API is returning 500 errors".into(),
                timestamp: now - 300,
            },
            ArchiveMessage {
                id: "2".into(),
                channel_id: "c1".into(),
                guild_id: "g1".into(),
                author_id: "u2".into(),
                author_name: "bob".into(),
                content: "I fixed the database connection pool".into(),
                timestamp: now - 60,
            },
        ])
        .unwrap();

    // Use the search tool's execute function
    let args = zeroclaw::tools::discord_search::DiscordSearchArgs::from_value(
        &serde_json::json!({"query": "API errors", "limit": 5}),
    )
    .unwrap();
    let results = zeroclaw::tools::discord_search::execute(&archive, &args).unwrap();
    assert!(!results.is_empty());
    assert_eq!(results[0].author, "alice");
}

#[test]
fn test_backfill_depth_parsing() {
    use zeroclaw::channels::discord_backfill::depth_to_cutoff;

    assert_eq!(depth_to_cutoff("none"), None);
    assert!(depth_to_cutoff("3d").is_some());
    assert!(depth_to_cutoff("7d").is_some());
    assert!(depth_to_cutoff("30d").is_some());
    assert!(depth_to_cutoff("90d").is_some());
    assert_eq!(depth_to_cutoff("all"), Some(0));
    assert_eq!(depth_to_cutoff("invalid"), None);
}

#[test]
fn test_message_page_parsing() {
    use zeroclaw::channels::discord_backfill::parse_message_page;

    let json = serde_json::json!([
        {
            "id": "123456",
            "channel_id": "c1",
            "guild_id": "g1",
            "author": {"id": "u1", "username": "alice"},
            "content": "hello world",
            "timestamp": "2026-03-09T10:00:00+00:00"
        },
        {
            "id": "123457",
            "channel_id": "c1",
            "guild_id": "g1",
            "author": {"id": "u2", "username": "bob"},
            "content": "hi there",
            "timestamp": "2026-03-09T10:01:00+00:00"
        }
    ]);
    let msgs = parse_message_page(&json).unwrap();
    assert_eq!(msgs.len(), 2);
    assert_eq!(msgs[0].author_name, "alice");
    assert_eq!(msgs[1].author_name, "bob");
}
