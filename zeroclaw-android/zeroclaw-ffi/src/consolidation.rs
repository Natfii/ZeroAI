/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Consolidation FFI module — LLM-based startup extraction pipeline.
//!
//! Provides three functions for the Kotlin layer:
//! - [`add_to_consolidation_backlog_inner`]: queues unextracted messages
//! - [`consolidation_backlog_count_inner`]: counts pending messages
//! - [`run_startup_consolidation_inner`]: processes backlog through an LLM

use crate::error::FfiError;
use zeroclaw::memory::consolidation::BacklogMessage;
use zeroclaw::memory::sqlite::SqliteMemory;

/// Report produced by a consolidation run, suitable for FFI transfer.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiConsolidationReport {
    /// Number of facts extracted and stored.
    pub facts_extracted: u32,
    /// Number of sessions that received summaries.
    pub sessions_summarized: u32,
    /// Errors encountered (capped at 10, each truncated to 200 chars).
    pub errors: Vec<String>,
}

/// Maximum message length stored in the backlog (characters).
const MAX_MESSAGE_LEN: usize = 4096;

/// Maximum rows retained in the backlog table.
const MAX_BACKLOG_ROWS: usize = 200;

/// Downcasts `&dyn Memory` to `&SqliteMemory`, returning an [`FfiError`] on failure.
fn downcast_sqlite(memory: &dyn zeroclaw::memory::traits::Memory) -> Result<&SqliteMemory, FfiError> {
    memory
        .as_any()
        .downcast_ref::<SqliteMemory>()
        .ok_or_else(|| FfiError::StateError {
            detail: "memory backend is not SQLite".into(),
        })
}

/// Parses a category string to [`MemoryCategory`].
fn parse_category(cat: &str) -> zeroclaw::memory::traits::MemoryCategory {
    match cat {
        "core" => zeroclaw::memory::traits::MemoryCategory::Core,
        "daily" => zeroclaw::memory::traits::MemoryCategory::Daily,
        "conversation" => zeroclaw::memory::traits::MemoryCategory::Conversation,
        other => zeroclaw::memory::traits::MemoryCategory::Custom(other.to_string()),
    }
}

/// Adds a message to the consolidation backlog for later LLM extraction.
///
/// Truncates `message_text` to [`MAX_MESSAGE_LEN`] characters and prunes the
/// backlog to [`MAX_BACKLOG_ROWS`] oldest entries after insertion.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the memory
/// backend is unavailable, [`FfiError::SpawnError`] on SQLite failure.
pub(crate) fn add_to_consolidation_backlog_inner(
    session_id: String,
    message_text: String,
) -> Result<(), FfiError> {
    crate::runtime::with_memory(|memory, _handle| {
        let sqlite = downcast_sqlite(memory)?;
        let conn = sqlite.conn.lock();
        let id = uuid::Uuid::new_v4().to_string();
        let now = chrono::Local::now().to_rfc3339();
        let truncated = if message_text.len() > MAX_MESSAGE_LEN {
            message_text[..MAX_MESSAGE_LEN].to_string()
        } else {
            message_text
        };

        conn.execute(
            "INSERT OR IGNORE INTO consolidation_backlog (id, session_id, message_text, created_at) \
             VALUES (?1, ?2, ?3, ?4)",
            rusqlite::params![id, session_id, truncated, now],
        )
        .map_err(|e| FfiError::SpawnError {
            detail: format!("backlog insert failed: {e}"),
        })?;

        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM consolidation_backlog", [], |r| r.get(0))
            .map_err(|e| FfiError::SpawnError {
                detail: format!("backlog count failed: {e}"),
            })?;

        if count as usize > MAX_BACKLOG_ROWS {
            let excess = count as usize - MAX_BACKLOG_ROWS;
            conn.execute(
                "DELETE FROM consolidation_backlog WHERE id IN \
                 (SELECT id FROM consolidation_backlog ORDER BY created_at ASC LIMIT ?1)",
                rusqlite::params![excess],
            )
            .map_err(|e| FfiError::SpawnError {
                detail: format!("backlog prune failed: {e}"),
            })?;
        }

        Ok(())
    })
}

/// Returns the number of pending messages in the consolidation backlog.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the memory
/// backend is unavailable, [`FfiError::SpawnError`] on SQLite failure.
pub(crate) fn consolidation_backlog_count_inner() -> Result<u32, FfiError> {
    crate::runtime::with_memory(|memory, _handle| {
        let sqlite = downcast_sqlite(memory)?;
        let conn = sqlite.conn.lock();
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM consolidation_backlog", [], |r| r.get(0))
            .map_err(|e| FfiError::SpawnError {
                detail: format!("backlog count failed: {e}"),
            })?;
        Ok(count as u32)
    })
}

/// Runs startup consolidation: reads the backlog, builds prompts, sends to the
/// configured provider, parses responses, and stores extracted facts.
///
/// Checks `smart_extraction` config flag before proceeding. Returns early with
/// a zero report if disabled.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on provider or storage failures.
pub(crate) fn run_startup_consolidation_inner() -> Result<FfiConsolidationReport, FfiError> {
    use zeroclaw::memory::consolidation::{
        build_consolidation_prompts, parse_consolidation_response, validate_extracted_fact,
    };

    let smart_extraction = crate::runtime::with_daemon_config(|cfg| cfg.memory.smart_extraction)?;
    if !smart_extraction {
        return Ok(FfiConsolidationReport {
            facts_extracted: 0,
            sessions_summarized: 0,
            errors: vec!["smart_extraction disabled".into()],
        });
    }

    let messages: Vec<BacklogMessage> = crate::runtime::with_memory(|memory, _handle| {
        let sqlite = downcast_sqlite(memory)?;
        let conn = sqlite.conn.lock();
        let mut stmt = conn
            .prepare(
                "SELECT session_id, message_text, created_at \
                 FROM consolidation_backlog ORDER BY created_at ASC",
            )
            .map_err(|e| FfiError::SpawnError {
                detail: format!("backlog read failed: {e}"),
            })?;

        let rows = stmt
            .query_map([], |row| {
                Ok(BacklogMessage {
                    session_id: row.get(0)?,
                    message_text: row.get(1)?,
                    created_at: row.get(2)?,
                })
            })
            .map_err(|e| FfiError::SpawnError {
                detail: format!("backlog query failed: {e}"),
            })?
            .filter_map(|r| r.ok())
            .collect();

        Ok(rows)
    })?;

    if messages.is_empty() {
        return Ok(FfiConsolidationReport {
            facts_extracted: 0,
            sessions_summarized: 0,
            errors: vec![],
        });
    }

    let config = crate::runtime::clone_daemon_config()?;
    let provider_name = config.effective_provider().to_string();
    let model = config.effective_model().to_string();
    let provider =
        zeroclaw::providers::create_provider(&provider_name, config.api_key.as_deref())
            .map_err(|e| FfiError::SpawnError {
                detail: format!("failed to create provider '{provider_name}': {e}"),
            })?;

    let prompts = build_consolidation_prompts(&messages, 30);
    let handle = crate::runtime::get_or_create_runtime()?;

    let mut facts_extracted: u32 = 0;
    let mut sessions_summarized: u32 = 0;
    let mut errors: Vec<String> = Vec::new();

    for prompt in &prompts {
        let result = handle.block_on(async {
            tokio::time::timeout(
                std::time::Duration::from_secs(60),
                provider.simple_chat(prompt, &model, 0.3),
            )
            .await
        });

        match result {
            Ok(Ok(response)) => match parse_consolidation_response(&response) {
                Ok(parsed) => {
                    for fact in &parsed.facts {
                        if !validate_extracted_fact(fact) {
                            continue;
                        }
                        let category = parse_category(&fact.category);
                        let store_result = crate::runtime::with_memory(|memory, handle| {
                            handle
                                .block_on(memory.store_with_metadata(
                                    &fact.key,
                                    &fact.content,
                                    category.clone(),
                                    None,
                                    fact.confidence,
                                    "llm",
                                    &fact.tags,
                                    365,
                                ))
                                .map_err(|e| FfiError::SpawnError {
                                    detail: format!("store failed for '{}': {e}", fact.key),
                                })
                        });
                        match store_result {
                            Ok(()) => facts_extracted += 1,
                            Err(e) => {
                                if errors.len() < 10 {
                                    let msg = format!("{e}");
                                    errors.push(msg[..msg.len().min(200)].to_string());
                                }
                            }
                        }
                    }
                    sessions_summarized += parsed.summaries.len() as u32;
                }
                Err(e) => {
                    if errors.len() < 10 {
                        errors.push(format!("parse error: {}", &e[..e.len().min(200)]));
                    }
                }
            },
            Ok(Err(e)) => {
                if errors.len() < 10 {
                    let msg = format!("provider error: {e}");
                    errors.push(msg[..msg.len().min(200)].to_string());
                }
            }
            Err(_) => {
                if errors.len() < 10 {
                    errors.push("provider request timed out (60s)".into());
                }
            }
        }
    }

    if facts_extracted > 0 {
        let _ = crate::runtime::with_memory(|memory, _handle| {
            let sqlite = downcast_sqlite(memory)?;
            let conn = sqlite.conn.lock();
            conn.execute("DELETE FROM consolidation_backlog", [])
                .map_err(|e| FfiError::SpawnError {
                    detail: format!("backlog clear failed: {e}"),
                })?;
            Ok(())
        });
    }

    Ok(FfiConsolidationReport {
        facts_extracted,
        sessions_summarized,
        errors,
    })
}
