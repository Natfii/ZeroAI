// Copyright (c) 2026 @Natfii. All rights reserved.

//! Session state persistence for Android process death recovery.
//!
//! Serializes conversation history to JSON files in app-private storage.
//! Ported from upstream zeroclaw `src/agent/loop_.rs:336-375` (v0.2.0).
//!
//! The on-disk format is a versioned JSON envelope ([`InteractiveSessionState`])
//! containing the full conversation transcript. Schema version is checked on
//! load so that future format changes can be migrated gracefully.

use crate::FfiError;
use serde::{Deserialize, Serialize};
use std::path::Path;
use zeroclaw::providers::ChatMessage;

/// Current schema version written by [`save_interactive_session_history`].
const CURRENT_VERSION: u32 = 1;

/// Serializable session state envelope.
///
/// Wraps the conversation history with a schema version tag so that
/// future format changes can be detected and migrated on load.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct InteractiveSessionState {
    /// Schema version for forward compatibility.
    pub version: u32,
    /// Full conversation history including system prompt.
    pub history: Vec<ChatMessage>,
}

impl InteractiveSessionState {
    /// Creates a new state envelope from a conversation history slice.
    pub fn from_history(history: &[ChatMessage]) -> Self {
        Self {
            version: CURRENT_VERSION,
            history: history.to_vec(),
        }
    }
}

/// Loads persisted session history from a JSON file.
///
/// If the file does not exist, returns a single-element vector containing
/// only the system prompt message. If the file exists, deserializes the
/// [`InteractiveSessionState`] envelope and ensures the system prompt is
/// the first message (replacing any stale system prompt from the file).
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if the file exists but contains
/// invalid JSON or an unrecognised schema version.
pub(crate) fn load_interactive_session_history(
    path: &Path,
    system_prompt: &str,
) -> Result<Vec<ChatMessage>, FfiError> {
    if !path.exists() {
        return Ok(vec![ChatMessage::system(system_prompt)]);
    }

    let data = std::fs::read_to_string(path).map_err(|e| FfiError::ConfigError {
        detail: format!("failed to read session file {}: {e}", path.display()),
    })?;

    let state: InteractiveSessionState =
        serde_json::from_str(&data).map_err(|e| FfiError::ConfigError {
            detail: format!("failed to parse session file {}: {e}", path.display()),
        })?;

    if state.version > CURRENT_VERSION {
        return Err(FfiError::ConfigError {
            detail: format!(
                "session file {} has version {} but this build only supports up to {}",
                path.display(),
                state.version,
                CURRENT_VERSION
            ),
        });
    }

    // Rebuild history with the current system prompt at the front,
    // dropping any stale system message from the persisted file.
    let mut history = vec![ChatMessage::system(system_prompt)];
    for msg in &state.history {
        if msg.role != "system" {
            history.push(msg.clone());
        }
    }

    Ok(history)
}

/// Saves session history to a JSON file.
///
/// Creates parent directories if they do not exist. The file is written
/// as pretty-printed JSON for debuggability.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] on I/O or serialization failures.
pub(crate) fn save_interactive_session_history(
    path: &Path,
    history: &[ChatMessage],
) -> Result<(), FfiError> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| FfiError::ConfigError {
            detail: format!(
                "failed to create parent directories for {}: {e}",
                path.display()
            ),
        })?;
    }

    let state = InteractiveSessionState::from_history(history);
    let json = serde_json::to_string_pretty(&state).map_err(|e| FfiError::ConfigError {
        detail: format!("failed to serialize session state: {e}"),
    })?;

    std::fs::write(path, json).map_err(|e| FfiError::ConfigError {
        detail: format!("failed to write session file {}: {e}", path.display()),
    })?;

    Ok(())
}

/// Lists session IDs from a directory of `.json` files.
///
/// Each `.json` file's stem (filename without extension) is returned as
/// a session ID. If the directory does not exist, returns an empty vector.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if the directory exists but cannot
/// be read.
pub(crate) fn list_persisted_sessions(dir: &Path) -> Result<Vec<String>, FfiError> {
    if !dir.exists() {
        return Ok(Vec::new());
    }

    let entries = std::fs::read_dir(dir).map_err(|e| FfiError::ConfigError {
        detail: format!("failed to read session directory {}: {e}", dir.display()),
    })?;

    let mut ids = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|e| FfiError::ConfigError {
            detail: format!("failed to read directory entry: {e}"),
        })?;

        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) == Some("json")
            && let Some(stem) = path.file_stem().and_then(|s| s.to_str())
        {
            ids.push(stem.to_string());
        }
    }

    ids.sort();
    Ok(ids)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    /// Verifies that saving and loading a conversation history round-trips
    /// all messages faithfully.
    #[test]
    fn session_state_round_trips_history() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("test-session.json");

        let history = vec![
            ChatMessage::system("You are a helpful assistant."),
            ChatMessage::user("Hello!"),
            ChatMessage::assistant("Hi there! How can I help?"),
            ChatMessage::user("What is Rust?"),
            ChatMessage::assistant("Rust is a systems programming language."),
        ];

        save_interactive_session_history(&path, &history).unwrap();
        let loaded =
            load_interactive_session_history(&path, "You are a helpful assistant.").unwrap();

        assert_eq!(loaded.len(), history.len());
        for (original, restored) in history.iter().zip(loaded.iter()) {
            assert_eq!(original.role, restored.role);
            assert_eq!(original.content, restored.content);
        }
    }

    /// Verifies that loading a file with no system message prepends the
    /// provided system prompt.
    #[test]
    fn session_state_adds_missing_system_prompt() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("no-system.json");

        // Save history without a system message.
        let history = vec![ChatMessage::user("Hello!"), ChatMessage::assistant("Hi!")];
        save_interactive_session_history(&path, &history).unwrap();

        let loaded = load_interactive_session_history(&path, "You are a test assistant.").unwrap();

        assert_eq!(loaded.len(), 3);
        assert_eq!(loaded[0].role, "system");
        assert_eq!(loaded[0].content, "You are a test assistant.");
        assert_eq!(loaded[1].role, "user");
        assert_eq!(loaded[1].content, "Hello!");
        assert_eq!(loaded[2].role, "assistant");
        assert_eq!(loaded[2].content, "Hi!");
    }

    /// Verifies that loading a corrupt (non-JSON) file returns an error.
    #[test]
    fn session_state_handles_corrupt_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("corrupt.json");

        std::fs::write(&path, "this is not valid json {{{").unwrap();

        let result = load_interactive_session_history(&path, "system prompt");
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("failed to parse session file"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    /// Verifies that listing a directory with `.json` files returns the
    /// file stems as session IDs.
    #[test]
    fn list_persisted_sessions_returns_ids() {
        let dir = tempfile::tempdir().unwrap();

        // Create some session files.
        std::fs::write(dir.path().join("session-alpha.json"), "{}").unwrap();
        std::fs::write(dir.path().join("session-beta.json"), "{}").unwrap();
        std::fs::write(dir.path().join("not-a-session.txt"), "ignored").unwrap();

        let ids = list_persisted_sessions(dir.path()).unwrap();
        assert_eq!(ids, vec!["session-alpha", "session-beta"]);
    }

    /// Verifies that listing an empty directory returns an empty vector.
    #[test]
    fn list_persisted_sessions_empty_dir() {
        let dir = tempfile::tempdir().unwrap();
        let ids = list_persisted_sessions(dir.path()).unwrap();
        assert!(ids.is_empty());
    }

    /// Verifies that listing a nonexistent directory returns an empty
    /// vector instead of an error.
    #[test]
    fn list_persisted_sessions_nonexistent_dir() {
        let path = std::path::PathBuf::from("/tmp/nonexistent-session-dir-12345");
        let ids = list_persisted_sessions(&path).unwrap();
        assert!(ids.is_empty());
    }
}
