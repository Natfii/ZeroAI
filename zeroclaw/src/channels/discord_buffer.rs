// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

//! In-memory message buffer for batched Discord archive writes.
//!
//! Sits between the Discord Gateway WebSocket listener and the SQLite archive.
//! Messages accumulate in memory and are flushed in batches to minimise disk
//! I/O on mobile devices. The buffer itself is passive — it signals when it
//! reaches capacity but does not flush autonomously. Flush-trigger logic
//! (capacity, timer, app foregrounding) lives in [`super::discord`].

use crate::memory::discord_archive::ArchiveMessage;
use parking_lot::Mutex;

/// Thread-safe accumulator for [`ArchiveMessage`]s.
///
/// The buffer stores messages behind a [`Mutex`] so it can be shared across
/// the WebSocket listener task and the flush driver without an async lock.
///
/// # Capacity semantics
///
/// `capacity` is a *flush threshold*, not a hard limit. [`push`](Self::push)
/// never rejects a message — it always succeeds. [`should_flush`](Self::should_flush)
/// returns `true` once the number of buffered messages reaches or exceeds the
/// configured capacity.
pub struct MessageBuffer {
    /// Flush-threshold: the buffer signals readiness once it holds this many
    /// messages.
    capacity: usize,
    /// Interior-mutable message store.
    inner: Mutex<Vec<ArchiveMessage>>,
}

impl MessageBuffer {
    /// Creates an empty buffer that signals flush at `capacity` messages.
    ///
    /// # Panics
    ///
    /// Panics if `capacity` is zero.
    pub fn new(capacity: usize) -> Self {
        assert!(capacity > 0, "MessageBuffer capacity must be > 0");
        Self {
            capacity,
            inner: Mutex::new(Vec::with_capacity(capacity)),
        }
    }

    /// Appends a message to the buffer.
    ///
    /// This method never fails or blocks beyond the mutex acquisition.
    pub fn push(&self, msg: ArchiveMessage) {
        let mut buf = self.inner.lock();
        buf.push(msg);
    }

    /// Returns `true` when the buffer has accumulated at least `capacity`
    /// messages and should be flushed.
    pub fn should_flush(&self) -> bool {
        let buf = self.inner.lock();
        buf.len() >= self.capacity
    }

    /// Returns the number of messages currently in the buffer.
    pub fn len(&self) -> usize {
        let buf = self.inner.lock();
        buf.len()
    }

    /// Returns `true` if the buffer contains no messages.
    pub fn is_empty(&self) -> bool {
        let buf = self.inner.lock();
        buf.is_empty()
    }

    /// Takes all buffered messages out and resets the buffer to empty.
    ///
    /// The returned [`Vec`] owns the messages; the internal buffer is
    /// replaced with a new empty [`Vec`].
    pub fn drain(&self) -> Vec<ArchiveMessage> {
        let mut buf = self.inner.lock();
        std::mem::take(&mut *buf)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: build a minimal [`ArchiveMessage`] with the given index baked
    /// into the `id` field for easy identification.
    fn make_message(index: u32) -> ArchiveMessage {
        ArchiveMessage {
            id: format!("msg_{index}"),
            channel_id: "ch_1".to_string(),
            guild_id: "guild_1".to_string(),
            author_id: "author_1".to_string(),
            author_name: "zeroclaw_user".to_string(),
            content: format!("hello {index}"),
            timestamp: 1_700_000_000 + i64::from(index),
        }
    }

    #[test]
    fn buffer_accumulates_below_capacity_without_flush_signal() {
        let buf = MessageBuffer::new(5);

        assert!(buf.is_empty());
        assert_eq!(buf.len(), 0);
        assert!(!buf.should_flush());

        for i in 0..4 {
            buf.push(make_message(i));
        }

        assert_eq!(buf.len(), 4);
        assert!(
            !buf.should_flush(),
            "should not signal flush below capacity"
        );
    }

    #[test]
    fn buffer_signals_flush_at_capacity() {
        let buf = MessageBuffer::new(3);

        buf.push(make_message(0));
        buf.push(make_message(1));
        assert!(!buf.should_flush(), "2 < 3, not yet at capacity");

        buf.push(make_message(2));
        assert!(
            buf.should_flush(),
            "3 == 3, should signal flush at capacity"
        );
        assert_eq!(buf.len(), 3);

        buf.push(make_message(3));
        assert!(buf.should_flush(), "4 > 3, should still signal flush");
        assert_eq!(buf.len(), 4);
    }

    #[test]
    fn drain_returns_messages_and_clears_buffer() {
        let buf = MessageBuffer::new(10);

        for i in 0..5 {
            buf.push(make_message(i));
        }
        assert_eq!(buf.len(), 5);

        let drained = buf.drain();

        assert_eq!(drained.len(), 5);
        assert_eq!(drained[0].id, "msg_0");
        assert_eq!(drained[4].id, "msg_4");

        assert!(buf.is_empty(), "buffer must be empty after drain");
        assert_eq!(buf.len(), 0);
        assert!(!buf.should_flush());
    }
}
