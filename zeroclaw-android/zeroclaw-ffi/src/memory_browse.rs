/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Memory browsing and management for the Android dashboard.
//!
//! Provides read-only access to the daemon's memory backend for listing,
//! searching, and counting memory entries. Also supports deleting entries
//! via the `forget` operation.

use crate::error::FfiError;

/// A memory entry with full scoring metadata.
///
/// Extends [`FfiMemoryEntry`] with confidence, source, tags, and access
/// count fields for the MemCore scoring pipeline.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiMemoryEntryScored {
    /// Unique identifier of this memory entry.
    pub id: String,
    /// Key under which the memory is stored.
    pub key: String,
    /// Content text of the memory entry.
    pub content: String,
    /// Category string: `"core"`, `"daily"`, `"conversation"`, or a custom name.
    pub category: String,
    /// RFC 3339 timestamp of when the entry was created.
    pub timestamp: String,
    /// Composite relevance score from the scoring pipeline.
    pub score: f64,
    /// Extraction confidence in `[0.0, 1.0]`.
    pub confidence: f64,
    /// Origin of this entry: `"heuristic"`, `"llm"`, `"agent"`, `"user"`, or `"migrated"`.
    pub source: String,
    /// Comma-separated tags describing the entry.
    pub tags: String,
    /// Number of times this entry has been accessed.
    pub access_count: u32,
}

/// Assembled working context for system prompt injection.
///
/// Maps to the upstream [`zeroai::memory::working_context::WorkingContext`]
/// for transfer across the FFI boundary.
///
/// Note: `estimated_tokens` uses saturating cast from internal `usize`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiWorkingContext {
    /// Core identity and user profile facts block.
    pub identity_block: String,
    /// Top-ranked semantic memories relevant to the current query.
    pub recall_block: String,
    /// Compressed summary of recent conversation history.
    pub episodic_summary: String,
    /// Total estimated token count of all blocks.
    pub estimated_tokens: u32,
}

/// Result of a daily maintenance run.
///
/// Reports counts of pruned (deleted) and merged (deduplicated) entries.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiMaintenanceReport {
    /// Number of entries pruned (deleted) during maintenance.
    pub pruned_count: u32,
    /// Number of entries merged (deduplicated) during maintenance.
    pub merged_count: u32,
}

/// A fact extracted by heuristic pattern matching.
///
/// Maps to the upstream [`zeroai::memory::heuristic::ExtractedFact`]
/// for transfer across the FFI boundary.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiExtractedFact {
    /// Key identifying the fact (e.g. `user_name`, `preference_a1b2c3d4`).
    pub key: String,
    /// The extracted content value.
    pub content: String,
    /// Category grouping (e.g. `"core"`).
    pub category: String,
    /// Comma-separated tags describing the fact.
    pub tags: String,
    /// Confidence score in `[0.0, 1.0]`.
    pub confidence: f64,
}

/// A memory entry suitable for transfer across the FFI boundary.
///
/// Maps to the upstream [`zeroclaw::memory::MemoryEntry`] but represents
/// the category as a plain string for FFI simplicity.
#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
pub struct FfiMemoryEntry {
    /// Unique identifier of this memory entry.
    pub id: String,
    /// Key under which the memory is stored.
    pub key: String,
    /// Content text of the memory entry.
    pub content: String,
    /// Category string: `"core"`, `"daily"`, `"conversation"`, or a custom name.
    pub category: String,
    /// RFC 3339 timestamp of when the entry was created.
    pub timestamp: String,
    /// Relevance score from a recall query, if applicable.
    pub score: Option<f64>,
}

/// Converts an upstream [`zeroclaw::memory::MemoryEntry`] to an [`FfiMemoryEntry`].
fn to_ffi(entry: &zeroclaw::memory::MemoryEntry) -> FfiMemoryEntry {
    FfiMemoryEntry {
        id: entry.id.clone(),
        key: entry.key.clone(),
        content: entry.content.clone(),
        category: entry.category.to_string(),
        timestamp: entry.timestamp.clone(),
        score: entry.score,
    }
}

/// Parses a category string into the upstream [`MemoryCategory`] enum.
fn parse_category(cat: &str) -> zeroclaw::memory::MemoryCategory {
    match cat {
        "core" => zeroclaw::memory::MemoryCategory::Core,
        "daily" => zeroclaw::memory::MemoryCategory::Daily,
        "conversation" => zeroclaw::memory::MemoryCategory::Conversation,
        other => zeroclaw::memory::MemoryCategory::Custom(other.to_string()),
    }
}

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Lists memory entries, optionally filtered by category and/or session.
    ///
    /// Categories: `"core"`, `"daily"`, `"conversation"`, or any custom
    /// category name. Pass `None` for all categories. When `session_id`
    /// is provided, only entries from that session are returned.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running or
    /// memory is unavailable, [`crate::FfiError::SpawnError`] on backend failure,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn list_memories(
        category: Option<String>,
        limit: u32,
        session_id: Option<String>,
    ) -> Vec<FfiMemoryEntry> = list_memories_inner
);

crate::ffi_export!(
    /// Searches memory entries by keyword query, optionally scoped to a session.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running or
    /// memory is unavailable, [`crate::FfiError::SpawnError`] on backend failure,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn recall_memory(
        query: String,
        limit: u32,
        session_id: Option<String>,
    ) -> Vec<FfiMemoryEntry> = recall_memory_inner
);

crate::ffi_export!(
    /// Deletes a memory entry by key.
    ///
    /// Returns `true` if the entry was found and deleted, `false` otherwise.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running or
    /// memory is unavailable, [`crate::FfiError::SpawnError`] on backend failure,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn forget_memory(key: String) -> bool = forget_memory_inner
);

crate::ffi_export!(
    /// Returns the total number of memory entries.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running or
    /// memory is unavailable, [`crate::FfiError::SpawnError`] on backend failure,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn memory_count() -> u32 = memory_count_inner
);

crate::ffi_export!(
    /// Stores a memory entry with full scoring metadata.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] if confidence is out of range,
    /// source is not a recognised value, or decay half-life is zero.
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running or
    /// memory is unavailable, [`crate::FfiError::SpawnError`] on backend failure,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn store_memory_with_metadata(
        key: String,
        content: String,
        category: String,
        confidence: f64,
        source: String,
        tags: String,
        decay_half_life_days: u32,
    ) -> () = store_memory_with_metadata_inner
);

crate::ffi_export!(
    /// Searches memory entries by query, returning scored results with metadata.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running or
    /// memory is unavailable, [`crate::FfiError::SpawnError`] on backend failure,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn recall_memory_scored(
        query: String,
        limit: u32,
        session_id: Option<String>,
    ) -> Vec<FfiMemoryEntryScored> = recall_memory_scored_inner
);

crate::ffi_export!(
    /// Assembles working context for system prompt injection.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running or
    /// memory is unavailable, [`crate::FfiError::SpawnError`] on backend failure,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn assemble_context(
        message: String,
        session_id: String,
        token_budget: u32,
    ) -> FfiWorkingContext = assemble_context_inner
);

crate::ffi_export!(
    /// Runs daily memory maintenance (pruning and merging).
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn run_memory_maintenance() -> FfiMaintenanceReport = run_memory_maintenance_inner
);

crate::ffi_export!(
    /// Extracts facts from a user message using heuristic pattern matching.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn extract_facts(message: String) -> Vec<FfiExtractedFact> = extract_facts_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

pub(crate) fn list_memories_inner(
    category: Option<String>,
    limit: u32,
    session_id: Option<String>,
) -> Result<Vec<FfiMemoryEntry>, FfiError> {
    crate::runtime::with_memory(|memory, handle| {
        let cat = category.as_deref().map(parse_category);
        let entries = handle
            .block_on(memory.list(cat.as_ref(), session_id.as_deref()))
            .map_err(|e| FfiError::SpawnError {
                detail: format!("memory list failed: {e}"),
            })?;
        let limit = limit as usize;
        Ok(entries.iter().take(limit).map(to_ffi).collect())
    })
}

/// Searches memory entries by keyword query.
///
/// Returns up to `limit` entries ranked by relevance. The `score` field
/// on each entry indicates the match quality.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// the memory backend is not available, or [`FfiError::SpawnError`]
/// on backend access failure.
pub(crate) fn recall_memory_inner(
    query: String,
    limit: u32,
    session_id: Option<String>,
) -> Result<Vec<FfiMemoryEntry>, FfiError> {
    crate::runtime::with_memory(|memory, handle| {
        let entries = handle
            .block_on(memory.recall(&query, limit as usize, session_id.as_deref(), None, None))
            .map_err(|e| FfiError::SpawnError {
                detail: format!("memory recall failed: {e}"),
            })?;
        Ok(entries.iter().map(to_ffi).collect())
    })
}

/// Deletes a memory entry by key.
///
/// Returns `true` if the entry was found and deleted, `false` otherwise.
/// On success, re-exports `MEMORY_SNAPSHOT.md` so that the snapshot stays
/// in sync with the database. Without this, auto-hydration on a future
/// cold boot would resurrect the deleted entry.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// the memory backend is not available, or [`FfiError::SpawnError`]
/// on backend access failure.
pub(crate) fn forget_memory_inner(key: String) -> Result<bool, FfiError> {
    let deleted = crate::runtime::with_memory(|memory, handle| {
        handle
            .block_on(memory.forget(&key))
            .map_err(|e| FfiError::SpawnError {
                detail: format!("memory forget failed: {e}"),
            })
    })?;

    if deleted
        && let Ok(workspace) = crate::runtime::with_daemon_config(|c| c.data_dir.clone())
        && let Err(e) = zeroclaw::memory::snapshot::export_snapshot(&workspace)
    {
        tracing::warn!("snapshot re-export after forget failed: {e}");
    }

    Ok(deleted)
}

/// Returns the total number of memory entries.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// the memory backend is not available, or [`FfiError::SpawnError`]
/// on backend access failure.
pub(crate) fn memory_count_inner() -> Result<u32, FfiError> {
    crate::runtime::with_memory(|memory, handle| {
        let count = handle
            .block_on(memory.count())
            .map_err(|e| FfiError::SpawnError {
                detail: format!("memory count failed: {e}"),
            })?;
        Ok(u32::try_from(count).unwrap_or(u32::MAX))
    })
}

/// Valid source values for [`store_memory_with_metadata_inner`].
const VALID_SOURCES: &[&str] = &["heuristic", "llm", "agent", "user", "migrated"];

/// Stores a memory entry with full scoring metadata.
///
/// Validates all arguments before delegating to the trait's
/// [`store_with_metadata`](zeroclaw::memory::Memory::store_with_metadata).
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if confidence is out of range,
/// source is not a recognised value, or decay half-life is zero.
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// memory is unavailable, or [`FfiError::SpawnError`] on backend failure.
pub(crate) fn store_memory_with_metadata_inner(
    key: String,
    content: String,
    category: String,
    confidence: f64,
    source: String,
    tags: String,
    decay_half_life_days: u32,
) -> Result<(), FfiError> {
    if confidence.is_nan() || !(0.0..=1.0).contains(&confidence) {
        return Err(FfiError::InvalidArgument {
            detail: format!(
                "confidence must be in [0.0, 1.0] and not NaN, got {confidence}"
            ),
        });
    }
    if !VALID_SOURCES.contains(&source.as_str()) {
        return Err(FfiError::InvalidArgument {
            detail: format!(
                "source must be one of {VALID_SOURCES:?}, got \"{source}\""
            ),
        });
    }
    if decay_half_life_days == 0 {
        return Err(FfiError::InvalidArgument {
            detail: "decay_half_life_days must be > 0".into(),
        });
    }

    let cat = parse_category(&category);

    // Upstream trimmed `store_with_metadata` to (key, content,
    // category, session_id, namespace, importance) — `source`, `tags`,
    // and `decay_half_life_days` are no longer carried by the trait.
    // We still validate them above so the Android caller gets the same
    // surface-level guarantees, but they are not forwarded to the
    // backend until upstream restores per-entry metadata.
    let _ = (&source, &tags, decay_half_life_days);

    crate::runtime::with_memory(|memory, handle| {
        handle
            .block_on(memory.store_with_metadata(
                &key,
                &content,
                cat,
                None,
                None,
                Some(confidence),
            ))
            .map_err(|e| FfiError::SpawnError {
                detail: format!("store_with_metadata failed: {e}"),
            })
    })
}

/// Searches memory entries by query, returning scored results with metadata.
///
/// This is a first-pass implementation that maps basic [`MemoryEntry`]
/// fields. Full scoring metadata (confidence, source, tags, access_count)
/// will be populated when the recall path can downcast to `SqliteMemory`.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// memory is unavailable, or [`FfiError::SpawnError`] on backend failure.
pub(crate) fn recall_memory_scored_inner(
    query: String,
    limit: u32,
    session_id: Option<String>,
) -> Result<Vec<FfiMemoryEntryScored>, FfiError> {
    crate::runtime::with_memory(|memory, handle| {
        let entries = handle
            .block_on(memory.recall(&query, limit as usize, session_id.as_deref(), None, None))
            .map_err(|e| FfiError::SpawnError {
                detail: format!("memory recall_scored failed: {e}"),
            })?;
        Ok(entries
            .iter()
            .map(|e| FfiMemoryEntryScored {
                id: e.id.clone(),
                key: e.key.clone(),
                content: e.content.clone(),
                category: e.category.to_string(),
                timestamp: e.timestamp.clone(),
                score: e.score.unwrap_or(0.0),
                confidence: 0.0,
                source: String::new(),
                tags: String::new(),
                access_count: 0,
            })
            .collect())
    })
}

/// Assembles working context for system prompt injection.
///
/// Creates a [`TokenBudget`] from the total budget using the cloud
/// profile split (36%/36%/28%), then delegates to
/// [`assemble_working_context`](zeroai::memory::working_context::assemble_working_context).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// memory is unavailable, or [`FfiError::SpawnError`] on backend failure.
pub(crate) fn assemble_context_inner(
    message: String,
    session_id: String,
    token_budget: u32,
) -> Result<FfiWorkingContext, FfiError> {
    use zeroai::memory::working_context::{TokenBudget, assemble_working_context};

    let total = token_budget as usize;
    let budget = if total == 0 {
        TokenBudget::cloud()
    } else {
        TokenBudget {
            identity: total * 36 / 100,
            recall: total * 36 / 100,
            episodic: total - (total * 36 / 100) - (total * 36 / 100),
        }
    };

    crate::runtime::with_memory(|memory, handle| {
        let ctx = handle
            .block_on(assemble_working_context(&message, &session_id, &budget, memory));
        Ok(FfiWorkingContext {
            identity_block: ctx.identity_block,
            recall_block: ctx.recall_block,
            episodic_summary: ctx.episodic_summary,
            estimated_tokens: ctx.estimated_tokens.min(u32::MAX as usize) as u32,
        })
    })
}

/// Runs daily memory maintenance (pruning stale entries, merging duplicates).
///
/// Currently a stub that returns zero counts. Actual pruning and merging
/// logic will be wired in a later task when `consolidation.rs` is built.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn run_memory_maintenance_inner() -> Result<FfiMaintenanceReport, FfiError> {
    // Stubbed until a consolidation module exists. The intended flow
    // is to call `consolidation::prune_stale()` and
    // `consolidation::merge_duplicates()` via `with_memory()` and
    // return the actual counts.
    Ok(FfiMaintenanceReport {
        pruned_count: 0,
        merged_count: 0,
    })
}

/// Extracts facts from a user message using heuristic pattern matching.
///
/// Delegates to [`zeroai::memory::heuristic::extract_facts`] which runs
/// 8 regex rules in ~50us per message with zero network cost.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] only if the regex engine panics
/// (should not happen with compiled rules).
pub(crate) fn extract_facts_inner(message: String) -> Result<Vec<FfiExtractedFact>, FfiError> {
    let facts = zeroai::memory::heuristic::extract_facts(&message);
    Ok(facts
        .into_iter()
        .map(|f| FfiExtractedFact {
            key: f.key,
            content: f.content,
            category: f.category,
            tags: f.tags,
            confidence: f.confidence,
        })
        .collect())
}


#[cfg(test)]
#[path = "memory_browse_tests.rs"]
mod tests;
