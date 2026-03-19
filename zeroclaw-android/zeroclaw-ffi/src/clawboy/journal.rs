// Copyright (c) 2026 @Natfii. All rights reserved.

//! Journal persistence for ClawBoy game sessions.
//!
//! The journal tracks the agent's progress across sessions, storing
//! summaries, party snapshots, and goals. On session start the agent
//! reads the journal to reorient; on session end it writes a new entry.

use std::fmt::Write;
use std::path::Path;

use super::types::{GameState, Journal, JournalEntry, PartySnapshot};

// ── Constants ────────────────────────────────────────────────────────

/// Maximum number of session entries retained in the journal.
const MAX_JOURNAL_ENTRIES: usize = 20;

/// Journal filename within the ClawBoy data directory.
const JOURNAL_FILENAME: &str = "journal.json";

/// Sub-path from the data root to the journal file.
const JOURNAL_SUBDIR: &str = "clawboy/pokemon-red";

// ── Public API ──────────────────────────────────────────────────────

/// Loads the journal from disk.
///
/// Reads `data_dir/clawboy/pokemon-red/journal.json` and deserialises
/// it into a [`Journal`]. Returns [`Journal::default()`] if the file
/// does not exist or contains malformed JSON.
pub fn load_journal(data_dir: &Path) -> Journal {
    let path = data_dir.join(JOURNAL_SUBDIR).join(JOURNAL_FILENAME);

    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            tracing::debug!(
                target: "clawboy::journal",
                path = %path.display(),
                "journal file not found — starting fresh"
            );
            return Journal::default();
        }
        Err(e) => {
            tracing::warn!(
                target: "clawboy::journal",
                path = %path.display(),
                "failed to read journal file: {e}"
            );
            return Journal::default();
        }
    };

    match serde_json::from_slice::<Journal>(&bytes) {
        Ok(journal) => {
            tracing::info!(
                target: "clawboy::journal",
                sessions = journal.sessions.len(),
                learnings = journal.learnings.len(),
                "journal loaded"
            );
            journal
        }
        Err(e) => {
            tracing::warn!(
                target: "clawboy::journal",
                path = %path.display(),
                "journal JSON malformed, returning default: {e}"
            );
            Journal::default()
        }
    }
}

/// Saves the journal to disk, pruning old session entries.
///
/// Clones the journal, prunes `sessions` to [`MAX_JOURNAL_ENTRIES`]
/// (oldest entries removed first), serialises as pretty JSON, and
/// writes to `data_dir/clawboy/pokemon-red/journal.json`. Parent
/// directories are created if they do not exist.
///
/// The `learnings` array is **never** pruned -- it is session-independent
/// and grows without limit.
///
/// # Errors
///
/// Returns a descriptive error string if directory creation or file
/// writing fails.
pub fn save_journal(data_dir: &Path, journal: &Journal) -> Result<(), String> {
    let dir = data_dir.join(JOURNAL_SUBDIR);
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("failed to create journal directory: {e}"))?;

    let mut pruned = journal.clone();
    if pruned.sessions.len() > MAX_JOURNAL_ENTRIES {
        let drain_count = pruned.sessions.len() - MAX_JOURNAL_ENTRIES;
        pruned.sessions.drain(..drain_count);
    }

    let json = serde_json::to_string_pretty(&pruned)
        .map_err(|e| format!("failed to serialise journal: {e}"))?;

    let path = dir.join(JOURNAL_FILENAME);
    std::fs::write(&path, json.as_bytes())
        .map_err(|e| format!("failed to write journal file: {e}"))?;

    tracing::info!(
        target: "clawboy::journal",
        sessions = pruned.sessions.len(),
        learnings = pruned.learnings.len(),
        "journal saved"
    );

    Ok(())
}

/// Creates a [`JournalEntry`] from the current game state.
///
/// Builds a party snapshot from [`GameState::party`] and extracts the
/// badge count. The caller supplies start/end timestamps (ISO-8601),
/// a summary string, and the total play time in seconds.
pub fn create_session_entry(
    started: &str,
    ended: &str,
    summary: String,
    state: &GameState,
    play_time_seconds: u64,
) -> JournalEntry {
    let party_snapshot = state
        .party
        .iter()
        .map(|m| PartySnapshot {
            species: m.species.clone(),
            level: m.level,
        })
        .collect();

    JournalEntry {
        started: started.to_owned(),
        ended: ended.to_owned(),
        summary,
        party_snapshot,
        badges: state.badge_count,
        play_time_seconds,
    }
}

/// Formats the party for inclusion in a journal auto-summary.
///
/// Produces a string like `"CHARMANDER Lv12, PIDGEY Lv8"`.
/// Returns `"empty"` for an empty party.
pub fn format_party_for_journal(party: &[super::types::PartyMember]) -> String {
    if party.is_empty() {
        return "empty".to_owned();
    }

    let mut buf = String::new();
    for (i, member) in party.iter().enumerate() {
        if i > 0 {
            buf.push_str(", ");
        }
        let _ = write!(buf, "{} Lv{}", member.species, member.level);
    }
    buf
}

// ── Tests ───────────────────────────────────────────────────────────

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::cast_possible_truncation)]
mod tests {
    use super::*;
    use crate::clawboy::types::PartyMember;

    /// Helper: builds a journal with `n` session entries.
    fn journal_with_sessions(n: usize) -> Journal {
        let sessions = (0..n)
            .map(|i| JournalEntry {
                started: format!("2026-03-14T{i:02}:00:00Z"),
                ended: format!("2026-03-14T{i:02}:30:00Z"),
                summary: format!("Session {i}"),
                party_snapshot: vec![PartySnapshot {
                    species: "PIKACHU".to_owned(),
                    level: (i as u8) + 5,
                }],
                badges: i as u8,
                play_time_seconds: 1800,
            })
            .collect();

        Journal {
            sessions,
            current_goal: "Beat the Elite Four".to_owned(),
            learnings: vec![
                "Save before gym battles".to_owned(),
                "Buy extra Poke Balls".to_owned(),
            ],
        }
    }

    #[test]
    fn load_journal_missing_file_returns_default() {
        let tmp = tempfile::tempdir().unwrap();
        let journal = load_journal(tmp.path());
        assert!(journal.sessions.is_empty());
        assert!(journal.current_goal.is_empty());
        assert!(journal.learnings.is_empty());
    }

    #[test]
    fn load_journal_valid_json() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join(JOURNAL_SUBDIR);
        std::fs::create_dir_all(&dir).unwrap();

        let journal = journal_with_sessions(3);
        let json = serde_json::to_string_pretty(&journal).unwrap();
        std::fs::write(dir.join(JOURNAL_FILENAME), &json).unwrap();

        let loaded = load_journal(tmp.path());
        assert_eq!(loaded.sessions.len(), 3);
        assert_eq!(loaded.sessions[0].summary, "Session 0");
        assert_eq!(loaded.sessions[2].summary, "Session 2");
        assert_eq!(loaded.current_goal, "Beat the Elite Four");
        assert_eq!(loaded.learnings.len(), 2);
    }

    #[test]
    fn load_journal_malformed_json_returns_default() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join(JOURNAL_SUBDIR);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(JOURNAL_FILENAME), b"not valid json {{{").unwrap();

        let journal = load_journal(tmp.path());
        assert!(journal.sessions.is_empty());
    }

    #[test]
    fn save_journal_creates_file() {
        let tmp = tempfile::tempdir().unwrap();
        let journal = journal_with_sessions(2);

        save_journal(tmp.path(), &journal).unwrap();

        let path = tmp.path().join(JOURNAL_SUBDIR).join(JOURNAL_FILENAME);
        assert!(path.exists(), "journal file should be created");

        let contents = std::fs::read_to_string(&path).unwrap();
        let loaded: Journal = serde_json::from_str(&contents).unwrap();
        assert_eq!(loaded.sessions.len(), 2);
    }

    #[test]
    fn save_journal_prunes_old_entries_to_max() {
        let tmp = tempfile::tempdir().unwrap();
        let journal = journal_with_sessions(25);

        save_journal(tmp.path(), &journal).unwrap();

        let path = tmp.path().join(JOURNAL_SUBDIR).join(JOURNAL_FILENAME);
        let contents = std::fs::read_to_string(&path).unwrap();
        let loaded: Journal = serde_json::from_str(&contents).unwrap();

        assert_eq!(
            loaded.sessions.len(),
            MAX_JOURNAL_ENTRIES,
            "should be pruned to {MAX_JOURNAL_ENTRIES}"
        );
        // Oldest 5 sessions (0-4) should be pruned; first remaining
        // is session 5.
        assert_eq!(loaded.sessions[0].summary, "Session 5");
        assert_eq!(
            loaded.sessions[MAX_JOURNAL_ENTRIES - 1].summary,
            "Session 24"
        );
    }

    #[test]
    fn save_journal_preserves_learnings() {
        let tmp = tempfile::tempdir().unwrap();
        let mut journal = journal_with_sessions(25);
        journal.learnings.push("Never prune me".to_owned());

        save_journal(tmp.path(), &journal).unwrap();

        let path = tmp.path().join(JOURNAL_SUBDIR).join(JOURNAL_FILENAME);
        let contents = std::fs::read_to_string(&path).unwrap();
        let loaded: Journal = serde_json::from_str(&contents).unwrap();

        assert_eq!(loaded.learnings.len(), 3);
        assert!(loaded.learnings.contains(&"Never prune me".to_owned()));
    }

    #[test]
    fn create_session_entry_builds_correct_snapshot() {
        let state = GameState {
            party: vec![
                PartyMember {
                    species: "CHARMANDER".to_owned(),
                    level: 14,
                    hp: 50,
                    max_hp: 52,
                    status: "OK".to_owned(),
                },
                PartyMember {
                    species: "PIDGEY".to_owned(),
                    level: 8,
                    hp: 28,
                    max_hp: 30,
                    status: String::new(),
                },
            ],
            badge_count: 1,
            ..GameState::default()
        };

        let entry = create_session_entry(
            "2026-03-14T10:00:00Z",
            "2026-03-14T11:00:00Z",
            "Beat Brock".to_owned(),
            &state,
            3600,
        );

        assert_eq!(entry.started, "2026-03-14T10:00:00Z");
        assert_eq!(entry.ended, "2026-03-14T11:00:00Z");
        assert_eq!(entry.summary, "Beat Brock");
        assert_eq!(entry.badges, 1);
        assert_eq!(entry.play_time_seconds, 3600);
        assert_eq!(entry.party_snapshot.len(), 2);
        assert_eq!(entry.party_snapshot[0].species, "CHARMANDER");
        assert_eq!(entry.party_snapshot[0].level, 14);
        assert_eq!(entry.party_snapshot[1].species, "PIDGEY");
        assert_eq!(entry.party_snapshot[1].level, 8);
    }

    #[test]
    fn roundtrip_save_then_load() {
        let tmp = tempfile::tempdir().unwrap();
        let journal = journal_with_sessions(5);

        save_journal(tmp.path(), &journal).unwrap();
        let loaded = load_journal(tmp.path());

        assert_eq!(loaded.sessions.len(), 5);
        assert_eq!(loaded.current_goal, journal.current_goal);
        assert_eq!(loaded.learnings, journal.learnings);
        for (orig, restored) in journal.sessions.iter().zip(loaded.sessions.iter()) {
            assert_eq!(orig.summary, restored.summary);
            assert_eq!(orig.badges, restored.badges);
            assert_eq!(orig.play_time_seconds, restored.play_time_seconds);
        }
    }

    #[test]
    fn format_party_for_journal_empty() {
        assert_eq!(format_party_for_journal(&[]), "empty");
    }

    #[test]
    fn format_party_for_journal_multiple() {
        let party = vec![
            PartyMember {
                species: "CHARMANDER".to_owned(),
                level: 12,
                hp: 40,
                max_hp: 42,
                status: "OK".to_owned(),
            },
            PartyMember {
                species: "PIDGEY".to_owned(),
                level: 8,
                hp: 28,
                max_hp: 30,
                status: String::new(),
            },
        ];

        let output = format_party_for_journal(&party);
        assert_eq!(output, "CHARMANDER Lv12, PIDGEY Lv8");
    }
}
