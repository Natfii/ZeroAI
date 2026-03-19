// Copyright (c) 2026 @Natfii. All rights reserved.

//! Shared types and constants for the email subsystem.

use std::time::Instant;

/// Maximum characters returned from a single email body read.
pub const MAX_READ_OUTPUT_CHARS: usize = 4_000;

/// Maximum number of messages fetched in a single IMAP FETCH call.
pub const MAX_FETCH_LIMIT: u32 = 50;

/// Maximum number of search results returned from an IMAP SEARCH.
pub const MAX_SEARCH_RESULTS: usize = 50;

/// Maximum outbound emails permitted per rolling hour.
pub const MAX_OUTBOUND_PER_HOUR: usize = 10;

/// Per-operation timeout applied to every IMAP/SMTP network call.
pub const OPERATION_TIMEOUT_SECS: u64 = 30;

/// Common folder names used by providers for the trash/deleted-items folder.
pub const TRASH_FOLDER_NAMES: &[&str] = &[
    "[Gmail]/Trash",
    "Trash",
    "Deleted Items",
    "Deleted Messages",
    "Bin",
];

/// A fully-parsed email message.
#[derive(Debug, Clone)]
pub struct ParsedEmail {
    /// IMAP UID of the message.
    pub uid: u32,
    /// Formatted sender string (e.g. `"Alice <alice@example.com>"`).
    pub sender: String,
    /// Subject line.
    pub subject: String,
    /// Plain-text body (may be truncated to [`MAX_READ_OUTPUT_CHARS`]).
    pub body: String,
    /// RFC 2822 `Message-ID` header value.
    pub message_id: String,
    /// Human-readable date string.
    pub date: String,
}

/// Lightweight summary returned by search operations (no body).
#[derive(Debug, Clone)]
pub struct EmailSummary {
    /// IMAP UID.
    pub uid: u32,
    /// Formatted sender string.
    pub sender: String,
    /// Subject line.
    pub subject: String,
    /// Human-readable date string.
    pub date: String,
}

/// Rate limiter that enforces [`MAX_OUTBOUND_PER_HOUR`] outbound sends
/// within any rolling 60-minute window.
pub struct SendRateLimiter {
    timestamps: parking_lot::Mutex<Vec<Instant>>,
}

impl SendRateLimiter {
    /// Creates a new rate limiter with an empty history.
    pub fn new() -> Self {
        Self {
            timestamps: parking_lot::Mutex::new(Vec::new()),
        }
    }

    /// Records a send attempt and returns `Ok(())` if within the hourly
    /// cap, or `Err` with a descriptive message if the limit is exceeded.
    pub fn check_and_record(&self) -> anyhow::Result<()> {
        let mut ts = self.timestamps.lock();
        let cutoff = Instant::now() - std::time::Duration::from_secs(3600);
        ts.retain(|t| *t > cutoff);
        if ts.len() >= MAX_OUTBOUND_PER_HOUR {
            anyhow::bail!(
                "outbound email rate limit exceeded ({} per hour)",
                MAX_OUTBOUND_PER_HOUR
            );
        }
        ts.push(Instant::now());
        Ok(())
    }
}

impl Default for SendRateLimiter {
    fn default() -> Self {
        Self::new()
    }
}
