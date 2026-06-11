// Copyright (c) 2026 @Natfii. All rights reserved.

//! On-device repair completer — an optional rung supplied by the embedding
//! application (e.g. Gemini Nano on Android, registered through the FFI
//! layer) and tried before the configured provider's model rung.
//!
//! This crate knows nothing about the bridge: the app registers any
//! [`RepairCompleter`] in the process-global slot and the repair ladder
//! picks it up. The completer's answer goes through the same strict parse
//! and model-free validation gate as every other candidate, so a weak
//! on-device model can never adopt a broken spec — a failed attempt only
//! costs one local completion.

use std::sync::{Arc, OnceLock};

/// Synchronous completion backend for the repair prompt.
///
/// Implementations may block for tens of seconds (an on-device model loads
/// lazily on first use); the repair task invokes them on the blocking pool.
pub trait RepairCompleter: Send + Sync {
    /// Runs the repair prompt and returns the model's raw text answer.
    fn complete(&self, prompt: String) -> anyhow::Result<String>;
}

fn slot() -> &'static parking_lot::RwLock<Option<Arc<dyn RepairCompleter>>> {
    static SLOT: OnceLock<parking_lot::RwLock<Option<Arc<dyn RepairCompleter>>>> = OnceLock::new();
    SLOT.get_or_init(|| parking_lot::RwLock::new(None))
}

/// Registers the process-global on-device completer, or clears it with
/// `None` (e.g. at daemon shutdown).
pub fn set_repair_completer(completer: Option<Arc<dyn RepairCompleter>>) {
    *slot().write() = completer;
}

/// Snapshot of the currently registered completer, if any.
pub(crate) fn current() -> Option<Arc<dyn RepairCompleter>> {
    slot().read().clone()
}
