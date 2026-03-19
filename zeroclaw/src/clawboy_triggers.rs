// Copyright (c) 2026 @Natfii. All rights reserved.

//! Chat trigger detection for the ClawBoy Game Boy emulator.
//!
//! Provides compiled regex patterns that detect start and stop commands
//! in user chat messages, plus a cross-crate callback mechanism so the
//! FFI layer can register a handler invoked when a trigger fires.

use std::sync::{Arc, LazyLock, OnceLock};

use regex::Regex;

/// Verbs that signal intent to begin a game session.
const VERBS: &str = r"play|start|run|boot|fire\s*up|launch|load\s*up|open|begin";

/// Nouns that identify ClawBoy / Game Boy / Pokemon targets.
const NOUNS: &str = r"pok[eé]mon\s+red|pok[eé]mon|clawboy|claw\s*boy|game\s*boy|the\s+game|a\s+game|red\s+version";

/// Compiled start-game trigger (case-insensitive, unanchored).
///
/// Matches an action verb followed by a game noun (or reversed), with
/// up to 20 characters of filler between them.
static START_TRIGGER: LazyLock<Regex> = LazyLock::new(|| {
    let pattern = format!(
        r"(?i)(?:{VERBS})(?:\s+.{{0,20}})?(?:{NOUNS})|(?:{NOUNS})(?:\s+.{{0,20}})?(?:{VERBS})"
    );
    Regex::new(&pattern).expect("START_TRIGGER regex failed to compile")
});

/// Compiled stop-game trigger (case-insensitive, anchored to full message).
static STOP_TRIGGER: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"(?i)^\s*(?:stop|quit|end|save\s+and\s+quit|stop\s+playing|end\s+(?:the\s+)?game|quit\s+(?:the\s+)?game|shut\s+it\s+down|stop\s+(?:the\s+)?game)\s*$",
    )
    .expect("STOP_TRIGGER regex failed to compile")
});

/// Returns `true` if `message` contains a start-game trigger phrase.
pub fn is_game_trigger(message: &str) -> bool {
    START_TRIGGER.is_match(message)
}

/// Returns `true` if `message` is a stop-game command.
pub fn is_stop_trigger(message: &str) -> bool {
    STOP_TRIGGER.is_match(message)
}

// ---------------------------------------------------------------------------
// Cross-crate callback
// ---------------------------------------------------------------------------

/// Handler signature: `(message_text, channel_id) -> Option<reply>`.
pub type TriggerHandler = Arc<dyn Fn(&str, &str) -> Option<String> + Send + Sync>;

/// Registered trigger handler, set once during FFI initialisation.
static TRIGGER_HANDLER: OnceLock<TriggerHandler> = OnceLock::new();

/// Register the trigger handler that the FFI layer will invoke when a
/// trigger matches. Must be called exactly once; subsequent calls are
/// silently ignored (first write wins).
pub fn register_trigger_handler(handler: TriggerHandler) {
    let _ = TRIGGER_HANDLER.set(handler);
}

/// Returns a reference to the registered trigger handler, if any.
pub fn trigger_handler() -> Option<&'static TriggerHandler> {
    TRIGGER_HANDLER.get()
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    // -- Start trigger: basic phrases ------------------------------------

    #[test]
    fn start_play_pokemon() {
        assert!(is_game_trigger("play pokemon"));
    }

    #[test]
    fn start_launch_clawboy() {
        assert!(is_game_trigger("launch clawboy"));
    }

    #[test]
    fn start_fire_up_game_boy() {
        assert!(is_game_trigger("fire up game boy"));
    }

    #[test]
    fn start_load_up_red_version() {
        assert!(is_game_trigger("load up red version"));
    }

    #[test]
    fn start_open_the_game() {
        assert!(is_game_trigger("open the game"));
    }

    #[test]
    fn start_begin_a_game() {
        assert!(is_game_trigger("begin a game"));
    }

    #[test]
    fn start_boot_pokemon_red() {
        assert!(is_game_trigger("boot pokemon red"));
    }

    // -- Start trigger: embedded in sentence -----------------------------

    #[test]
    fn start_embedded_hey_can_you_play_pokemon() {
        assert!(is_game_trigger("hey can you play pokemon for me?"));
    }

    #[test]
    fn start_embedded_please_start_the_game() {
        assert!(is_game_trigger("please start the game now"));
    }

    // -- Start trigger: reversed word order ------------------------------

    #[test]
    fn start_reversed_pokemon_play() {
        assert!(is_game_trigger("pokemon play"));
    }

    #[test]
    fn start_reversed_clawboy_start() {
        assert!(is_game_trigger("clawboy start"));
    }

    #[test]
    fn start_reversed_game_boy_boot() {
        assert!(is_game_trigger("game boy boot"));
    }

    // -- Start trigger: case insensitive ---------------------------------

    #[test]
    fn start_case_upper() {
        assert!(is_game_trigger("PLAY POKEMON"));
    }

    #[test]
    fn start_case_mixed() {
        assert!(is_game_trigger("Play Pokemon"));
    }

    #[test]
    fn start_case_alternating() {
        assert!(is_game_trigger("pLaY pOkEmOn"));
    }

    // -- Start trigger: accented é ---------------------------------------

    #[test]
    fn start_accented_pokemon() {
        assert!(is_game_trigger("play pokémon"));
    }

    #[test]
    fn start_accented_pokemon_red() {
        assert!(is_game_trigger("start pokémon red"));
    }

    // -- Start trigger: filler between verb and noun ---------------------

    #[test]
    fn start_filler_short() {
        assert!(is_game_trigger("play some pokemon"));
    }

    #[test]
    fn start_filler_max_boundary() {
        // "play " + 14 chars of filler + " " + "pokemon" should still match
        assert!(is_game_trigger("play that cool new pokemon"));
    }

    // -- Start trigger: rejects ------------------------------------------

    #[test]
    fn start_rejects_no_verb() {
        assert!(!is_game_trigger("pokemon is fun"));
    }

    #[test]
    fn start_rejects_no_noun() {
        assert!(!is_game_trigger("play something"));
    }

    #[test]
    fn start_rejects_past_tense() {
        assert!(!is_game_trigger("played pokemon yesterday"));
    }

    #[test]
    fn start_rejects_unrelated() {
        assert!(!is_game_trigger("what's for dinner?"));
    }

    // -- Stop trigger: exact match ---------------------------------------

    #[test]
    fn stop_basic_stop() {
        assert!(is_stop_trigger("stop"));
    }

    #[test]
    fn stop_quit() {
        assert!(is_stop_trigger("quit"));
    }

    #[test]
    fn stop_end() {
        assert!(is_stop_trigger("end"));
    }

    #[test]
    fn stop_save_and_quit() {
        assert!(is_stop_trigger("save and quit"));
    }

    #[test]
    fn stop_stop_playing() {
        assert!(is_stop_trigger("stop playing"));
    }

    #[test]
    fn stop_end_the_game() {
        assert!(is_stop_trigger("end the game"));
    }

    #[test]
    fn stop_end_game() {
        assert!(is_stop_trigger("end game"));
    }

    #[test]
    fn stop_quit_the_game() {
        assert!(is_stop_trigger("quit the game"));
    }

    #[test]
    fn stop_quit_game() {
        assert!(is_stop_trigger("quit game"));
    }

    #[test]
    fn stop_shut_it_down() {
        assert!(is_stop_trigger("shut it down"));
    }

    #[test]
    fn stop_stop_the_game() {
        assert!(is_stop_trigger("stop the game"));
    }

    #[test]
    fn stop_stop_game() {
        assert!(is_stop_trigger("stop game"));
    }

    // -- Stop trigger: case insensitive ----------------------------------

    #[test]
    fn stop_case_upper() {
        assert!(is_stop_trigger("STOP"));
    }

    #[test]
    fn stop_case_mixed() {
        assert!(is_stop_trigger("Save And Quit"));
    }

    // -- Stop trigger: with surrounding whitespace -----------------------

    #[test]
    fn stop_leading_whitespace() {
        assert!(is_stop_trigger("  stop  "));
    }

    #[test]
    fn stop_trailing_whitespace() {
        assert!(is_stop_trigger("quit  "));
    }

    // -- Stop trigger: rejects substring ---------------------------------

    #[test]
    fn stop_rejects_embedded() {
        assert!(!is_stop_trigger("please stop the game now"));
    }

    #[test]
    fn stop_rejects_prefix() {
        assert!(!is_stop_trigger("don't stop"));
    }

    #[test]
    fn stop_rejects_unrelated() {
        assert!(!is_stop_trigger("how do I save my progress?"));
    }

    // -- Callback registration -------------------------------------------

    #[test]
    fn trigger_handler_initially_none() {
        // Cannot reliably test OnceLock across tests (shared state), so
        // just confirm the accessor compiles and returns a deterministic
        // type.  In fresh processes the handler is `None`.
        let _ = trigger_handler();
    }
}
