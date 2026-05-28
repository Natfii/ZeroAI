// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for `crate::memory_browse`.

#![allow(clippy::unwrap_used)]

use super::*;

#[test]
fn test_list_memories_not_running() {
    let result = list_memories_inner(None, 100, None);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_list_memories_with_session_not_running() {
    let result = list_memories_inner(Some("core".into()), 50, Some("session-abc".into()));
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_recall_memory_not_running() {
    let result = recall_memory_inner("test query".into(), 10, None);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_recall_memory_with_session_not_running() {
    let result = recall_memory_inner("test query".into(), 10, Some("session-xyz".into()));
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_forget_memory_not_running() {
    let result = forget_memory_inner("test-key".into());
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_memory_count_not_running() {
    let result = memory_count_inner();
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_parse_category_core() {
    assert!(matches!(
        parse_category("core"),
        zeroclaw::memory::MemoryCategory::Core
    ));
}

#[test]
fn test_parse_category_daily() {
    assert!(matches!(
        parse_category("daily"),
        zeroclaw::memory::MemoryCategory::Daily
    ));
}

#[test]
fn test_parse_category_conversation() {
    assert!(matches!(
        parse_category("conversation"),
        zeroclaw::memory::MemoryCategory::Conversation
    ));
}

#[test]
fn test_parse_category_custom() {
    let cat = parse_category("project_notes");
    assert!(matches!(
        cat,
        zeroclaw::memory::MemoryCategory::Custom(ref s) if s == "project_notes"
    ));
}

#[test]
fn test_to_ffi_conversion() {
    let entry = zeroclaw::memory::MemoryEntry {
        id: "id-1".into(),
        key: "favourite_lang".into(),
        content: "Rust".into(),
        category: zeroclaw::memory::MemoryCategory::Core,
        timestamp: "2026-02-18T12:00:00Z".into(),
        session_id: Some("session-1".into()),
        score: Some(0.95),
        namespace: "default".into(),
        importance: None,
        superseded_by: None,
        agent_alias: None,
        agent_id: None,
    };

    let ffi = to_ffi(&entry);
    assert_eq!(ffi.id, "id-1");
    assert_eq!(ffi.key, "favourite_lang");
    assert_eq!(ffi.content, "Rust");
    assert_eq!(ffi.category, "core");
    assert_eq!(ffi.timestamp, "2026-02-18T12:00:00Z");
    assert_eq!(ffi.score, Some(0.95));
}
