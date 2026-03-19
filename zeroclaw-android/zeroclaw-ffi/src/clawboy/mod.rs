// Copyright (c) 2026 @Natfii. All rights reserved.

//! ClawBoy — AI-played Game Boy emulator subsystem.
//!
//! Embeds a Game Boy emulator (Boytacean) in the daemon, letting the AI
//! agent play Pokemon Red while users spectate via WebSocket viewer.

pub mod bridge;
pub mod chat;
pub mod emulator;
pub mod journal;
pub mod memory_map;
pub mod prompts;
pub mod session;
pub mod types;
pub mod viewer;
pub mod viewer_page;
