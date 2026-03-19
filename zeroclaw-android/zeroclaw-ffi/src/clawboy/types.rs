// Copyright (c) 2026 @Natfii. All rights reserved.

//! Shared types for the ClawBoy subsystem.

use std::fmt;

// ── UniFFI-exported types (become Kotlin types) ─────────────────────

/// Information about an active ClawBoy session.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ClawBoySessionInfo {
    /// WebSocket viewer URL (e.g. `"http://192.168.1.5:8432"`).
    pub viewer_url: String,
    /// Port the viewer is bound to.
    pub port: u16,
}

/// Result of ROM SHA-1 verification.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RomVerification {
    /// Whether the ROM matches the expected Pokemon Red hash.
    pub valid: bool,
    /// Computed SHA-1 hash string for logging.
    pub sha1: String,
}

/// Current state of the ClawBoy emulator.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum ClawBoyStatus {
    /// No session active.
    Idle,
    /// Emulator is running.
    Playing {
        /// WebSocket viewer URL.
        viewer_url: String,
        /// Seconds since session started.
        play_time_seconds: u64,
    },
    /// Emulator is paused (battery saver, LLM timeout).
    Paused {
        /// Reason for pause.
        reason: String,
    },
    /// Session ended with an error.
    Error {
        /// Error description.
        message: String,
    },
}

// ── Internal types (Rust-only, not exported via UniFFI) ─────────────

/// Game Boy button input.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)]
pub enum GbButton {
    /// The A button.
    A,
    /// The B button.
    B,
    /// The Start button.
    Start,
    /// The Select button.
    Select,
    /// D-pad up.
    Up,
    /// D-pad down.
    Down,
    /// D-pad left.
    Left,
    /// D-pad right.
    Right,
}

/// Error returned when a string cannot be parsed into a [`GbButton`].
#[derive(Debug, Clone, PartialEq, Eq)]
#[allow(dead_code)]
pub struct UnknownButton(pub String);

impl fmt::Display for UnknownButton {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "unknown Game Boy button: \"{}\"", self.0)
    }
}

impl std::error::Error for UnknownButton {}

impl TryFrom<&str> for GbButton {
    type Error = UnknownButton;

    fn try_from(s: &str) -> Result<Self, Self::Error> {
        match s.to_ascii_uppercase().as_str() {
            "A" => Ok(Self::A),
            "B" => Ok(Self::B),
            "START" => Ok(Self::Start),
            "SELECT" => Ok(Self::Select),
            "UP" => Ok(Self::Up),
            "DOWN" => Ok(Self::Down),
            "LEFT" => Ok(Self::Left),
            "RIGHT" => Ok(Self::Right),
            _ => Err(UnknownButton(s.to_owned())),
        }
    }
}

/// Snapshot of Pokemon Red game state read from memory.
#[derive(Debug, Clone, Default, serde::Serialize)]
#[allow(dead_code)]
pub struct GameState {
    /// Map ID byte from the emulator memory.
    pub map_id: u8,
    /// Human-readable map name resolved from `map_id`.
    pub map_name: String,
    /// Player X coordinate on the current map.
    pub player_x: u8,
    /// Player Y coordinate on the current map.
    pub player_y: u8,
    /// Whether the player is currently in a battle.
    pub in_battle: bool,
    /// Current party members.
    pub party: Vec<PartyMember>,
    /// Earned badge names.
    pub badges: Vec<String>,
    /// Total number of badges earned.
    pub badge_count: u8,
    /// Current money amount.
    pub money: u32,
    /// Items currently in the bag.
    pub bag: Vec<BagItem>,
    /// Current special UI overlay, if any.
    pub special_ui: SpecialUiState,
}

/// A Pokemon in the player's party.
#[derive(Debug, Clone, serde::Serialize)]
#[allow(dead_code)]
pub struct PartyMember {
    /// Species name (e.g. "PIKACHU").
    pub species: String,
    /// Current level.
    pub level: u8,
    /// Current HP.
    pub hp: u16,
    /// Maximum HP.
    pub max_hp: u16,
    /// Status condition (e.g. "PSN", "SLP", or empty).
    pub status: String,
}

/// An item in the player's bag.
#[derive(Debug, Clone, serde::Serialize)]
#[allow(dead_code)]
pub struct BagItem {
    /// Item name (e.g. "POTION").
    pub name: String,
    /// Quantity held.
    pub count: u8,
}

/// Special UI states that require different agent behavior.
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Serialize)]
#[allow(dead_code)]
pub enum SpecialUiState {
    /// No special UI is active.
    #[default]
    None,
    /// A text box is being displayed.
    TextBox,
    /// The battle action menu is open.
    BattleMenu,
    /// The item selection menu is open.
    ItemMenu,
    /// The party selection menu is open.
    PartyMenu,
    /// The name input screen is active.
    NameInput,
    /// The move swap prompt is active.
    MoveSwap,
    /// A shop buy/sell interface is open.
    Shopping,
    /// A yes/no confirmation dialog is active.
    YesNo,
    /// The PC storage system is open.
    PcStorage,
}

/// A single journal entry for session persistence.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[allow(dead_code)]
pub struct JournalEntry {
    /// ISO-8601 timestamp when the session started.
    pub started: String,
    /// ISO-8601 timestamp when the session ended.
    pub ended: String,
    /// LLM-generated summary of what happened during this session.
    pub summary: String,
    /// Snapshot of the party at the end of the session.
    pub party_snapshot: Vec<PartySnapshot>,
    /// Number of badges held at the end of the session.
    pub badges: u8,
    /// Total play time for this session in seconds.
    pub play_time_seconds: u64,
}

/// Lightweight party snapshot for journal entries.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[allow(dead_code)]
pub struct PartySnapshot {
    /// Species name.
    pub species: String,
    /// Pokemon level.
    pub level: u8,
}

/// Persistent game journal across sessions.
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
#[allow(dead_code)]
pub struct Journal {
    /// All recorded session entries.
    pub sessions: Vec<JournalEntry>,
    /// The current high-level goal the agent is pursuing.
    pub current_goal: String,
    /// Lessons the agent has recorded for future decision-making.
    pub learnings: Vec<String>,
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn gb_button_from_str_valid() {
        assert_eq!(GbButton::try_from("A").unwrap(), GbButton::A);
        assert_eq!(GbButton::try_from("b").unwrap(), GbButton::B);
        assert_eq!(GbButton::try_from("Start").unwrap(), GbButton::Start);
        assert_eq!(GbButton::try_from("SELECT").unwrap(), GbButton::Select);
        assert_eq!(GbButton::try_from("up").unwrap(), GbButton::Up);
        assert_eq!(GbButton::try_from("Down").unwrap(), GbButton::Down);
        assert_eq!(GbButton::try_from("LEFT").unwrap(), GbButton::Left);
        assert_eq!(GbButton::try_from("right").unwrap(), GbButton::Right);
    }

    #[test]
    fn gb_button_from_str_invalid() {
        let err = GbButton::try_from("X").unwrap_err();
        assert_eq!(err.0, "X");
        assert!(err.to_string().contains("unknown Game Boy button"));
    }

    #[test]
    fn game_state_default() {
        let state = GameState::default();
        assert_eq!(state.map_id, 0);
        assert!(!state.in_battle);
        assert!(state.party.is_empty());
        assert!(state.badges.is_empty());
    }

    #[test]
    fn journal_roundtrip_serde() {
        let journal = Journal {
            sessions: vec![JournalEntry {
                started: "2026-03-14T10:00:00Z".to_owned(),
                ended: "2026-03-14T11:00:00Z".to_owned(),
                summary: "Beat Brock".to_owned(),
                party_snapshot: vec![PartySnapshot {
                    species: "CHARMANDER".to_owned(),
                    level: 14,
                }],
                badges: 1,
                play_time_seconds: 3600,
            }],
            current_goal: "Reach Mt. Moon".to_owned(),
            learnings: vec!["Brock is weak to water".to_owned()],
        };

        let json = serde_json::to_string(&journal).unwrap();
        let restored: Journal = serde_json::from_str(&json).unwrap();

        assert_eq!(restored.sessions.len(), 1);
        assert_eq!(restored.sessions[0].summary, "Beat Brock");
        assert_eq!(restored.current_goal, "Reach Mt. Moon");
    }
}
