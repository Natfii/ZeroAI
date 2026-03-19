// Copyright (c) 2026 @Natfii. All rights reserved.

//! System prompt templates for the ClawBoy agent brain.
//!
//! These templates are assembled into the system prompt and per-turn
//! messages sent to the LLM during Pokemon Red gameplay.

use std::fmt::Write;

use super::types::{GameState, Journal, SpecialUiState};

// ── Prompt constants ────────────────────────────────────────────────

/// Core game context explaining the emulator setup and Pokemon Red mechanics.
///
/// Sent once as part of the system prompt. Tells the LLM what it is
/// controlling, what inputs are available, and how game state arrives.
pub const GAME_CONTEXT_PROMPT: &str = "\
You are playing Pokemon Red on a Game Boy emulator. \
You control the game by sending button inputs: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT. \
Each turn you receive the current game state read from memory: location, party, \
battle status, items, and badges. You also receive a screenshot every few turns.\n\
\n\
IMPORTANT: Each turn takes ~10 seconds real time. Send 5-10 button presses per turn \
for reasonable play speed. For dialogue, send [\"A\",\"A\",\"A\",\"A\",\"A\"] to advance \
quickly. For walking, chain directions: [\"UP\",\"UP\",\"UP\",\"RIGHT\",\"RIGHT\"]. \
A single button per turn is too slow.\n\
\n\
Screenshots are your primary way to see what is actually on screen. Request them often, \
especially after dialogue ends, when entering new areas, in battles, or when unsure \
what is happening. Never go more than 2 turns without requesting one.\n\
\n\
UI state guide:\n\
- text_box: Dialogue or sign. Press A repeatedly to advance. Request screenshot after \
  5+ A presses to check if dialogue finished.\n\
- battle_menu: You are in a battle. FIGHT/ITEM/POKEMON/RUN. Request a screenshot to \
  see your options.\n\
- name_input: Naming screen (letter grid visible). Press START immediately to accept the \
  default name and move on. Do NOT try to type a custom name.\n\
- yes_no: A yes/no prompt. UP=Yes, DOWN=No, A=confirm.\n\
- item_menu/party_menu/shopping/pc_storage: Menu navigation. UP/DOWN to scroll, A to select, B to back.\n\
- none: Free movement. Walk around, talk to NPCs (press A facing them), enter buildings.\n\
\n\
VISUAL RECOGNITION:\n\
- If you see a grid of letters/characters on the screenshot, it is the NAMING SCREEN. \
  Press START to accept the default name. Do not mash A.\n\
- If you see a large sprite (person/pokemon) with text underneath, it is a CUTSCENE. \
  Press A to advance through it.\n\
- If your position has not changed for several turns, you may be stuck. Try pressing B \
  to cancel a menu, or try different directions. Check the screenshot carefully.\n\
\n\
Pokemon Red basics: explore the world, catch wild Pokemon with Poke Balls, \
battle trainers and gym leaders to earn 8 badges, heal at Pokemon Centers, \
buy items at Poke Marts, and build a strong team. \
Talk to NPCs for clues. Press A to interact, B to cancel or run from battles.";

/// Response format specification for the LLM.
///
/// Defines the exact JSON schema the agent must return each turn.
/// Included in the system prompt so every response is machine-parseable.
pub const DECISION_FORMAT_PROMPT: &str = "\
You must respond with ONLY a JSON object, no other text:
{
  \"inputs\": [\"A\", \"A\", \"A\", \"A\", \"A\"],
  \"thought\": \"Mashing through intro dialogue, will check screen after\",
  \"share\": false,
  \"request_screenshot\": true
}

inputs: 5-10 button presses to execute. Valid: A, B, START, SELECT, UP, DOWN, LEFT, RIGHT. \
Always send multiple presses -- single buttons are too slow.
thought: Your internal reasoning (logged but not shown to user unless share=true)
share: Set true to share a fun/interesting observation with the user watching you play
request_screenshot: Set true to see the screen next turn. Default to true. Only set false \
when you are confident about what is on screen (e.g. mid-dialogue mashing).";

/// Personality guidelines for agent commentary.
///
/// Sets the tone for messages shared with the user. Keeps the agent
/// feeling like a real player discovering the game for the first time.
pub const PERSONALITY_PROMPT: &str = "\
When sharing thoughts, be genuine and casual -- like someone experiencing Pokemon for the first time. \
Use text emoticons only (c: :D D: :( :O :3), never unicode emoji. \
Keep messages brief. Show excitement for catches, frustration for losses, curiosity for new areas.";

/// Template for injecting journal context from previous sessions.
///
/// Placeholders: `{summary}`, `{goal}`, `{party}`.
/// Filled by [`format_journal_context`] before being prepended to the
/// first turn of a new session.
pub const JOURNAL_CONTEXT_TEMPLATE: &str = "\
Previously: {summary}
Current goal: {goal}
Your party was: {party}
Continue from where you left off.";

/// Template for formatting per-turn game state.
///
/// Placeholders: `{map_name}`, `{x}`, `{y}`, `{party}`, `{badges}`,
/// `{badge_count}`, `{money}`, `{bag}`, `{battle}`, `{ui_state}`.
/// Filled by [`format_game_state`] each decision tick.
pub const STATE_TEMPLATE: &str = "\
[GAME STATE]
Location: {map_name} ({x},{y})
Party: {party}
Badges: {badges} ({badge_count}/8)
Money: ${money}
Bag: {bag}
Battle: {battle}
UI: {ui_state}
Screenshot: {screenshot_status}";

// ── Maximum bag items shown to the LLM ─────────────────────────────

/// Cap on how many bag items are included in the state to avoid
/// wasting tokens on a full 20-item inventory dump.
const MAX_BAG_DISPLAY: usize = 5;

// ── Formatting functions ────────────────────────────────────────────

/// Formats a [`GameState`] into a compact text block for the LLM.
///
/// Follows the layout defined by [`STATE_TEMPLATE`], rendering:
/// - Party as comma-separated `"SPECIES Lv## ##/##hp"` entries
/// - Badges as comma-separated names, or `"none"`
/// - Bag as the first 5 items (truncated with `"..."` if more exist)
/// - Battle as `"none"` or `"in battle"`
/// - UI as the [`SpecialUiState`] variant name
pub fn format_game_state(state: &GameState, has_screenshot: bool) -> String {
    let party = format_party_inline(&state.party);
    let badges = if state.badges.is_empty() {
        "none".to_owned()
    } else {
        state.badges.join(", ")
    };
    let bag = format_bag(&state.bag);
    let battle = if state.in_battle { "in battle" } else { "none" };
    let ui_state = format_special_ui(&state.special_ui);
    let screenshot_status = if has_screenshot {
        "attached below"
    } else {
        "not included this turn"
    };

    STATE_TEMPLATE
        .replace("{map_name}", &state.map_name)
        .replace("{x}", &state.player_x.to_string())
        .replace("{y}", &state.player_y.to_string())
        .replace("{party}", &party)
        .replace("{badges}", &badges)
        .replace("{badge_count}", &state.badge_count.to_string())
        .replace("{money}", &state.money.to_string())
        .replace("{bag}", &bag)
        .replace("{battle}", battle)
        .replace("{ui_state}", ui_state)
        .replace("{screenshot_status}", screenshot_status)
}

/// Formats a [`Journal`] into context for the start of a new session.
///
/// Uses the last session's summary and party snapshot plus the
/// journal's current goal to fill [`JOURNAL_CONTEXT_TEMPLATE`].
/// Returns an empty string if the journal has no recorded sessions.
pub fn format_journal_context(journal: &Journal) -> String {
    let Some(last) = journal.sessions.last() else {
        return String::new();
    };

    let goal = if journal.current_goal.is_empty() {
        "no specific goal set"
    } else {
        &journal.current_goal
    };

    let party = if last.party_snapshot.is_empty() {
        "empty".to_owned()
    } else {
        let mut buf = String::new();
        for (i, member) in last.party_snapshot.iter().enumerate() {
            if i > 0 {
                buf.push_str(", ");
            }
            let _ = write!(buf, "{} Lv{}", member.species, member.level);
        }
        buf
    };

    JOURNAL_CONTEXT_TEMPLATE
        .replace("{summary}", &last.summary)
        .replace("{goal}", goal)
        .replace("{party}", &party)
}

// ── Private helpers ─────────────────────────────────────────────────

/// Formats party members as a comma-separated inline string.
///
/// Each member is rendered as `"SPECIES Lv## ##/##hp"`. Status
/// conditions other than `"OK"` are appended in parentheses.
/// Returns `"empty"` for an empty party.
fn format_party_inline(party: &[super::types::PartyMember]) -> String {
    if party.is_empty() {
        return "empty".to_owned();
    }

    let mut buf = String::new();
    for (i, member) in party.iter().enumerate() {
        if i > 0 {
            buf.push_str(", ");
        }
        let _ = write!(
            buf,
            "{} Lv{} {}/{}hp",
            member.species, member.level, member.hp, member.max_hp,
        );
        if !member.status.is_empty() && member.status != "OK" {
            let _ = write!(buf, " ({})", member.status);
        }
    }
    buf
}

/// Formats bag items, capped at [`MAX_BAG_DISPLAY`] entries.
///
/// Returns `"empty"` for an empty bag, or a comma-separated list
/// like `"POTION x3, POKE_BALL x5"`. Appends `"..."` if truncated.
fn format_bag(bag: &[super::types::BagItem]) -> String {
    if bag.is_empty() {
        return "empty".to_owned();
    }

    let mut buf = String::new();
    let display_count = bag.len().min(MAX_BAG_DISPLAY);
    for (i, item) in bag.iter().take(display_count).enumerate() {
        if i > 0 {
            buf.push_str(", ");
        }
        let _ = write!(buf, "{} x{}", item.name, item.count);
    }
    if bag.len() > MAX_BAG_DISPLAY {
        buf.push_str(", ...");
    }
    buf
}

/// Maps a [`SpecialUiState`] variant to a short display name.
fn format_special_ui(ui: &SpecialUiState) -> &'static str {
    match ui {
        SpecialUiState::None => "none",
        SpecialUiState::TextBox => "text_box",
        SpecialUiState::BattleMenu => "battle_menu",
        SpecialUiState::ItemMenu => "item_menu",
        SpecialUiState::PartyMenu => "party_menu",
        SpecialUiState::NameInput => "name_input",
        SpecialUiState::MoveSwap => "move_swap",
        SpecialUiState::Shopping => "shopping",
        SpecialUiState::YesNo => "yes_no",
        SpecialUiState::PcStorage => "pc_storage",
    }
}

// ── Tests ───────────────────────────────────────────────────────────

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::clawboy::types::{BagItem, JournalEntry, PartyMember, PartySnapshot};

    #[test]
    fn format_game_state_empty_party() {
        let state = GameState::default();
        let output = format_game_state(&state, false);
        assert!(output.contains("Party: empty"));
        assert!(output.contains("Badges: none (0/8)"));
        assert!(output.contains("Bag: empty"));
        assert!(output.contains("Battle: none"));
        assert!(output.contains("UI: none"));
        assert!(output.contains("Screenshot: not included this turn"));
    }

    #[test]
    fn format_game_state_with_data() {
        let state = GameState {
            map_id: 2,
            map_name: "PEWTER_CITY".to_owned(),
            player_x: 10,
            player_y: 5,
            in_battle: false,
            party: vec![
                PartyMember {
                    species: "CHARMANDER".to_owned(),
                    level: 12,
                    hp: 42,
                    max_hp: 52,
                    status: "OK".to_owned(),
                },
                PartyMember {
                    species: "PIDGEY".to_owned(),
                    level: 8,
                    hp: 28,
                    max_hp: 30,
                    status: "PSN".to_owned(),
                },
            ],
            badges: vec!["BOULDER".to_owned()],
            badge_count: 1,
            money: 3456,
            bag: vec![
                BagItem {
                    name: "POTION".to_owned(),
                    count: 3,
                },
                BagItem {
                    name: "POKE_BALL".to_owned(),
                    count: 5,
                },
            ],
            special_ui: SpecialUiState::None,
        };

        let output = format_game_state(&state, true);
        assert!(output.contains("Location: PEWTER_CITY (10,5)"));
        assert!(output.contains("Screenshot: attached below"));
        assert!(output.contains("Party: CHARMANDER Lv12 42/52hp, PIDGEY Lv8 28/30hp (PSN)"));
        assert!(output.contains("Badges: BOULDER (1/8)"));
        assert!(output.contains("Money: $3456"));
        assert!(output.contains("Bag: POTION x3, POKE_BALL x5"));
        assert!(output.contains("Battle: none"));
    }

    #[test]
    fn format_game_state_in_battle() {
        let state = GameState {
            in_battle: true,
            special_ui: SpecialUiState::BattleMenu,
            ..GameState::default()
        };
        let output = format_game_state(&state, false);
        assert!(output.contains("Battle: in battle"));
        assert!(output.contains("UI: battle_menu"));
    }

    #[test]
    fn format_bag_truncation() {
        let items: Vec<BagItem> = (0..8)
            .map(|i| BagItem {
                name: format!("ITEM_{i}"),
                count: i + 1,
            })
            .collect();
        let output = format_bag(&items);
        assert!(output.contains("ITEM_0 x1"));
        assert!(output.contains("ITEM_4 x5"));
        assert!(output.ends_with(", ..."));
        assert!(!output.contains("ITEM_5"));
    }

    #[test]
    fn format_journal_context_empty_journal() {
        let journal = Journal::default();
        let output = format_journal_context(&journal);
        assert!(output.is_empty());
    }

    #[test]
    fn format_journal_context_with_session() {
        let journal = Journal {
            sessions: vec![JournalEntry {
                started: "2026-03-14T10:00:00Z".to_owned(),
                ended: "2026-03-14T11:00:00Z".to_owned(),
                summary: "Beat Brock with Charmander".to_owned(),
                party_snapshot: vec![
                    PartySnapshot {
                        species: "CHARMANDER".to_owned(),
                        level: 14,
                    },
                    PartySnapshot {
                        species: "PIDGEY".to_owned(),
                        level: 9,
                    },
                ],
                badges: 1,
                play_time_seconds: 3600,
            }],
            current_goal: "Reach Mt. Moon".to_owned(),
            learnings: vec![],
        };

        let output = format_journal_context(&journal);
        assert!(output.contains("Previously: Beat Brock with Charmander"));
        assert!(output.contains("Current goal: Reach Mt. Moon"));
        assert!(output.contains("Your party was: CHARMANDER Lv14, PIDGEY Lv9"));
        assert!(output.contains("Continue from where you left off."));
    }

    #[test]
    fn format_journal_context_empty_goal() {
        let journal = Journal {
            sessions: vec![JournalEntry {
                started: String::new(),
                ended: String::new(),
                summary: "Started the game".to_owned(),
                party_snapshot: vec![],
                badges: 0,
                play_time_seconds: 600,
            }],
            current_goal: String::new(),
            learnings: vec![],
        };

        let output = format_journal_context(&journal);
        assert!(output.contains("Current goal: no specific goal set"));
        assert!(output.contains("Your party was: empty"));
    }

    #[test]
    fn format_special_ui_variants() {
        assert_eq!(format_special_ui(&SpecialUiState::None), "none");
        assert_eq!(format_special_ui(&SpecialUiState::TextBox), "text_box");
        assert_eq!(
            format_special_ui(&SpecialUiState::BattleMenu),
            "battle_menu"
        );
        assert_eq!(format_special_ui(&SpecialUiState::ItemMenu), "item_menu");
        assert_eq!(format_special_ui(&SpecialUiState::PartyMenu), "party_menu");
        assert_eq!(format_special_ui(&SpecialUiState::NameInput), "name_input");
        assert_eq!(format_special_ui(&SpecialUiState::MoveSwap), "move_swap");
        assert_eq!(format_special_ui(&SpecialUiState::Shopping), "shopping");
        assert_eq!(format_special_ui(&SpecialUiState::YesNo), "yes_no");
        assert_eq!(format_special_ui(&SpecialUiState::PcStorage), "pc_storage");
    }

    #[test]
    fn const_prompts_are_nonempty() {
        assert!(!GAME_CONTEXT_PROMPT.is_empty());
        assert!(!DECISION_FORMAT_PROMPT.is_empty());
        assert!(!PERSONALITY_PROMPT.is_empty());
        assert!(!JOURNAL_CONTEXT_TEMPLATE.is_empty());
        assert!(!STATE_TEMPLATE.is_empty());
    }

    #[test]
    fn state_template_has_all_placeholders() {
        assert!(STATE_TEMPLATE.contains("{map_name}"));
        assert!(STATE_TEMPLATE.contains("{x}"));
        assert!(STATE_TEMPLATE.contains("{y}"));
        assert!(STATE_TEMPLATE.contains("{party}"));
        assert!(STATE_TEMPLATE.contains("{badges}"));
        assert!(STATE_TEMPLATE.contains("{badge_count}"));
        assert!(STATE_TEMPLATE.contains("{money}"));
        assert!(STATE_TEMPLATE.contains("{bag}"));
        assert!(STATE_TEMPLATE.contains("{battle}"));
        assert!(STATE_TEMPLATE.contains("{ui_state}"));
        assert!(STATE_TEMPLATE.contains("{screenshot_status}"));
    }

    #[test]
    fn journal_template_has_all_placeholders() {
        assert!(JOURNAL_CONTEXT_TEMPLATE.contains("{summary}"));
        assert!(JOURNAL_CONTEXT_TEMPLATE.contains("{goal}"));
        assert!(JOURNAL_CONTEXT_TEMPLATE.contains("{party}"));
    }
}
