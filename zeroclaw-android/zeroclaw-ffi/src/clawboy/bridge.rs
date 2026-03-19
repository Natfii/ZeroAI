// Copyright (c) 2026 @Natfii. All rights reserved.

//! Agent Bridge -- connects the Game Boy emulator to the LLM for AI gameplay.
//!
//! Every decision tick, the bridge reads game state from memory, compares
//! to the previous snapshot, and decides whether to query the LLM. When
//! it does, it formats the state into a prompt, sends it to the provider,
//! parses the JSON response, and queues button inputs for the emulator.

use std::collections::VecDeque;

use base64::Engine;
use zeroclaw::config::Config;
use zeroclaw::providers::{ChatMessage, ChatRequest};

use super::emulator::Emulator;
use super::memory_map::read_game_state;
use super::prompts::{
    DECISION_FORMAT_PROMPT, GAME_CONTEXT_PROMPT, PERSONALITY_PROMPT, format_game_state,
};
use super::types::{GameState, GbButton};

// ── Constants ───────────────────────────────────────────────────────

/// Maximum conversation history entries (system + turn pairs + buffer).
///
/// Keeps memory usage bounded and avoids token-bloated requests.
/// When exceeded, the oldest non-system messages are pruned.
const MAX_HISTORY_LEN: usize = 42;

/// How many recent messages to retain after pruning.
const HISTORY_RETAIN: usize = 40;

/// HP change threshold (as a fraction denominator) for triggering a
/// decision. A change of more than 1/10th of max HP is "meaningful".
const HP_CHANGE_THRESHOLD: u32 = 10;

/// Maximum turns without a screenshot before forcing one.
///
/// Prevents the LLM from flying blind by overriding `request_screenshot`
/// to `true` when this many consecutive decisions have skipped screenshots.
const MAX_TURNS_WITHOUT_SCREENSHOT: u64 = 3;

/// Position delta (in tiles) that triggers a new decision.
const POSITION_DELTA_THRESHOLD: u16 = 3;

/// Default model if none is configured.
const FALLBACK_MODEL: &str = "claude-sonnet-4-20250514";

/// Temperature for game decisions (creative but focused).
const DECISION_TEMPERATURE: f64 = 0.7;

// ── Agent Bridge ────────────────────────────────────────────────────

/// Bridges the emulator to the LLM for AI-driven gameplay.
///
/// Maintains its own conversation history (separate from the daemon's
/// singleton session), tracks game state deltas, and queues button
/// inputs parsed from LLM responses.
#[allow(dead_code)]
pub struct AgentBridge {
    /// Previous game state for delta comparison.
    previous_state: Option<GameState>,
    /// Queued button inputs to feed to the emulator.
    input_queue: VecDeque<GbButton>,
    /// Current decision interval in frames.
    decision_interval_frames: u64,
    /// Frame counter since last decision.
    frames_since_decision: u64,
    /// Whether to include a screenshot on next decision.
    request_screenshot: bool,
    /// Conversation history for this game session.
    history: Vec<ChatMessage>,
    /// System prompt assembled from templates.
    system_prompt: String,
    /// Commentary messages to be sent to chat.
    commentary_queue: VecDeque<String>,
    /// Last direction held when repeating input.
    last_input: Option<GbButton>,
    /// Consecutive decisions without a screenshot.
    turns_without_screenshot: u64,
    /// Consecutive decisions where position has not changed.
    stuck_turns: u64,
    /// Last seen player position for stuckness detection.
    last_position: Option<(u8, u8)>,
}

#[allow(dead_code)]
impl AgentBridge {
    /// Creates a new agent bridge with the given decision interval and
    /// journal context from previous play sessions.
    ///
    /// Assembles the system prompt from game context, decision format,
    /// personality, and journal context templates, then seeds the
    /// conversation history with the system message.
    ///
    /// # Arguments
    ///
    /// * `decision_interval_ms` - How often (in milliseconds) the agent
    ///   should make a decision. Converted to frames at 60 fps.
    /// * `journal_context` - Formatted summary of prior sessions, or
    ///   empty string if this is the first session.
    pub fn new(decision_interval_ms: u64, journal_context: String) -> Self {
        let decision_interval_frames = decision_interval_ms * 60 / 1000;

        let mut system_prompt = String::with_capacity(1024);
        system_prompt.push_str(GAME_CONTEXT_PROMPT);
        system_prompt.push_str("\n\n");
        system_prompt.push_str(DECISION_FORMAT_PROMPT);
        system_prompt.push_str("\n\n");
        system_prompt.push_str(PERSONALITY_PROMPT);

        if !journal_context.is_empty() {
            system_prompt.push_str("\n\n");
            system_prompt.push_str(&journal_context);
        }

        let history = vec![ChatMessage::system(&system_prompt)];

        Self {
            previous_state: None,
            input_queue: VecDeque::new(),
            decision_interval_frames,
            frames_since_decision: 0,
            request_screenshot: true,
            history,
            system_prompt,
            commentary_queue: VecDeque::new(),
            last_input: None,
            turns_without_screenshot: 0,
            stuck_turns: 0,
            last_position: None,
        }
    }

    /// Advances the bridge by one frame and returns the next button to
    /// press, if any.
    ///
    /// If the input queue has pending buttons, pops and returns the next
    /// one. Otherwise increments the frame counter and returns the last
    /// held direction for smooth movement, or `None` if idle.
    pub fn tick(&mut self, _emulator: &mut Emulator, _frame_count: u64) -> Option<GbButton> {
        if let Some(button) = self.input_queue.pop_front() {
            self.last_input = Some(button);
            return Some(button);
        }

        self.frames_since_decision += 1;

        // Repeat last directional input for smooth movement.
        self.last_input
    }

    /// Returns `true` if enough frames have elapsed since the last
    /// decision and the input queue is empty.
    ///
    /// The session tick loop should call [`decide`](Self::decide) when
    /// this returns `true`.
    pub fn needs_decision(&self) -> bool {
        self.frames_since_decision >= self.decision_interval_frames && self.input_queue.is_empty()
    }

    /// Reads game state, optionally queries the LLM, and queues inputs.
    ///
    /// Reads the current game state from emulator memory, compares to
    /// the previous snapshot via [`state_changed_meaningfully`], and
    /// either repeats the last input or calls the LLM for a fresh
    /// decision. On a successful LLM response, parses the JSON into
    /// button inputs and queues them.
    ///
    /// # Errors
    ///
    /// Returns a descriptive error string if the LLM call fails or the
    /// response cannot be parsed.
    pub async fn decide(&mut self, emulator: &mut Emulator, config: &Config) -> Result<(), String> {
        let state = read_game_state(|addr| emulator.read_memory(addr));

        // Check if state changed meaningfully.
        let meaningful = match &self.previous_state {
            Some(old) => state_changed_meaningfully(old, &state),
            None => true,
        };

        if !meaningful && !self.input_queue.is_empty() {
            // Nothing changed and we still have queued inputs -- skip.
            self.frames_since_decision = 0;
            self.previous_state = Some(state);
            return Ok(());
        }

        // Optionally capture screenshot.
        let screenshot_b64 = if self.request_screenshot {
            match emulator.capture_screenshot_png() {
                Ok(png_data) => {
                    let encoded = base64::engine::general_purpose::STANDARD.encode(&png_data);
                    Some(encoded)
                }
                Err(e) => {
                    tracing::warn!(
                        target: "clawboy::bridge",
                        "screenshot capture failed: {e}"
                    );
                    None
                }
            }
        } else {
            None
        };

        // Format state for the LLM.
        let state_text = format_game_state(&state, screenshot_b64.is_some());

        // Call the LLM.
        let reply = call_llm(
            &mut self.history,
            &state_text,
            screenshot_b64.as_deref(),
            config,
        )
        .await?;

        // Parse the decision.
        let decision = parse_decision(&reply)?;

        // Queue button inputs.
        for input_str in &decision.inputs {
            match GbButton::try_from(input_str.as_str()) {
                Ok(button) => self.input_queue.push_back(button),
                Err(e) => {
                    tracing::warn!(
                        target: "clawboy::bridge",
                        "ignoring invalid button from LLM: {e}"
                    );
                }
            }
        }

        // Route commentary if shared.
        if decision.share && !decision.thought.is_empty() {
            self.commentary_queue.push_back(decision.thought);
        }

        // Update screenshot preference from LLM response.
        self.request_screenshot = decision.request_screenshot;

        // Update state tracking.
        self.previous_state = Some(state);
        self.frames_since_decision = 0;

        Ok(())
    }

    /// Returns `true` if the bridge wants a screenshot on the next
    /// decision turn.
    pub fn wants_screenshot(&self) -> bool {
        self.request_screenshot
    }

    /// Prepares a decision context synchronously in the tick loop.
    ///
    /// Reads game state from emulator memory, checks whether the state
    /// changed meaningfully, captures a screenshot if requested, and
    /// builds the LLM message. Returns `None` if no LLM call is needed
    /// (state unchanged and inputs still queued).
    ///
    /// This is the synchronous first half of the decide flow. The
    /// returned [`DecisionContext`] is `Send` and can be passed to a
    /// spawned task for [`execute_decision`].
    pub fn prepare_decision(&mut self, emulator: &mut Emulator) -> Option<DecisionContext> {
        let state = read_game_state(|addr| emulator.read_memory(addr));

        let meaningful = match &self.previous_state {
            Some(old) => state_changed_meaningfully(old, &state),
            None => true,
        };

        if !meaningful && !self.input_queue.is_empty() {
            self.frames_since_decision = 0;
            self.previous_state = Some(state);
            return None;
        }

        let screenshot_b64 = if self.request_screenshot {
            match emulator.capture_screenshot_png() {
                Ok(png_data) => {
                    let encoded = base64::engine::general_purpose::STANDARD.encode(&png_data);
                    Some(encoded)
                }
                Err(e) => {
                    tracing::warn!(
                        target: "clawboy::bridge",
                        "screenshot capture failed: {e}"
                    );
                    None
                }
            }
        } else {
            None
        };

        let mut state_text = format_game_state(&state, screenshot_b64.is_some());

        // Track stuckness — if position unchanged for 3+ decisions, warn the LLM.
        let pos = (state.player_x, state.player_y);
        if self.last_position == Some(pos) {
            self.stuck_turns += 1;
        } else {
            self.stuck_turns = 0;
        }
        self.last_position = Some(pos);

        if self.stuck_turns >= 3 {
            state_text.push_str(
                "\n⚠ STUCK: Position unchanged for multiple turns. \
                Look at the screenshot carefully. If you see a letter grid, press START. \
                If in a menu, try B to exit. Try different directions.",
            );
        }

        // Inject any queued user messages into conversation history.
        let user_msgs = crate::clawboy::session::drain_user_messages();
        for msg in user_msgs {
            self.history.push(ChatMessage::user(msg));
        }

        let history_snapshot = self.history.clone();

        self.previous_state = Some(state);
        self.frames_since_decision = 0;

        Some(DecisionContext {
            state_text,
            screenshot_b64,
            history: history_snapshot,
        })
    }

    /// Applies a completed LLM decision back to the bridge state.
    ///
    /// Replaces the conversation history with the updated version from
    /// the async task, queues parsed button inputs, and routes
    /// commentary. Called in the tick loop after the spawned
    /// [`execute_decision`] task completes.
    pub fn apply_decision(&mut self, result: DecisionOutcome) {
        self.history = result.updated_history;

        for input_str in &result.decision.inputs {
            match GbButton::try_from(input_str.as_str()) {
                Ok(button) => self.input_queue.push_back(button),
                Err(e) => {
                    tracing::warn!(
                        target: "clawboy::bridge",
                        "ignoring invalid button from LLM: {e}"
                    );
                }
            }
        }

        if result.decision.share && !result.decision.thought.is_empty() {
            self.commentary_queue
                .push_back(result.decision.thought.clone());
        }

        // Track screenshot frequency and force periodic screenshots.
        if result.decision.request_screenshot {
            self.turns_without_screenshot = 0;
            self.request_screenshot = true;
        } else {
            self.turns_without_screenshot += 1;
            if self.turns_without_screenshot >= MAX_TURNS_WITHOUT_SCREENSHOT {
                self.request_screenshot = true;
                self.turns_without_screenshot = 0;
            } else {
                self.request_screenshot = false;
            }
        }
    }

    /// Pops the next commentary message for routing to chat.
    ///
    /// Returns `None` when the commentary queue is empty.
    pub fn take_commentary(&mut self) -> Option<String> {
        self.commentary_queue.pop_front()
    }

    /// Updates the decision interval (in milliseconds).
    ///
    /// Converts the new interval to frames at 60 fps.
    pub fn update_interval(&mut self, ms: u64) {
        self.decision_interval_frames = ms * 60 / 1000;
    }
}

// ── Split-async decision types ──────────────────────────────────────

/// Owned snapshot of everything needed for an async LLM call.
///
/// Produced by [`AgentBridge::prepare_decision`] (sync, in tick loop)
/// and consumed by [`execute_decision`] (async, in spawned task).
/// All fields are owned so the struct is `Send`.
#[allow(dead_code)]
pub struct DecisionContext {
    /// Formatted game state text for the user message.
    pub state_text: String,
    /// Optional base64-encoded PNG screenshot.
    pub screenshot_b64: Option<String>,
    /// Snapshot of the conversation history at preparation time.
    pub history: Vec<ChatMessage>,
}

/// Result of a successful async LLM decision.
///
/// Returned by [`execute_decision`] and consumed by
/// [`AgentBridge::apply_decision`].
#[allow(dead_code)]
pub struct DecisionOutcome {
    /// The parsed agent decision (inputs, thought, flags).
    pub decision: AgentDecision,
    /// Updated conversation history (with user + assistant messages
    /// appended and pruned).
    pub updated_history: Vec<ChatMessage>,
}

/// Executes an LLM decision asynchronously.
///
/// Takes ownership of a [`DecisionContext`] and a [`Config`], calls the
/// LLM provider, parses the JSON response, and returns a
/// [`DecisionOutcome`] that the tick loop can apply via
/// [`AgentBridge::apply_decision`].
///
/// This function is `Send` and designed to run in a spawned tokio task
/// so the emulator tick loop is never blocked by LLM latency.
///
/// # Errors
///
/// Returns a descriptive error string if the LLM call fails or the
/// response cannot be parsed into a valid [`AgentDecision`].
#[allow(dead_code)]
pub async fn execute_decision(
    mut ctx: DecisionContext,
    config: Config,
) -> Result<DecisionOutcome, String> {
    let reply = call_llm(
        &mut ctx.history,
        &ctx.state_text,
        ctx.screenshot_b64.as_deref(),
        &config,
    )
    .await?;

    let decision = parse_decision(&reply)?;

    Ok(DecisionOutcome {
        decision,
        updated_history: ctx.history,
    })
}

// ── LLM calling ─────────────────────────────────────────────────────

/// Calls the LLM with the current game state and optional screenshot.
///
/// Builds a user message from the state text (and an optional base64
/// PNG screenshot marker), appends it to the conversation history,
/// invokes the provider, and records the assistant reply. Prunes
/// history when it exceeds [`MAX_HISTORY_LEN`].
async fn call_llm(
    history: &mut Vec<ChatMessage>,
    state_text: &str,
    screenshot_base64: Option<&str>,
    config: &Config,
) -> Result<String, String> {
    // Build user message content.
    let user_content = if let Some(img) = screenshot_base64 {
        format!("{state_text}\n\n[Screenshot (base64 PNG)]\n{img}")
    } else {
        state_text.to_owned()
    };

    history.push(ChatMessage::user(user_content));

    // Resolve provider settings from config.
    let provider_name = config.default_provider.as_deref().unwrap_or("anthropic");
    let api_key = config.api_key.as_deref();

    let provider = zeroclaw::providers::create_resilient_provider_with_options(
        provider_name,
        api_key,
        config.api_url.as_deref(),
        &config.reliability,
        None,
        &zeroclaw::providers::ProviderRuntimeOptions {
            auth_profile_override: None,
            provider_api_url: config.api_url.clone(),
            zeroclaw_dir: Some(config.workspace_dir.clone()),
            secrets_encrypt: config.secrets.encrypt,
            reasoning_enabled: None,
            reasoning_effort: None,
            custom_headers: None,
        },
    )
    .map_err(|e| format!("failed to create provider: {e}"))?;

    let model = config.default_model.as_deref().unwrap_or(FALLBACK_MODEL);

    // Build chat request.
    let request = ChatRequest {
        messages: history.as_slice(),
        tools: None,
    };

    let response = provider
        .chat(request, model, DECISION_TEMPERATURE)
        .await
        .map_err(|e| format!("LLM call failed: {e}"))?;

    let reply = response.text.unwrap_or_default();

    history.push(ChatMessage::assistant(&reply));

    // Prune history to keep it manageable.
    if history.len() > MAX_HISTORY_LEN {
        let system = history.remove(0);
        let drain_count = history.len().saturating_sub(HISTORY_RETAIN);
        history.drain(..drain_count);
        history.insert(0, system);
    }

    Ok(reply)
}

// ── Response parsing ────────────────────────────────────────────────

/// Parsed LLM decision for a single game turn.
#[derive(Debug, Clone, serde::Deserialize)]
pub struct AgentDecision {
    /// Button sequence to execute.
    #[serde(default)]
    pub inputs: Vec<String>,
    /// Internal reasoning (logged, optionally shared with users).
    #[serde(default)]
    pub thought: String,
    /// Whether to share the thought as commentary in chat.
    #[serde(default)]
    pub share: bool,
    /// Whether the agent wants a screenshot on the next turn.
    #[serde(default)]
    pub request_screenshot: bool,
}

/// Parses the LLM response JSON, handling common edge cases.
///
/// Tries direct JSON parse first, then extracts from markdown code
/// blocks, then falls back to finding the first `{...}` substring.
///
/// # Errors
///
/// Returns a descriptive error if no valid JSON can be extracted.
fn parse_decision(response: &str) -> Result<AgentDecision, String> {
    // Try direct JSON parse first.
    if let Ok(decision) = serde_json::from_str::<AgentDecision>(response) {
        return Ok(decision);
    }

    // Try extracting JSON from markdown code blocks.
    let trimmed = response.trim();
    let json_str = if let Some(start) = trimmed.find("```json") {
        let content = &trimmed[start + 7..];
        content.split("```").next().unwrap_or(content).trim()
    } else if let Some(start) = trimmed.find("```") {
        let content = &trimmed[start + 3..];
        content.split("```").next().unwrap_or(content).trim()
    } else if let Some(start) = trimmed.find('{') {
        let end = trimmed.rfind('}').unwrap_or(trimmed.len());
        &trimmed[start..=end]
    } else {
        return Err(format!(
            "no JSON found in response: {}",
            &response[..response.len().min(100)]
        ));
    };

    serde_json::from_str::<AgentDecision>(json_str).map_err(|e| format!("JSON parse failed: {e}"))
}

// ── State comparison ────────────────────────────────────────────────

/// Returns `true` if the game state changed meaningfully since last check.
///
/// Triggers on map changes, battle transitions, badge acquisitions,
/// UI state changes, party size changes, significant HP changes
/// (>10% of max), and position deltas greater than 3 tiles.
fn state_changed_meaningfully(old: &GameState, new: &GameState) -> bool {
    // Map change.
    if old.map_id != new.map_id {
        return true;
    }
    // Battle start/end.
    if old.in_battle != new.in_battle {
        return true;
    }
    // Badge earned.
    if old.badge_count != new.badge_count {
        return true;
    }
    // UI state change (menu, text, etc.).
    if old.special_ui != new.special_ui {
        return true;
    }
    // Party size change.
    if old.party.len() != new.party.len() {
        return true;
    }

    // Check HP changes > 10% of max.
    for (o, n) in old.party.iter().zip(new.party.iter()) {
        if n.max_hp > 0 {
            let diff = (i32::from(o.hp) - i32::from(n.hp)).unsigned_abs();
            if diff * HP_CHANGE_THRESHOLD > u32::from(n.max_hp) {
                return true;
            }
        }
    }

    // Position delta > 3 tiles.
    let dx = (i16::from(old.player_x) - i16::from(new.player_x)).unsigned_abs();
    let dy = (i16::from(old.player_y) - i16::from(new.player_y)).unsigned_abs();
    if dx > POSITION_DELTA_THRESHOLD || dy > POSITION_DELTA_THRESHOLD {
        return true;
    }

    // Nothing meaningful changed -- repeat last input.
    false
}

// ── Tests ───────────────────────────────────────────────────────────

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::clawboy::types::{PartyMember, SpecialUiState};

    // ── parse_decision tests ────────────────────────────────────────

    #[test]
    fn parse_decision_direct_json() {
        let json =
            r#"{"inputs":["A","B"],"thought":"testing","share":false,"request_screenshot":true}"#;
        let decision = parse_decision(json).unwrap();
        assert_eq!(decision.inputs, vec!["A", "B"]);
        assert_eq!(decision.thought, "testing");
        assert!(!decision.share);
        assert!(decision.request_screenshot);
    }

    #[test]
    fn parse_decision_markdown_code_block() {
        let response =
            "Here's my decision:\n```json\n{\"inputs\":[\"UP\"],\"thought\":\"go north\"}\n```";
        let decision = parse_decision(response).unwrap();
        assert_eq!(decision.inputs, vec!["UP"]);
        assert_eq!(decision.thought, "go north");
    }

    #[test]
    fn parse_decision_bare_code_block() {
        let response = "```\n{\"inputs\":[\"DOWN\"],\"thought\":\"explore\"}\n```\nDone!";
        let decision = parse_decision(response).unwrap();
        assert_eq!(decision.inputs, vec!["DOWN"]);
    }

    #[test]
    fn parse_decision_embedded_json() {
        let response = "I think we should go left. {\"inputs\":[\"LEFT\"],\"thought\":\"wall ahead\"} That's my plan.";
        let decision = parse_decision(response).unwrap();
        assert_eq!(decision.inputs, vec!["LEFT"]);
    }

    #[test]
    fn parse_decision_defaults_on_missing_fields() {
        let json = r#"{"inputs":["A"]}"#;
        let decision = parse_decision(json).unwrap();
        assert_eq!(decision.inputs, vec!["A"]);
        assert!(decision.thought.is_empty());
        assert!(!decision.share);
        assert!(!decision.request_screenshot);
    }

    #[test]
    fn parse_decision_no_json() {
        let response = "I don't know what to do!";
        let err = parse_decision(response).unwrap_err();
        assert!(err.contains("no JSON found"));
    }

    #[test]
    fn parse_decision_invalid_json() {
        let response = "{broken json}";
        let err = parse_decision(response).unwrap_err();
        assert!(err.contains("JSON parse failed"));
    }

    #[test]
    fn parse_decision_empty_inputs() {
        let json = r#"{"inputs":[],"thought":"waiting"}"#;
        let decision = parse_decision(json).unwrap();
        assert!(decision.inputs.is_empty());
    }

    // ── state_changed_meaningfully tests ────────────────────────────

    fn base_state() -> GameState {
        GameState {
            map_id: 1,
            map_name: "VIRIDIAN_CITY".to_owned(),
            player_x: 10,
            player_y: 10,
            in_battle: false,
            party: vec![PartyMember {
                species: "CHARMANDER".to_owned(),
                level: 10,
                hp: 40,
                max_hp: 40,
                status: "OK".to_owned(),
            }],
            badges: vec!["BOULDER".to_owned()],
            badge_count: 1,
            money: 1000,
            bag: vec![],
            special_ui: SpecialUiState::None,
        }
    }

    #[test]
    fn state_no_change() {
        let old = base_state();
        let new = base_state();
        assert!(!state_changed_meaningfully(&old, &new));
    }

    #[test]
    fn state_map_change() {
        let old = base_state();
        let mut new = base_state();
        new.map_id = 2;
        assert!(state_changed_meaningfully(&old, &new));
    }

    #[test]
    fn state_battle_start() {
        let old = base_state();
        let mut new = base_state();
        new.in_battle = true;
        assert!(state_changed_meaningfully(&old, &new));
    }

    #[test]
    fn state_badge_earned() {
        let old = base_state();
        let mut new = base_state();
        new.badge_count = 2;
        assert!(state_changed_meaningfully(&old, &new));
    }

    #[test]
    fn state_ui_change() {
        let old = base_state();
        let mut new = base_state();
        new.special_ui = SpecialUiState::TextBox;
        assert!(state_changed_meaningfully(&old, &new));
    }

    #[test]
    fn state_party_size_change() {
        let old = base_state();
        let mut new = base_state();
        new.party.push(PartyMember {
            species: "PIDGEY".to_owned(),
            level: 5,
            hp: 20,
            max_hp: 20,
            status: "OK".to_owned(),
        });
        assert!(state_changed_meaningfully(&old, &new));
    }

    #[test]
    fn state_significant_hp_loss() {
        let old = base_state();
        let mut new = base_state();
        // 40 max_hp, 10% = 4. Drop from 40 to 35 = 5 > 4.
        new.party[0].hp = 35;
        assert!(state_changed_meaningfully(&old, &new));
    }

    #[test]
    fn state_minor_hp_change() {
        let old = base_state();
        let mut new = base_state();
        // 40 max_hp, 10% = 4. Drop from 40 to 37 = 3 < 4.
        new.party[0].hp = 37;
        assert!(!state_changed_meaningfully(&old, &new));
    }

    #[test]
    fn state_large_position_delta() {
        let old = base_state();
        let mut new = base_state();
        new.player_x = 14; // delta = 4 > 3
        assert!(state_changed_meaningfully(&old, &new));
    }

    #[test]
    fn state_small_position_delta() {
        let old = base_state();
        let mut new = base_state();
        new.player_x = 13; // delta = 3, not > 3
        assert!(!state_changed_meaningfully(&old, &new));
    }

    // ── AgentBridge construction tests ──────────────────────────────

    #[test]
    fn bridge_new_builds_system_prompt() {
        let bridge = AgentBridge::new(500, String::new());
        assert!(!bridge.system_prompt.is_empty());
        assert!(bridge.system_prompt.contains("Pokemon Red"));
        assert!(bridge.system_prompt.contains("JSON"));
        assert_eq!(bridge.history.len(), 1);
        assert_eq!(bridge.history[0].role, "system");
    }

    #[test]
    fn bridge_new_includes_journal_context() {
        let journal = "Previously: Beat Brock".to_owned();
        let bridge = AgentBridge::new(500, journal);
        assert!(bridge.system_prompt.contains("Beat Brock"));
    }

    #[test]
    fn bridge_new_interval_conversion() {
        // 500ms at 60fps = 30 frames
        let bridge = AgentBridge::new(500, String::new());
        assert_eq!(bridge.decision_interval_frames, 30);
    }

    #[test]
    fn bridge_new_1000ms_interval() {
        // 1000ms at 60fps = 60 frames
        let bridge = AgentBridge::new(1000, String::new());
        assert_eq!(bridge.decision_interval_frames, 60);
    }

    #[test]
    fn bridge_needs_decision_initially_false() {
        let bridge = AgentBridge::new(500, String::new());
        // frames_since_decision starts at 0, so it should not need a decision.
        assert!(!bridge.needs_decision());
    }

    #[test]
    fn bridge_needs_decision_after_enough_frames() {
        let mut bridge = AgentBridge::new(500, String::new());
        bridge.frames_since_decision = 30;
        assert!(bridge.needs_decision());
    }

    #[test]
    fn bridge_needs_decision_false_when_queue_full() {
        let mut bridge = AgentBridge::new(500, String::new());
        bridge.frames_since_decision = 30;
        bridge.input_queue.push_back(GbButton::A);
        assert!(!bridge.needs_decision());
    }

    #[test]
    fn bridge_update_interval() {
        let mut bridge = AgentBridge::new(500, String::new());
        assert_eq!(bridge.decision_interval_frames, 30);
        bridge.update_interval(1000);
        assert_eq!(bridge.decision_interval_frames, 60);
    }

    #[test]
    fn bridge_take_commentary_empty() {
        let mut bridge = AgentBridge::new(500, String::new());
        assert!(bridge.take_commentary().is_none());
    }

    #[test]
    fn bridge_take_commentary_drains() {
        let mut bridge = AgentBridge::new(500, String::new());
        bridge.commentary_queue.push_back("Hello!".to_owned());
        bridge.commentary_queue.push_back("World!".to_owned());
        assert_eq!(bridge.take_commentary().unwrap(), "Hello!");
        assert_eq!(bridge.take_commentary().unwrap(), "World!");
        assert!(bridge.take_commentary().is_none());
    }
}
