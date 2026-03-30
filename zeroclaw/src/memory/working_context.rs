// Copyright (c) 2026 @Natfii. All rights reserved.

//! Working context assembly for system prompt injection.
//!
//! Replaces legacy MEMORY.md/USER.md file injection with
//! SQLite-backed context. Inspired by Hermes Agent's system
//! prompt assembly and CoALA's working memory concept.

use crate::memory::traits::{Memory, MemoryCategory};

/// Working context assembled for each LLM turn.
#[derive(Debug, Clone)]
pub struct WorkingContext {
    /// Core identity and user profile facts.
    pub identity_block: String,
    /// Top-ranked semantic memories relevant to current query.
    pub recall_block: String,
    /// Compressed summary of recent conversation history.
    pub episodic_summary: String,
    /// Total estimated token count of all blocks.
    pub estimated_tokens: usize,
}

/// Token budget per provider tier.
#[derive(Debug, Clone)]
pub struct TokenBudget {
    /// Max tokens for the identity/core-facts block.
    pub identity: usize,
    /// Max tokens for the semantic recall block.
    pub recall: usize,
    /// Max tokens for the episodic summary block.
    pub episodic: usize,
}

impl TokenBudget {
    /// Cloud providers (128K+ context): 500/500/400.
    pub fn cloud() -> Self {
        Self {
            identity: 500,
            recall: 500,
            episodic: 400,
        }
    }

    /// Gemini Nano (4K context): 400/200/200.
    pub fn nano() -> Self {
        Self {
            identity: 400,
            recall: 200,
            episodic: 200,
        }
    }

    /// Local Ollama (2-8K context): 400/200/200.
    pub fn local() -> Self {
        Self {
            identity: 400,
            recall: 200,
            episodic: 200,
        }
    }

    /// Total budget across all blocks.
    pub fn total(&self) -> usize {
        self.identity + self.recall + self.episodic
    }
}

/// Estimates token count from text using the chars/4 heuristic.
fn estimate_tokens(text: &str) -> usize {
    text.len() / 4
}

/// Truncates text to fit within a token budget (chars/4 heuristic).
///
/// Returns the original text if it fits, otherwise truncates at
/// a char boundary near `budget * 4` characters.
fn truncate_to_budget(text: &str, budget: usize) -> &str {
    let max_chars = budget * 4;
    if text.len() <= max_chars {
        return text;
    }
    let mut end = max_chars;
    while end > 0 && !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

/// Formats core-fact entries into an identity block.
///
/// Each entry appears as `- {key}: {content}` on its own line.
fn format_identity_block(entries: &[(String, String)]) -> String {
    let mut buf = String::new();
    for (key, content) in entries {
        if !buf.is_empty() {
            buf.push('\n');
        }
        buf.push_str(&format!("- {key}: {content}"));
    }
    buf
}

/// Formats recall results into a recall block.
///
/// Each entry appears as `- {content} (score: {score:.2})` on its own line.
fn format_recall_block(entries: &[(String, f64)]) -> String {
    let mut buf = String::new();
    for (content, score) in entries {
        if !buf.is_empty() {
            buf.push('\n');
        }
        buf.push_str(&format!("- {content} (score: {score:.2})"));
    }
    buf
}

/// Assembles working context for a session turn.
///
/// 1. Loads ALL core facts (category=core, up to identity budget).
/// 2. Semantic recall against query (top results, up to recall budget).
/// 3. Episodic placeholder (simple "recent session" text, up to episodic budget).
pub async fn assemble_working_context(
    message: &str,
    session_id: &str,
    budget: &TokenBudget,
    memory: &dyn Memory,
) -> WorkingContext {
    let core_facts = memory
        .list(Some(&MemoryCategory::Core), None)
        .await
        .unwrap_or_default();

    let identity_pairs: Vec<(String, String)> = core_facts
        .iter()
        .map(|e| (e.key.clone(), e.content.clone()))
        .collect();

    let identity_raw = format_identity_block(&identity_pairs);
    let identity_block = truncate_to_budget(&identity_raw, budget.identity).to_string();

    let recall_entries = memory.recall(message, 10, None).await.unwrap_or_default();

    let recall_pairs: Vec<(String, f64)> = recall_entries
        .iter()
        .map(|e| (e.content.clone(), e.score.unwrap_or(0.0)))
        .collect();

    let recall_raw = format_recall_block(&recall_pairs);
    let recall_block = truncate_to_budget(&recall_raw, budget.recall).to_string();

    let episodic_summary = format!("[Session: {session_id}]");

    let estimated_tokens =
        estimate_tokens(&identity_block) + estimate_tokens(&recall_block) + estimate_tokens(&episodic_summary);

    WorkingContext {
        identity_block,
        recall_block,
        episodic_summary,
        estimated_tokens,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::memory::none::NoneMemory;
    use crate::memory::sqlite::SqliteMemory;
    use crate::memory::traits::Memory;
    use tempfile::TempDir;

    fn temp_sqlite() -> (TempDir, SqliteMemory) {
        let tmp = TempDir::new().unwrap();
        let mem = SqliteMemory::new(tmp.path()).unwrap();
        (tmp, mem)
    }

    #[tokio::test]
    async fn empty_store_returns_empty_blocks() {
        let mem = NoneMemory::new();
        let ctx = assemble_working_context("hello", "sess-1", &TokenBudget::cloud(), &mem).await;
        assert!(ctx.identity_block.is_empty());
        assert!(ctx.recall_block.is_empty());
        assert_eq!(ctx.estimated_tokens, estimate_tokens(&ctx.episodic_summary));
    }

    #[tokio::test]
    async fn identity_block_formats_core_facts() {
        let (_tmp, mem) = temp_sqlite();
        mem.store("name", "Alice", MemoryCategory::Core, None)
            .await
            .unwrap();
        mem.store("language", "Rust", MemoryCategory::Core, None)
            .await
            .unwrap();
        mem.store("os", "Linux", MemoryCategory::Core, None)
            .await
            .unwrap();

        let ctx = assemble_working_context("hello", "sess-1", &TokenBudget::cloud(), &mem).await;
        assert!(
            ctx.identity_block.contains("name: Alice"),
            "identity block should contain name fact"
        );
        assert!(
            ctx.identity_block.contains("language: Rust"),
            "identity block should contain language fact"
        );
        assert!(
            ctx.identity_block.contains("os: Linux"),
            "identity block should contain os fact"
        );
    }

    #[tokio::test]
    async fn recall_block_formats_search_results() {
        let (_tmp, mem) = temp_sqlite();
        mem.store("k1", "Rust is fast", MemoryCategory::Core, None)
            .await
            .unwrap();
        mem.store("k2", "Rust has zero-cost abstractions", MemoryCategory::Core, None)
            .await
            .unwrap();

        let ctx = assemble_working_context("Rust", "sess-1", &TokenBudget::cloud(), &mem).await;
        assert!(
            ctx.recall_block.contains("Rust"),
            "recall block should contain matched content"
        );
        assert!(
            ctx.recall_block.contains("score:"),
            "recall block should contain score annotation"
        );
    }

    #[test]
    fn budget_cloud() {
        let b = TokenBudget::cloud();
        assert_eq!(b.identity, 500);
        assert_eq!(b.recall, 500);
        assert_eq!(b.episodic, 400);
        assert_eq!(b.total(), 1400);
    }

    #[test]
    fn budget_nano() {
        let b = TokenBudget::nano();
        assert_eq!(b.identity, 400);
        assert_eq!(b.recall, 200);
        assert_eq!(b.episodic, 200);
        assert_eq!(b.total(), 800);
    }

    #[test]
    fn token_estimation() {
        let text = "a".repeat(400);
        let tokens = estimate_tokens(&text);
        assert_eq!(tokens, 100, "400 chars / 4 = 100 tokens");
    }

    #[tokio::test]
    async fn cold_start_minimal() {
        let (_tmp, mem) = temp_sqlite();
        mem.store("user_name", "Bob", MemoryCategory::Core, None)
            .await
            .unwrap();

        let ctx = assemble_working_context("hello", "sess-first", &TokenBudget::cloud(), &mem).await;
        assert!(
            ctx.identity_block.contains("user_name: Bob"),
            "first call should return identity block with stored core fact"
        );
    }

    #[tokio::test]
    async fn episodic_placeholder() {
        let mem = NoneMemory::new();
        let ctx =
            assemble_working_context("hello", "my-session-42", &TokenBudget::cloud(), &mem).await;
        assert!(
            ctx.episodic_summary.contains("my-session-42"),
            "episodic_summary should contain the session_id"
        );
    }
}
