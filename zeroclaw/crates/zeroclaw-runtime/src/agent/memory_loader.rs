use async_trait::async_trait;
use std::fmt::Write;
use zeroclaw_memory::{self, MEMORY_CONTEXT_CLOSE, MEMORY_CONTEXT_OPEN, Memory, decay};

#[async_trait]
pub trait MemoryLoader: Send + Sync {
    async fn load_context(
        &self,
        memory: &dyn Memory,
        user_message: &str,
        session_id: Option<&str>,
    ) -> anyhow::Result<String>;
}

pub struct DefaultMemoryLoader {
    limit: usize,
    min_relevance_score: f64,
}

impl Default for DefaultMemoryLoader {
    fn default() -> Self {
        Self {
            limit: 5,
            min_relevance_score: 0.4,
        }
    }
}

impl DefaultMemoryLoader {
    pub fn new(limit: usize, min_relevance_score: f64) -> Self {
        Self {
            limit: limit.max(1),
            min_relevance_score,
        }
    }
}

/// Returns true when the user message is short enough that semantic
/// memory recall will only return low-relevance noise. Specifically:
///
///   - Strings with fewer than three whitespace-separated tokens.
///   - A small allowlist of common greeting / acknowledgement words
///     that semantic search inevitably misroutes (`hi`, `hello`,
///     `thanks`, `ok`, `yes`, `no`, …) even when the message has 3+
///     tokens because of punctuation or filler.
///
/// Kept conservative — anything that LOOKS like a real question
/// (`who is bob`, `what time is it`) passes through, since those
/// genuinely benefit from recall.
fn is_trivial_input(message: &str) -> bool {
    let trimmed = message.trim();
    if trimmed.is_empty() {
        return true;
    }
    let word_count = trimmed.split_whitespace().count();
    if word_count < 3 {
        return true;
    }
    let lowered = trimmed.to_lowercase();
    const GREETINGS: &[&str] = &[
        "hi", "hello", "hey", "yo", "sup", "thanks", "thank you",
        "ok", "okay", "yes", "no", "sure", "cool", "nice", "lol",
        "gn", "good night", "good morning", "morning",
    ];
    GREETINGS.iter().any(|g| lowered == *g)
}

/// Returns true when the user message carries an image attachment
/// (either the Nano-captioned `<image>...</image>` block emitted by
/// `NanoFallback.captionedPrompt` or the bracketed `[Image: ...]` /
/// `[IMAGE:path]` markers from the channel media-marker convention).
///
/// We use this to skip memory recall entirely on image-bearing turns.
/// The bug we're fixing: when the user sends a fresh image, semantic
/// recall reliably surfaces *past* image descriptions from daily
/// summaries and channel archives. Those memories have higher
/// textual salience than the current Nano caption (~38 chars vs the
/// recalled entry's hundreds of chars), so a text-only model
/// pattern-matches to the OLD image description and "describes" the
/// wrong picture. Observed: user sent a dog photo, model described
/// a toy from a past Discord interaction.
///
/// Real fix is a vision-capable model on a path that sees the
/// actual pixels; until then, skipping recall when an image is
/// present keeps the model's attention on the current visual signal
/// instead of drowning it under stale text.
fn current_turn_has_image(message: &str) -> bool {
    message.contains("<image>")
        || message.contains("[image:")
        || message.contains("[IMAGE:")
        || message.contains("[Image:")
}

#[async_trait]
impl MemoryLoader for DefaultMemoryLoader {
    async fn load_context(
        &self,
        memory: &dyn Memory,
        user_message: &str,
        session_id: Option<&str>,
    ) -> anyhow::Result<String> {
        // Skip recall entirely for trivial conversational turns —
        // greetings, single-word acknowledgements, bare emojis. The
        // semantic-search component will happily return memories
        // scored just above threshold for "hi" / "thanks" / "ok",
        // but those memories have no real relevance to the turn and
        // just bloat the prompt by 100-400 tokens of noise. Honest
        // questions are at least three words; anything shorter
        // doesn't have enough signal to retrieve against.
        if is_trivial_input(user_message) {
            tracing::debug!(
                target: "zeroclaw::memory_loader",
                "Skipping recall: trivial input (len={}, first_chars={})",
                user_message.len(),
                user_message.chars().take(40).collect::<String>(),
            );
            return Ok(String::new());
        }
        // Skip recall on image-bearing turns. See
        // [current_turn_has_image] — past image descriptions in
        // memory consistently win salience over the brief current
        // Nano caption and lead the model to describe the wrong
        // picture.
        if current_turn_has_image(user_message) {
            tracing::info!(
                target: "zeroclaw::memory_loader",
                "Skipping recall: current turn carries <image> block (msg_len={})",
                user_message.len(),
            );
            return Ok(String::new());
        }
        tracing::debug!(
            target: "zeroclaw::memory_loader",
            "Running recall (msg_len={}, limit={})",
            user_message.len(),
            self.limit,
        );
        let mut entries = memory
            .recall(user_message, self.limit, session_id, None, None)
            .await?;
        if entries.is_empty() {
            return Ok(String::new());
        }

        // Apply time decay: older non-Core memories score lower
        decay::apply_time_decay(&mut entries, decay::DEFAULT_HALF_LIFE_DAYS);

        let mut context = String::new();
        let mut included = false;
        for entry in entries {
            if zeroclaw_memory::is_assistant_autosave_key(&entry.key) {
                continue;
            }
            if zeroclaw_memory::is_user_autosave_key(&entry.key) {
                continue;
            }
            if zeroclaw_memory::should_skip_autosave_content(&entry.content) {
                continue;
            }
            if let Some(score) = entry.score
                && score < self.min_relevance_score
            {
                continue;
            }
            if !included {
                context.push_str(MEMORY_CONTEXT_OPEN);
                context.push('\n');
                included = true;
            }
            let _ = writeln!(context, "- {}: {}", entry.key, entry.content);
        }

        // If all entries were below threshold, return empty
        if !included {
            return Ok(String::new());
        }

        context.push_str(MEMORY_CONTEXT_CLOSE);
        context.push_str("\n\n");
        Ok(context)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use zeroclaw_memory::{
        MEMORY_CONTEXT_CLOSE, MEMORY_CONTEXT_OPEN, Memory, MemoryCategory, MemoryEntry,
    };

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
            _since: Option<&str>,
            _until: Option<&str>,
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
                namespace: "default".into(),
                importance: None,
                superseded_by: None,
                agent_alias: None,
                agent_id: None,
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

        async fn forget_for_agent(&self, _key: &str, _agent_id: &str) -> anyhow::Result<bool> {
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

        async fn store_with_agent(
            &self,
            _key: &str,
            _content: &str,
            _category: MemoryCategory,
            _session_id: Option<&str>,
            _namespace: Option<&str>,
            _importance: Option<f64>,
            _agent_id: Option<&str>,
        ) -> anyhow::Result<()> {
            Ok(())
        }

        async fn recall_for_agents(
            &self,
            _allowed_agent_ids: &[&str],
            query: &str,
            limit: usize,
            session_id: Option<&str>,
            since: Option<&str>,
            until: Option<&str>,
        ) -> anyhow::Result<Vec<MemoryEntry>> {
            self.recall(query, limit, session_id, since, until).await
        }
    }
    impl ::zeroclaw_api::attribution::Attributable for MockMemory {
        fn role(&self) -> ::zeroclaw_api::attribution::Role {
            ::zeroclaw_api::attribution::Role::Memory(
                ::zeroclaw_api::attribution::MemoryKind::InMemory,
            )
        }
        fn alias(&self) -> &str {
            "MockMemory"
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
            _since: Option<&str>,
            _until: Option<&str>,
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

        async fn forget_for_agent(&self, _key: &str, _agent_id: &str) -> anyhow::Result<bool> {
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

        async fn store_with_agent(
            &self,
            _key: &str,
            _content: &str,
            _category: MemoryCategory,
            _session_id: Option<&str>,
            _namespace: Option<&str>,
            _importance: Option<f64>,
            _agent_id: Option<&str>,
        ) -> anyhow::Result<()> {
            Ok(())
        }

        async fn recall_for_agents(
            &self,
            _allowed_agent_ids: &[&str],
            query: &str,
            limit: usize,
            session_id: Option<&str>,
            since: Option<&str>,
            until: Option<&str>,
        ) -> anyhow::Result<Vec<MemoryEntry>> {
            self.recall(query, limit, session_id, since, until).await
        }
    }
    impl ::zeroclaw_api::attribution::Attributable for MockMemoryWithEntries {
        fn role(&self) -> ::zeroclaw_api::attribution::Role {
            ::zeroclaw_api::attribution::Role::Memory(
                ::zeroclaw_api::attribution::MemoryKind::InMemory,
            )
        }
        fn alias(&self) -> &str {
            "MockMemoryWithEntries"
        }
    }

    #[tokio::test]
    async fn default_loader_formats_context() {
        let loader = DefaultMemoryLoader::default();
        let context = loader
            .load_context(&MockMemory, "hello", None)
            .await
            .unwrap();
        assert_eq!(
            context,
            format!("{MEMORY_CONTEXT_OPEN}\n- k: v\n{MEMORY_CONTEXT_CLOSE}\n\n")
        );
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
                    namespace: "default".into(),
                    importance: None,
                    superseded_by: None,
                    agent_alias: None,
                    agent_id: None,
                },
                MemoryEntry {
                    id: "2".into(),
                    key: "user_fact".into(),
                    content: "User prefers concise answers".into(),
                    category: MemoryCategory::Conversation,
                    timestamp: "now".into(),
                    session_id: None,
                    score: Some(0.9),
                    namespace: "default".into(),
                    importance: None,
                    superseded_by: None,
                    agent_alias: None,
                    agent_id: None,
                },
            ]),
        };

        let context = loader
            .load_context(&memory, "answer style", None)
            .await
            .unwrap();
        assert!(context.contains("user_fact"));
        assert!(!context.contains("assistant_resp_legacy"));
        assert!(!context.contains("fabricated detail"));
    }

    #[tokio::test]
    async fn default_loader_skips_user_autosave_entries() {
        let loader = DefaultMemoryLoader::new(5, 0.0);
        let memory = MockMemoryWithEntries {
            entries: Arc::new(vec![
                MemoryEntry {
                    id: "1".into(),
                    key: "user_msg_e5f6g7h8".into(),
                    content: "User message embedding prior context verbatim".into(),
                    category: MemoryCategory::Conversation,
                    timestamp: "now".into(),
                    session_id: None,
                    score: Some(0.95),
                    namespace: "default".into(),
                    importance: None,
                    superseded_by: None,
                    agent_alias: None,
                    agent_id: None,
                },
                MemoryEntry {
                    id: "2".into(),
                    key: "user_fact".into(),
                    content: "User prefers concise answers".into(),
                    category: MemoryCategory::Conversation,
                    timestamp: "now".into(),
                    session_id: None,
                    score: Some(0.9),
                    namespace: "default".into(),
                    importance: None,
                    superseded_by: None,
                    agent_alias: None,
                    agent_id: None,
                },
            ]),
        };

        let context = loader
            .load_context(&memory, "answer style", None)
            .await
            .unwrap();
        assert!(context.contains("user_fact"));
        assert!(!context.contains("user_msg_e5f6g7h8"));
        assert!(!context.contains("embedding prior context"));
    }
}
