// Copyright (c) 2026 @Natfii. All rights reserved.

//! Email subsystem — IMAP reading, SMTP sending, and agent tool wrappers.
//!
//! This module provides the [`EmailClient`] for connecting to a user-configured
//! mailbox over IMAP/SMTP plus the type definitions shared across the individual
//! email tool implementations.

pub mod check;
pub mod client;
pub mod compose;
pub mod delete;
pub mod empty_trash;
pub mod read;
pub mod reply;
pub mod search;
pub mod types;

pub use check::EmailCheckTool;
pub use compose::EmailComposeTool;
pub use delete::EmailDeleteTool;
pub use empty_trash::EmailEmptyTrashTool;
pub use read::EmailReadTool;
pub use reply::EmailReplyTool;
pub use search::EmailSearchTool;
