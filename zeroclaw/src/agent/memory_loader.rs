// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

use crate::memory::discord_archive::DiscordArchive;
use crate::memory::{self, Memory};
use async_trait::async_trait;
use chrono::Utc;
use std::collections::HashSet;
use std::fmt::Write;
use std::sync::Arc;

#[async_trait]
pub trait MemoryLoader: Send + Sync {
    async fn load_context(&self, memory: &dyn Memory, user_message: &str)
        -> anyhow::Result<String>;
}

/// Maximum character budget for the formatted Discord context block.
/// Roughly ~500 tokens at ~4 chars/token.
const DISCORD_CONTEXT_CHAR_LIMIT: usize = 2000;

/// Format a Unix timestamp as a human-readable relative age string.
///
/// Returns a compact duration like `"30s ago"`, `"5m ago"`, `"2h ago"`, or `"3d ago"`.
fn humanize_age(timestamp: i64) -> String {
    let now = Utc::now().timestamp();
    let delta = (now - timestamp).max(0);

    if delta < 60 {
        format!("{delta}s ago")
    } else if delta < 3600 {
        format!("{}m ago", delta / 60)
    } else if delta < 86_400 {
        format!("{}h ago", delta / 3600)
    } else {
        format!("{}d ago", delta / 86_400)
    }
}

/// Build a Discord context block from recent and keyword-relevant archive messages.
///
/// Queries `archive.recent_messages` for messages within the recency window,
/// and (when the user message has >= 2 words) `archive.search` for keyword-relevant
/// messages. Results are deduplicated by message ID, sorted by timestamp descending,
/// and formatted as a `[Discord context]` block capped at ~2000 characters.
///
/// Returns an empty string if no messages are found.
pub fn build_discord_context(
    archive: &DiscordArchive,
    user_message: &str,
    recency_secs: i64,
    recent_limit: usize,
    relevant_limit: usize,
) -> anyhow::Result<String> {
    let recent = archive.recent_messages(recency_secs, recent_limit)?;

    let mut relevant = Vec::new();
    let word_count = user_message.split_whitespace().count();
    if word_count >= 2 {
        relevant = archive.search(user_message, None, Some(30), relevant_limit)?;
    }

    let mut seen = HashSet::new();
    let mut merged = Vec::new();
    for msg in recent.into_iter().chain(relevant) {
        if seen.insert(msg.id.clone()) {
            merged.push(msg);
        }
    }

    if merged.is_empty() {
        return Ok(String::new());
    }

    merged.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));

    let mut output = String::from("[Discord context]\n");
    for msg in &merged {
        let line = format!(
            "#{} ({}) {}: {}\n",
            msg.channel_id,
            humanize_age(msg.timestamp),
            msg.author_name,
            msg.content,
        );

        if output.len() + line.len() > DISCORD_CONTEXT_CHAR_LIMIT {
            break;
        }
        output.push_str(&line);
    }

    if output == "[Discord context]\n" {
        return Ok(String::new());
    }

    output.push('\n');
    Ok(output)
}

pub struct DefaultMemoryLoader {
    limit: usize,
    min_relevance_score: f64,
    archive: Option<Arc<DiscordArchive>>,
}

impl Default for DefaultMemoryLoader {
    fn default() -> Self {
        Self {
            limit: 5,
            min_relevance_score: 0.4,
            archive: None,
        }
    }
}

impl DefaultMemoryLoader {
    pub fn new(limit: usize, min_relevance_score: f64) -> Self {
        Self {
            limit: limit.max(1),
            min_relevance_score,
            archive: None,
        }
    }

    /// Set the Discord archive for automatic context injection.
    pub fn with_archive(mut self, archive: Arc<DiscordArchive>) -> Self {
        self.archive = Some(archive);
        self
    }
}

#[async_trait]
impl MemoryLoader for DefaultMemoryLoader {
    async fn load_context(
        &self,
        memory: &dyn Memory,
        user_message: &str,
    ) -> anyhow::Result<String> {
        let mut full_context = String::new();

        if let Some(ref archive) = self.archive {
            let discord_ctx = build_discord_context(archive, user_message, 600, 5, 3)?;
            if !discord_ctx.is_empty() {
                full_context.push_str(&discord_ctx);
            }
        }

        let entries = memory.recall(user_message, self.limit, None).await?;
        if !entries.is_empty() {
            let mut memory_ctx = String::from("[Memory context]\n");
            for entry in entries {
                if memory::is_assistant_autosave_key(&entry.key) {
                    continue;
                }
                if let Some(score) = entry.score {
                    if score < self.min_relevance_score {
                        continue;
                    }
                }
                let _ = writeln!(memory_ctx, "- {}: {}", entry.key, entry.content);
            }

            if memory_ctx != "[Memory context]\n" {
                memory_ctx.push('\n');
                full_context.push_str(&memory_ctx);
            }
        }

        Ok(full_context)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::memory::{Memory, MemoryCategory, MemoryEntry};
    use std::sync::Arc;

    struct MockMemory;
    struct MockMemoryWithEntries {
        entries: Arc<Vec<MemoryEntry>>,
    }

    #[async_trait]
    impl Memory for MockMemory {
        async fn store(
            &self,
            _key: &str,
            _content: &str,
            _category: MemoryCategory,
            _session_id: Option<&str>,
        ) -> anyhow::Result<()> {
            Ok(())
        }

        async fn recall(
            &self,
            _query: &str,
            limit: usize,
            _session_id: Option<&str>,
        ) -> anyhow::Result<Vec<MemoryEntry>> {
            if limit == 0 {
                return Ok(vec![]);
            }
            Ok(vec![MemoryEntry {
                id: "1".into(),
                key: "k".into(),
                content: "v".into(),
                category: MemoryCategory::Conversation,
                timestamp: "now".into(),
                session_id: None,
                score: None,
            }])
        }

        async fn get(&self, _key: &str) -> anyhow::Result<Option<MemoryEntry>> {
            Ok(None)
        }

        async fn list(
            &self,
            _category: Option<&MemoryCategory>,
            _session_id: Option<&str>,
        ) -> anyhow::Result<Vec<MemoryEntry>> {
            Ok(vec![])
        }

        async fn forget(&self, _key: &str) -> anyhow::Result<bool> {
            Ok(true)
        }

        async fn count(&self) -> anyhow::Result<usize> {
            Ok(0)
        }

        async fn health_check(&self) -> bool {
            true
        }

        fn name(&self) -> &str {
            "mock"
        }
    }

    #[async_trait]
    impl Memory for MockMemoryWithEntries {
        async fn store(
            &self,
            _key: &str,
            _content: &str,
            _category: MemoryCategory,
            _session_id: Option<&str>,
        ) -> anyhow::Result<()> {
            Ok(())
        }

        async fn recall(
            &self,
            _query: &str,
            _limit: usize,
            _session_id: Option<&str>,
        ) -> anyhow::Result<Vec<MemoryEntry>> {
            Ok(self.entries.as_ref().clone())
        }

        async fn get(&self, _key: &str) -> anyhow::Result<Option<MemoryEntry>> {
            Ok(None)
        }

        async fn list(
            &self,
            _category: Option<&MemoryCategory>,
            _session_id: Option<&str>,
        ) -> anyhow::Result<Vec<MemoryEntry>> {
            Ok(vec![])
        }

        async fn forget(&self, _key: &str) -> anyhow::Result<bool> {
            Ok(true)
        }

        async fn count(&self) -> anyhow::Result<usize> {
            Ok(self.entries.len())
        }

        async fn health_check(&self) -> bool {
            true
        }

        fn name(&self) -> &str {
            "mock-with-entries"
        }
    }

    #[tokio::test]
    async fn default_loader_formats_context() {
        let loader = DefaultMemoryLoader::default();
        let context = loader.load_context(&MockMemory, "hello").await.unwrap();
        assert!(context.contains("[Memory context]"));
        assert!(context.contains("- k: v"));
    }

    #[tokio::test]
    async fn default_loader_skips_legacy_assistant_autosave_entries() {
        let loader = DefaultMemoryLoader::new(5, 0.0);
        let memory = MockMemoryWithEntries {
            entries: Arc::new(vec![
                MemoryEntry {
                    id: "1".into(),
                    key: "assistant_resp_legacy".into(),
                    content: "fabricated detail".into(),
                    category: MemoryCategory::Daily,
                    timestamp: "now".into(),
                    session_id: None,
                    score: Some(0.95),
                },
                MemoryEntry {
                    id: "2".into(),
                    key: "user_fact".into(),
                    content: "User prefers concise answers".into(),
                    category: MemoryCategory::Conversation,
                    timestamp: "now".into(),
                    session_id: None,
                    score: Some(0.9),
                },
            ]),
        };

        let context = loader.load_context(&memory, "answer style").await.unwrap();
        assert!(context.contains("user_fact"));
        assert!(!context.contains("assistant_resp_legacy"));
        assert!(!context.contains("fabricated detail"));
    }

    #[test]
    fn discord_context_formats_correctly() {
        use crate::memory::discord_archive::{ArchiveMessage, DiscordArchive};
        let dir = tempfile::TempDir::new().unwrap();
        let archive = DiscordArchive::open(dir.path()).unwrap();
        let now = chrono::Utc::now().timestamp();
        archive
            .configure_channel("c1", "g1", "general", "7d")
            .unwrap();
        archive
            .store_messages(&[ArchiveMessage {
                id: "1".into(),
                channel_id: "c1".into(),
                guild_id: "g1".into(),
                author_id: "u1".into(),
                author_name: "alice".into(),
                content: "the deploy looks good".into(),
                timestamp: now - 60,
            }])
            .unwrap();

        let context = build_discord_context(&archive, "how is the deploy", 600, 5, 3).unwrap();
        assert!(context.contains("[Discord context]"));
        assert!(context.contains("alice"));
        assert!(context.contains("deploy looks good"));
    }

    #[test]
    fn discord_context_empty_when_no_recent() {
        use crate::memory::discord_archive::DiscordArchive;
        let dir = tempfile::TempDir::new().unwrap();
        let archive = DiscordArchive::open(dir.path()).unwrap();
        let context = build_discord_context(&archive, "hello", 600, 5, 3).unwrap();
        assert!(context.is_empty());
    }

    #[test]
    fn humanize_age_formats_relative_durations() {
        let now = chrono::Utc::now().timestamp();
        assert!(humanize_age(now - 30).contains("s ago"));
        assert!(humanize_age(now - 300).contains("m ago"));
        assert!(humanize_age(now - 7200).contains("h ago"));
        assert!(humanize_age(now - 172_800).contains("d ago"));
    }

    #[test]
    fn discord_context_deduplicates_messages() {
        use crate::memory::discord_archive::{ArchiveMessage, DiscordArchive};
        let dir = tempfile::TempDir::new().unwrap();
        let archive = DiscordArchive::open(dir.path()).unwrap();
        let now = chrono::Utc::now().timestamp();
        archive
            .configure_channel("c1", "g1", "general", "7d")
            .unwrap();
        archive
            .store_messages(&[
                ArchiveMessage {
                    id: "1".into(),
                    channel_id: "c1".into(),
                    guild_id: "g1".into(),
                    author_id: "u1".into(),
                    author_name: "alice".into(),
                    content: "deploy started".into(),
                    timestamp: now - 60,
                },
                ArchiveMessage {
                    id: "2".into(),
                    channel_id: "c1".into(),
                    guild_id: "g1".into(),
                    author_id: "u2".into(),
                    author_name: "bob".into(),
                    content: "deploy finished".into(),
                    timestamp: now - 30,
                },
            ])
            .unwrap();

        let context = build_discord_context(&archive, "deploy status", 600, 10, 10).unwrap();
        let alice_count = context.matches("alice").count();
        let bob_count = context.matches("bob").count();
        assert_eq!(alice_count, 1);
        assert_eq!(bob_count, 1);
    }

    #[test]
    fn discord_context_respects_char_limit() {
        use crate::memory::discord_archive::{ArchiveMessage, DiscordArchive};
        let dir = tempfile::TempDir::new().unwrap();
        let archive = DiscordArchive::open(dir.path()).unwrap();
        let now = chrono::Utc::now().timestamp();
        archive
            .configure_channel("c1", "g1", "general", "7d")
            .unwrap();

        let messages: Vec<ArchiveMessage> = (0..100)
            .map(|i| ArchiveMessage {
                id: format!("m{i}"),
                channel_id: "c1".into(),
                guild_id: "g1".into(),
                author_id: "u1".into(),
                author_name: "zeroclaw_user".into(),
                content: format!("message number {i} with some padding text here"),
                timestamp: now - i,
            })
            .collect();
        archive.store_messages(&messages).unwrap();

        let context = build_discord_context(&archive, "hello world", 600, 100, 50).unwrap();
        assert!(context.len() <= DISCORD_CONTEXT_CHAR_LIMIT + 1);
    }

    #[tokio::test]
    async fn loader_with_archive_prepends_discord_context() {
        use crate::memory::discord_archive::{ArchiveMessage, DiscordArchive};
        let dir = tempfile::TempDir::new().unwrap();
        let archive = DiscordArchive::open(dir.path()).unwrap();
        let now = chrono::Utc::now().timestamp();
        archive
            .configure_channel("c1", "g1", "general", "7d")
            .unwrap();
        archive
            .store_messages(&[ArchiveMessage {
                id: "1".into(),
                channel_id: "c1".into(),
                guild_id: "g1".into(),
                author_id: "u1".into(),
                author_name: "alice".into(),
                content: "system online".into(),
                timestamp: now - 30,
            }])
            .unwrap();

        let loader = DefaultMemoryLoader::default().with_archive(Arc::new(archive));
        let context = loader.load_context(&MockMemory, "hello").await.unwrap();

        let discord_pos = context.find("[Discord context]");
        let memory_pos = context.find("[Memory context]");
        assert!(discord_pos.is_some(), "should contain Discord context");
        assert!(memory_pos.is_some(), "should contain Memory context");
        assert!(
            discord_pos.unwrap() < memory_pos.unwrap(),
            "Discord context should precede Memory context"
        );
    }
}
