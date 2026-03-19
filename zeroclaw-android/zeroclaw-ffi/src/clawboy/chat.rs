// Copyright (c) 2026 @Natfii. All rights reserved.

//! Chat integration for ClawBoy -- commentary routing and user commands.
//!
//! Routes agent commentary to the originating chat channel with a
//! hard 30-second minimum between messages. Parses user commands
//! during gameplay (stop, directives, game triggers).

use std::time::{Duration, Instant};

/// Minimum time between commentary messages.
const MIN_COMMENTARY_INTERVAL: Duration = Duration::from_secs(30);

/// Returns true if the message matches a start-game trigger pattern.
/// Delegates to the engine crate's compiled regex.
#[allow(dead_code)]
pub(crate) fn is_game_trigger(message: &str) -> bool {
    zeroclaw::clawboy_triggers::is_game_trigger(message)
}

/// Returns true if the message matches a stop-game trigger AND was
/// sent from the originating channel.
/// Stop triggers are scoped: only the channel that started the game
/// can stop it via chat.
#[allow(dead_code)]
pub(crate) fn is_stop_trigger(message: &str, sender_channel: Option<&str>) -> bool {
    if !zeroclaw::clawboy_triggers::is_stop_trigger(message) {
        return false;
    }
    if let Some(sender) = sender_channel
        && let Some(orig) = super::session::originating_channel_id()
    {
        return sender == orig;
    }
    true
}

/// Keywords that suggest a game-related directive for the agent.
///
/// If any of these appear as a substring (case-insensitive) in a user
/// message that is not a stop command, the message is classified as a
/// [`UserCommand::Directive`].
#[allow(dead_code)]
pub(crate) const DIRECTIVE_KEYWORDS: &[&str] = &[
    "catch",
    "go to",
    "grind",
    "level up",
    "fight",
    "battle",
    "heal",
    "buy",
    "sell",
    "use",
    "teach",
    "learn",
    "evolve",
    "switch",
    "what's your team",
    "take a screenshot",
    "screenshot",
    "where are you",
    "status",
];

// ── Commentary Router ────────────────────────────────────────────────

/// Routes ClawBoy commentary to the originating chat channel.
///
/// Enforces a hard 30-second minimum between messages to prevent
/// spamming. Messages that arrive too soon are silently dropped.
/// Status messages (game started, stopped, crash) bypass the cap.
///
/// In v1, actual channel dispatch is deferred -- commentary is logged
/// via `tracing::info!` with structured fields and stored in a pending
/// buffer retrievable via [`CommentaryRouter::drain_pending`].
#[allow(dead_code)]
pub struct CommentaryRouter {
    /// The channel ID where the game was started from.
    channel_id: Option<String>,
    /// Timestamp of last sent message.
    last_sent: Option<Instant>,
    /// Pending commentary that passed the frequency check.
    pending: Vec<String>,
}

#[allow(dead_code)]
impl CommentaryRouter {
    /// Creates a new router for the given channel.
    pub fn new(channel_id: Option<String>) -> Self {
        Self {
            channel_id,
            last_sent: None,
            pending: Vec::new(),
        }
    }

    /// Queues commentary if the frequency cap allows.
    ///
    /// Returns `true` if the message was accepted, `false` if dropped
    /// due to the 30-second interval.
    pub fn queue_commentary(&mut self, text: String) -> bool {
        if let Some(last) = self.last_sent
            && last.elapsed() < MIN_COMMENTARY_INTERVAL
        {
            tracing::debug!(
                target: "clawboy::chat",
                elapsed_secs = last.elapsed().as_secs(),
                "commentary dropped (within 30s window)"
            );
            return false;
        }
        self.last_sent = Some(Instant::now());

        // Log the commentary (channel dispatch will be wired in a future PR).
        tracing::info!(
            target: "clawboy::chat",
            channel = ?self.channel_id,
            %text,
            "commentary"
        );

        self.pending.push(text);
        true
    }

    /// Sends a status message bypassing the frequency cap.
    ///
    /// Used for critical messages: game started, game stopped, crash.
    pub fn send_status(&mut self, text: String) {
        tracing::info!(
            target: "clawboy::chat",
            channel = ?self.channel_id,
            status = true,
            %text,
            "status"
        );
        self.pending.push(text);
        self.last_sent = Some(Instant::now());
    }

    /// Takes all pending messages, leaving the buffer empty.
    pub fn drain_pending(&mut self) -> Vec<String> {
        std::mem::take(&mut self.pending)
    }

    /// Returns the originating channel ID.
    pub fn channel_id(&self) -> Option<&str> {
        self.channel_id.as_deref()
    }
}

// ── User command parsing ─────────────────────────────────────────────

/// Result of parsing a user message during gameplay.
#[derive(Debug, PartialEq, Eq)]
#[allow(dead_code)]
pub(crate) enum UserCommand {
    /// User wants to stop the game.
    Stop,
    /// User gave a directive for the agent (e.g. "catch a Pikachu").
    Directive(String),
    /// Normal conversation -- not a game command.
    Chat(String),
}

/// Parses a user message during an active ClawBoy session.
///
/// Checks for stop triggers first (via the engine regex, anchored to
/// the full trimmed message). If any directive keyword appears as a
/// substring, the message is classified as a [`UserCommand::Directive`].
/// Everything else is treated as normal chat.
#[allow(dead_code)]
pub(crate) fn parse_user_command(message: &str) -> UserCommand {
    let trimmed = message.trim();
    if zeroclaw::clawboy_triggers::is_stop_trigger(trimmed) {
        return UserCommand::Stop;
    }
    let lower = trimmed.to_lowercase();
    for &keyword in DIRECTIVE_KEYWORDS {
        if lower.contains(keyword) {
            return UserCommand::Directive(trimmed.to_owned());
        }
    }
    UserCommand::Chat(trimmed.to_owned())
}

// ── Trigger interceptor ──────────────────────────────────────────────

/// Result of a trigger check.
#[derive(Debug, PartialEq, Eq)]
#[allow(dead_code)]
pub(crate) enum TriggerResult {
    /// Message matched a start trigger.
    StartResponse(String),
    /// Message matched a stop trigger -- session stopped.
    StopResponse(String),
    /// No trigger matched -- forward to normal agent processing.
    PassThrough,
}

/// Handles a message while a ClawBoy session is active.
#[allow(dead_code)]
fn handle_active_trigger(
    message: &str,
    channel_id: &str,
    status: &super::types::ClawBoyStatus,
) -> TriggerResult {
    if is_stop_trigger(message, Some(channel_id)) {
        let play_time = match status {
            super::types::ClawBoyStatus::Playing {
                play_time_seconds, ..
            } => *play_time_seconds,
            _ => 0,
        };
        let Ok(handle) = crate::runtime::get_or_create_runtime() else {
            return TriggerResult::PassThrough;
        };
        if let Err(e) = handle.block_on(super::session::stop_session()) {
            tracing::warn!(target: "clawboy::chat", error = %e, "stop_session failed in trigger");
        }
        let minutes = play_time / 60;
        let seconds = play_time % 60;
        return TriggerResult::StopResponse(format!(
            "Saved and stopped! {minutes}m {seconds}s played this session."
        ));
    }

    if is_game_trigger(message) {
        return match status {
            super::types::ClawBoyStatus::Playing { viewer_url, .. } => {
                TriggerResult::StartResponse(format!("Already playing! Watch here: {viewer_url}"))
            }
            super::types::ClawBoyStatus::Paused { .. } => TriggerResult::StartResponse(
                "Game is paused (battery saver). It'll resume when battery saver is off."
                    .to_owned(),
            ),
            _ => TriggerResult::PassThrough,
        };
    }

    let cmd = parse_user_command(message);
    match cmd {
        UserCommand::Stop => TriggerResult::PassThrough,
        UserCommand::Directive(d) => {
            super::session::push_user_message(format!("[User coaching]: {d}"));
            let (play_time, viewer_url) = match status {
                super::types::ClawBoyStatus::Playing {
                    play_time_seconds,
                    viewer_url,
                } => (*play_time_seconds, viewer_url.as_str()),
                _ => (0, "unknown"),
            };
            TriggerResult::StartResponse(format!(
                "Got it! I'll work on that. Playing for {}m. Watch: {viewer_url}",
                play_time / 60
            ))
        }
        UserCommand::Chat(c) => {
            super::session::push_user_message(format!("[User says]: {c}"));
            let (play_time, viewer_url) = match status {
                super::types::ClawBoyStatus::Playing {
                    play_time_seconds,
                    viewer_url,
                } => (*play_time_seconds, viewer_url.as_str()),
                _ => (0, "unknown"),
            };
            TriggerResult::StartResponse(format!(
                "I'm playing Pokemon Red right now! {}m in. Watch: {viewer_url}",
                play_time / 60
            ))
        }
    }
}

/// Checks if a message triggers a ClawBoy start or stop action.
/// Single entry point for both Terminal REPL and channel paths.
/// If ROM_PRESENT is false, returns PassThrough immediately (zero overhead).
#[allow(dead_code)]
pub(crate) fn check_trigger(message: &str, channel_id: &str) -> TriggerResult {
    use std::sync::atomic::Ordering;

    if !super::session::ROM_PRESENT.load(Ordering::Relaxed) {
        return TriggerResult::PassThrough;
    }

    let status = super::session::get_status();
    let is_active = matches!(
        status,
        super::types::ClawBoyStatus::Playing { .. } | super::types::ClawBoyStatus::Paused { .. }
    );

    if is_active {
        return handle_active_trigger(message, channel_id, &status);
    }

    if !is_game_trigger(message) {
        return TriggerResult::PassThrough;
    }

    let Ok(handle) = crate::runtime::get_or_create_runtime() else {
        return TriggerResult::PassThrough;
    };

    let Some(data_dir) = super::session::ROM_DATA_DIR.get().cloned() else {
        tracing::warn!(target: "clawboy::chat", "ROM_PRESENT is true but ROM_DATA_DIR is not set");
        return TriggerResult::PassThrough;
    };

    let rom_path = data_dir
        .join("clawboy/pokemon-red/rom.gb")
        .to_string_lossy()
        .to_string();
    let interval_ms: u64 = 1500;

    match handle.block_on(super::session::start_session(
        rom_path,
        interval_ms,
        &data_dir,
        Some(channel_id.to_owned()),
    )) {
        Ok(info) => TriggerResult::StartResponse(format!(
            "Starting Pokemon Red! Watch here: {}",
            info.viewer_url
        )),
        Err(e) if e.contains("already running") => {
            let url = match super::session::get_status() {
                super::types::ClawBoyStatus::Playing { viewer_url, .. } => viewer_url,
                _ => "unknown".to_owned(),
            };
            TriggerResult::StartResponse(format!("Already playing! Watch here: {url}"))
        }
        Err(e) => {
            tracing::error!(target: "clawboy::chat", error = %e, "failed to start ClawBoy from trigger");
            TriggerResult::StartResponse(format!("Couldn't start ClawBoy: {e}"))
        }
    }
}

// ── Tests ────────────────────────────────────────────────────────────

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    // ── CommentaryRouter tests ───────────────────────────────────────

    #[test]
    fn router_accepts_first_message() {
        let mut router = CommentaryRouter::new(Some("discord:123".to_owned()));
        assert!(router.queue_commentary("Hello chat!".to_owned()));
        assert_eq!(router.pending.len(), 1);
    }

    #[test]
    fn router_drops_message_within_interval() {
        let mut router = CommentaryRouter::new(None);
        assert!(router.queue_commentary("First".to_owned()));

        // Second message immediately after -- should be dropped.
        assert!(!router.queue_commentary("Second".to_owned()));
        assert_eq!(router.pending.len(), 1);
        assert_eq!(router.pending[0], "First");
    }

    #[test]
    fn router_accepts_after_interval_elapsed() {
        let mut router = CommentaryRouter::new(None);
        assert!(router.queue_commentary("First".to_owned()));

        // Manually backdate last_sent to simulate time passing.
        router.last_sent = Some(Instant::now().checked_sub(Duration::from_secs(31)).unwrap());

        assert!(router.queue_commentary("Second".to_owned()));
        assert_eq!(router.pending.len(), 2);
    }

    #[test]
    fn router_status_bypasses_frequency_cap() {
        let mut router = CommentaryRouter::new(None);
        router.queue_commentary("First".to_owned());

        // Status message should always go through.
        router.send_status("Game started!".to_owned());
        assert_eq!(router.pending.len(), 2);
        assert_eq!(router.pending[1], "Game started!");
    }

    #[test]
    fn router_status_updates_last_sent() {
        let mut router = CommentaryRouter::new(None);
        router.send_status("Started".to_owned());

        // A commentary immediately after a status should be dropped.
        assert!(!router.queue_commentary("Hello".to_owned()));
    }

    #[test]
    fn router_drain_pending_clears_buffer() {
        let mut router = CommentaryRouter::new(None);
        router.queue_commentary("One".to_owned());
        router.send_status("Two".to_owned());

        let drained = router.drain_pending();
        assert_eq!(drained.len(), 2);
        assert_eq!(drained[0], "One");
        assert_eq!(drained[1], "Two");

        // Buffer should now be empty.
        assert!(router.drain_pending().is_empty());
    }

    #[test]
    fn router_channel_id_accessor() {
        let router = CommentaryRouter::new(Some("telegram:456".to_owned()));
        assert_eq!(router.channel_id(), Some("telegram:456"));

        let router_none = CommentaryRouter::new(None);
        assert_eq!(router_none.channel_id(), None);
    }

    // ── parse_user_command tests ─────────────────────────────────────

    #[test]
    fn stop_keyword_exact_match() {
        assert_eq!(parse_user_command("stop"), UserCommand::Stop);
        assert_eq!(parse_user_command("quit"), UserCommand::Stop);
        assert_eq!(parse_user_command("save and quit"), UserCommand::Stop);
        assert_eq!(parse_user_command("end game"), UserCommand::Stop);
    }

    #[test]
    fn stop_keyword_case_insensitive() {
        assert_eq!(parse_user_command("STOP"), UserCommand::Stop);
        assert_eq!(parse_user_command("Stop Playing"), UserCommand::Stop);
        assert_eq!(parse_user_command("QUIT THE GAME"), UserCommand::Stop);
    }

    #[test]
    fn stop_keyword_with_whitespace() {
        assert_eq!(parse_user_command("  stop  "), UserCommand::Stop);
        assert_eq!(parse_user_command("\tstop\n"), UserCommand::Stop);
    }

    #[test]
    fn stop_keyword_substring_does_not_match() {
        // "stop" must be an exact match, not a substring.
        let cmd = parse_user_command("don't stop me now");
        assert!(
            !matches!(cmd, UserCommand::Stop),
            "substring 'stop' should not trigger Stop"
        );
    }

    #[test]
    fn directive_keyword_detected() {
        assert_eq!(
            parse_user_command("catch a Pikachu"),
            UserCommand::Directive("catch a Pikachu".to_owned())
        );
        assert_eq!(
            parse_user_command("go to Pewter City"),
            UserCommand::Directive("go to Pewter City".to_owned())
        );
        assert_eq!(
            parse_user_command("take a screenshot please"),
            UserCommand::Directive("take a screenshot please".to_owned())
        );
    }

    #[test]
    fn directive_preserves_original_casing() {
        let cmd = parse_user_command("CATCH a Pikachu!");
        assert_eq!(cmd, UserCommand::Directive("CATCH a Pikachu!".to_owned()));
    }

    #[test]
    fn chat_when_no_keywords() {
        assert_eq!(
            parse_user_command("hello there"),
            UserCommand::Chat("hello there".to_owned())
        );
        assert_eq!(
            parse_user_command("what do you think of the game?"),
            UserCommand::Chat("what do you think of the game?".to_owned())
        );
    }

    #[test]
    fn chat_trims_whitespace() {
        assert_eq!(
            parse_user_command("  hello  "),
            UserCommand::Chat("hello".to_owned())
        );
    }

    // ── is_game_trigger tests (delegates to engine regex) ─────────────

    #[test]
    fn game_trigger_detected() {
        assert!(is_game_trigger("play a game"));
        assert!(is_game_trigger("hey, play pokemon!"));
        assert!(is_game_trigger("can you start clawboy?"));
        assert!(is_game_trigger("start the game"));
    }

    #[test]
    fn game_trigger_case_insensitive() {
        assert!(is_game_trigger("PLAY A GAME"));
        assert!(is_game_trigger("Play Pokemon"));
        assert!(is_game_trigger("START CLAWBOY"));
    }

    #[test]
    fn game_trigger_negative() {
        assert!(!is_game_trigger("hello"));
        assert!(!is_game_trigger("what are you doing?"));
        assert!(!is_game_trigger("how do I play?"));
    }

    #[test]
    fn game_trigger_with_whitespace() {
        assert!(is_game_trigger("  play a game  "));
    }

    // ── is_stop_trigger tests (delegates to engine regex) ───────────

    #[test]
    fn stop_trigger_exact_match() {
        assert!(is_stop_trigger("stop", Some("cli")));
        assert!(is_stop_trigger("quit", Some("cli")));
        assert!(is_stop_trigger("save and quit", Some("cli")));
        assert!(is_stop_trigger("end game", Some("cli")));
    }

    #[test]
    fn stop_trigger_case_insensitive() {
        assert!(is_stop_trigger("STOP", Some("cli")));
        assert!(is_stop_trigger("Stop Playing", Some("cli")));
        assert!(is_stop_trigger("QUIT THE GAME", Some("cli")));
    }

    #[test]
    fn stop_trigger_with_whitespace() {
        assert!(is_stop_trigger("  stop  ", Some("cli")));
        assert!(is_stop_trigger("\tstop\n", Some("cli")));
    }

    #[test]
    fn stop_trigger_substring_does_not_match() {
        assert!(!is_stop_trigger("don't stop me now", Some("cli")));
    }

    #[test]
    fn stop_trigger_allowed_when_no_session() {
        // When no session is active, there is no originating channel to
        // scope against, so is_stop_trigger returns true for any sender.
        assert!(is_stop_trigger("stop", Some("discord")));
    }

    // ── check_trigger gate tests ────────────────────────────────────

    #[test]
    fn check_trigger_passthrough_when_rom_not_present() {
        use std::sync::atomic::Ordering;
        super::super::session::ROM_PRESENT.store(false, Ordering::Relaxed);
        assert_eq!(
            check_trigger("play pokemon", "cli"),
            TriggerResult::PassThrough
        );
    }

    #[test]
    fn check_trigger_passthrough_for_non_trigger() {
        use std::sync::atomic::Ordering;
        super::super::session::ROM_PRESENT.store(true, Ordering::Relaxed);
        assert_eq!(check_trigger("hello", "cli"), TriggerResult::PassThrough);
        // Reset flag to avoid leaking state to other tests.
        super::super::session::ROM_PRESENT.store(false, Ordering::Relaxed);
    }
}
