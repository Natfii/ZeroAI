/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Live agent session management with streaming tool-call loop integration.
//!
//! A session represents a single multi-turn conversation with the `ZeroClaw`
//! agent loop. The lifecycle follows a strict state machine:
//!
//! 1. **Start** -- [`session_start`](crate::session_start) creates a new
//!    session, parsing daemon config and building the system prompt.
//! 2. **Seed** -- optional: inject prior context via
//!    [`session_seed_history`](crate::session_seed_history).
//! 3. **Send** -- [`session_send`](crate::session_send) runs the full
//!    tool-call loop, streaming progress deltas through an
//!    [`FfiSessionListener`] callback.
//! 4. **Cancel / Clear** -- abort the current send or wipe history.
//! 5. **History** -- [`session_history`](crate::session_history) returns
//!    the conversation transcript.
//! 6. **Destroy** -- [`session_destroy`](crate::session_destroy) tears
//!    down the session and releases all resources.
//!
//! Only one session exists at a time (guarded by the [`SESSION`] mutex).

use std::fmt::Write;
use std::sync::{Arc, Mutex};

use tokio_util::sync::CancellationToken;
use zeroclaw::providers::{ChatMessage, ChatRequest, ModelProvider};
use zeroclaw::tools::{Tool, ToolSpec};

use crate::error::FfiError;
use crate::runtime::{clone_daemon_config, clone_daemon_memory};
use crate::session_history::{auto_compact_history, build_memory_context};
use crate::session_registry::build_tools_registry;
use crate::session_text::{append_android_identity_extras, compose_multimodal_message};
// Re-exported so `crate::session::extract_thinking_from_text` (used by
// `streaming.rs`) and the `super::*` glob in `session_tests.rs` resolve
// against the canonical implementations in `session_text`.
pub(crate) use crate::session_text::{
    extract_thinking_from_text, parse_xml_tool_calls, truncate_tool_args_hint,
};
use crate::session_tool_specs::{
    build_android_tool_descs, build_android_tool_specs, build_tool_use_protocol,
    tool_specs_from_registry,
};

/// Maximum user message size in bytes (1 MiB).
const MAX_MESSAGE_BYTES: usize = 1_048_576;

/// Default HTTP user-agent for web search, web fetch, and HTTP request tools.
pub(crate) const DEFAULT_USER_AGENT: &str = "ZeroClaw/1.0 (Android)";

/// Default maximum agentic tool-use iterations per user message.
const DEFAULT_MAX_TOOL_ITERATIONS: usize = 10;

/// Maximum number of tool calls to execute from a single model response.
///
/// Prompt-guided models (e.g. Codex) sometimes emit dozens of
/// `<tool_call>` tags in one response. Executing all of them wastes
/// tokens and fills the thinking card with noise. Excess calls are
/// dropped with a warning.
const MAX_TOOL_CALLS_PER_RESPONSE: usize = 5;

/// Non-system message count threshold that triggers auto-compaction.
const DEFAULT_MAX_HISTORY_MESSAGES: usize = 50;

/// Number of most-recent non-system messages to keep after compaction.
pub(crate) const COMPACTION_KEEP_RECENT: usize = 20;

/// Safety cap for the compaction source transcript sent to the summariser.
pub(crate) const COMPACTION_MAX_SOURCE_CHARS: usize = 12_000;

/// Maximum characters retained in the stored compaction summary.
pub(crate) const COMPACTION_MAX_SUMMARY_CHARS: usize = 2_000;

/// Minimum characters per chunk when streaming the final response text.
const STREAM_CHUNK_MIN_CHARS: usize = 80;

/// Maximum number of seed messages accepted by [`session_seed_inner`].
const MAX_SEED_MESSAGES: usize = 20;

/// The global singleton session slot.
///
/// At most one [`Session`] is active at any time. Operations that require
/// a running session acquire this mutex and return
/// [`FfiError::StateError`] when the slot is `None`.
static SESSION: Mutex<Option<Session>> = Mutex::new(None);

/// The cancellation token for the currently active [`session_send_inner`] call.
///
/// Set at the start of `session_send_inner`, cleared on exit. Calling
/// [`session_cancel_inner`] cancels the token, causing the agent loop
/// to abort at the next check point.
static CANCEL_TOKEN: Mutex<Option<CancellationToken>> = Mutex::new(None);

/// Locks the [`SESSION`] mutex, recovering from poison if a prior holder panicked.
///
/// See [`crate::runtime::lock_daemon`] for the rationale behind poison recovery.
fn lock_session() -> std::sync::MutexGuard<'static, Option<Session>> {
    SESSION.lock().unwrap_or_else(|e| {
        tracing::warn!("Session mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Locks the [`CANCEL_TOKEN`] mutex, recovering from poison.
fn lock_cancel_token() -> std::sync::MutexGuard<'static, Option<CancellationToken>> {
    CANCEL_TOKEN.lock().unwrap_or_else(|e| {
        tracing::warn!("Cancel token mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}



/// Internal session state holding conversation history and provider config.
///
/// Not exposed across the FFI boundary -- Kotlin interacts exclusively
/// through exported free functions and the [`FfiSessionListener`] callback.
struct Session {
    /// Accumulated conversation messages (user + assistant turns).
    history: Vec<ChatMessage>,
    /// Parsed daemon configuration snapshot taken at session creation.
    config: zeroclaw::Config,
    /// Assembled system prompt (identity + workspace files).
    system_prompt: String,
    /// Model identifier passed to the provider (e.g. `"gpt-4o"`).
    model: String,
    /// Sampling temperature for the provider.
    temperature: f64,
    /// Provider name used to create the provider instance (e.g. `"openai"`).
    provider_name: String,
    /// Tools registry built from available upstream tools and FFI wrappers.
    tools_registry: Vec<Box<dyn Tool>>,
}

/// RAII guard that restores session state (history + tools) on drop,
/// even if a panic occurs during processing.
///
/// When [`session_send_inner`] takes history and tools out of the
/// [`SESSION`] mutex for processing, a panic between take and put-back
/// would leave the session in a zombified state (active but empty).
/// This guard's [`Drop`] re-acquires the mutex and writes the state
/// back unless [`SessionStateGuard::defuse`] was called.
///
/// # Invariants
///
/// **Must** only be constructed after the [`SESSION`] mutex has been
/// released (i.e. after `history` and `tools` are moved out and the
/// guard is dropped). The [`Drop`] impl re-acquires both [`SESSION`]
/// and [`CANCEL_TOKEN`] via their poison-recovering helpers.
///
/// Constructing this guard while holding either lock **will deadlock**
/// during stack unwinding. Call [`SessionStateGuard::defuse`] after a
/// successful put-back to prevent a redundant restore.
struct SessionStateGuard {
    /// Conversation history taken from the session. `None` once defused.
    history: Option<Vec<ChatMessage>>,
    /// Tools registry taken from the session. `None` once defused.
    tools: Option<Vec<Box<dyn Tool>>>,
}

impl SessionStateGuard {
    /// Creates a new guard holding the taken-out session state.
    fn new(history: Vec<ChatMessage>, tools: Vec<Box<dyn Tool>>) -> Self {
        Self {
            history: Some(history),
            tools: Some(tools),
        }
    }

    /// Returns mutable references to the held history and tools.
    ///
    /// # Errors
    ///
    /// Returns [`FfiError::StateCorrupted`] if called after [`take`](Self::take)
    /// or [`defuse`](Self::defuse) already consumed the held state.
    #[allow(clippy::type_complexity)]
    fn state_mut(&mut self) -> Result<(&mut Vec<ChatMessage>, &[Box<dyn Tool>]), FfiError> {
        debug_assert!(
            self.history.is_some() && self.tools.is_some(),
            "SessionStateGuard::state_mut called after take/defuse"
        );
        let history = self
            .history
            .as_mut()
            .ok_or_else(|| FfiError::StateCorrupted {
                detail: "SessionStateGuard::state_mut called after take/defuse (history)".into(),
            })?;
        let tools = self
            .tools
            .as_deref()
            .ok_or_else(|| FfiError::StateCorrupted {
                detail: "SessionStateGuard::state_mut called after take/defuse (tools)".into(),
            })?;
        Ok((history, tools))
    }

    /// Consumes the held state, returning ownership to the caller.
    ///
    /// After this call the guard's [`Drop`] is a no-op.
    ///
    /// # Errors
    ///
    /// Returns [`FfiError::StateCorrupted`] if called after a previous
    /// [`take`](Self::take) or [`defuse`](Self::defuse) already consumed
    /// the held state.
    #[allow(clippy::type_complexity)]
    fn take(mut self) -> Result<(Vec<ChatMessage>, Vec<Box<dyn Tool>>), FfiError> {
        debug_assert!(
            self.history.is_some() && self.tools.is_some(),
            "SessionStateGuard::take called after take/defuse"
        );
        match (self.history.take(), self.tools.take()) {
            (Some(history), Some(tools)) => Ok((history, tools)),
            _ => Err(FfiError::StateCorrupted {
                detail: "SessionStateGuard::take called after take/defuse".into(),
            }),
        }
    }
}

impl Drop for SessionStateGuard {
    /// Restore session state when the guard is dropped during a panic unwind.
    ///
    /// # Safety — Mutex Reentrancy
    ///
    /// This acquires `SESSION` and `CANCEL_TOKEN` mutexes during `drop`, which
    /// may run inside a `catch_unwind` unwind. This is safe because:
    /// - `run_agent_loop` (the only call-site that creates a guard) takes
    ///   `history` and `tools` **out** of the session before entering the loop,
    ///   so it does **not** hold the `SESSION` lock when panic occurs.
    /// - `lock_session()` / `lock_cancel_token()` use poison-recovering helpers,
    ///   so a previously-panicked thread cannot deadlock this path.
    fn drop(&mut self) {
        let Some(history) = self.history.take() else {
            return;
        };
        let Some(tools) = self.tools.take() else {
            return;
        };

        tracing::warn!("SessionStateGuard::drop restoring state after panic");
        let mut guard = lock_session();
        if let Some(session) = guard.as_mut() {
            session.history = history;
            session.tools_registry = tools;
        }
        // Also clear cancel token to prevent stale state.
        *lock_cancel_token() = None;
    }
}

/// A single conversation message exchanged over the FFI boundary.
///
/// Mirrors [`zeroclaw::providers::ChatMessage`] but uses UniFFI-compatible
/// types. The `role` field is one of `"system"`, `"user"`, or `"assistant"`.
#[derive(uniffi::Record, Clone, Debug)]
pub struct SessionMessage {
    /// The message role: `"system"`, `"user"`, or `"assistant"`.
    pub role: String,
    /// The text content of the message.
    pub content: String,
}

/// Typed progress phases sent from the agent loop to the Kotlin UI.
///
/// Each variant maps to a distinct visual state in the thinking card.
/// Replaces the previous freeform `on_progress(String)` callback.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum FfiProgressPhase {
    /// Building memory context from the vector store.
    SearchingMemory,
    /// Sending the prompt to the LLM provider.
    ///
    /// `round` is 1-based; round 1 is the initial call, round 2+ are
    /// tool-loop iterations.
    CallingProvider { round: u32 },
    /// The LLM returned tool call requests.
    ///
    /// `count` is the number of tool calls, `llm_duration_secs` is the
    /// wall-clock time for the LLM response.
    GotToolCalls { count: u32, llm_duration_secs: u64 },
    /// The agent is now streaming the final response text.
    StreamingResponse,
    /// The conversation history is being compacted.
    Compacting,
    /// No active progress (clears any displayed status).
    Idle,
    /// Raw progress message from the upstream agent loop that doesn't map
    /// to a specific typed phase.
    Raw { message: String },
}

/// Callback interface that Kotlin implements to receive live agent session events.
///
/// Events are dispatched from the tokio runtime thread during
/// [`session_send`](crate::session_send). Implementations must be
/// thread-safe (`Send + Sync`). Each callback corresponds to a distinct
/// phase of the agent's tool-call loop execution.
#[uniffi::export(callback_interface)]
pub trait FfiSessionListener: Send + Sync {
    /// The agent is producing internal reasoning (thinking/planning).
    ///
    /// Called with progressive text chunks as the agent reasons about
    /// which tools to invoke or how to answer.
    fn on_thinking(&self, text: String);

    /// A chunk of the agent's final response text has arrived.
    ///
    /// Called incrementally as the provider streams response tokens.
    /// Concatenating all chunks yields the full response.
    fn on_response_chunk(&self, text: String);

    /// The agent is about to invoke a tool.
    ///
    /// `name` is the tool identifier (e.g. `"read_file"`).
    /// `arguments_hint` is a short summary of the arguments, which may
    /// be empty if no hint is available.
    fn on_tool_start(&self, name: String, arguments_hint: String);

    /// A tool invocation has completed.
    ///
    /// `name` is the tool identifier, `success` indicates whether the
    /// tool returned a result or an error, and `duration_secs` is the
    /// wall-clock execution time rounded to whole seconds.
    fn on_tool_result(&self, name: String, success: bool, duration_secs: u64);

    /// Raw tool output text for display in a collapsible detail section.
    ///
    /// Called after [`on_tool_result`](FfiSessionListener::on_tool_result)
    /// with the full stdout/stderr captured from the tool execution.
    fn on_tool_output(&self, name: String, output: String);

    /// A typed progress phase from the agent loop.
    ///
    /// Each [`FfiProgressPhase`] variant maps to a distinct visual state
    /// in the thinking card. Replaces the previous freeform string callback.
    fn on_progress(&self, phase: FfiProgressPhase);

    /// Clears any displayed progress status.
    ///
    /// Called when the agent transitions out of a progress phase (e.g.
    /// before streaming the final response text).
    fn on_progress_clear(&self);

    /// The conversation history was compacted to fit the context window.
    ///
    /// `summary` contains the AI-generated summary that replaced older
    /// messages. The UI should display this as a fold/expansion point.
    fn on_compaction(&self, summary: String);

    /// The agent loop has finished and the full response is available.
    ///
    /// `full_response` contains the concatenated final answer. This is
    /// always the last callback for a successful send.
    fn on_complete(&self, full_response: String);

    /// An unrecoverable error occurred during the agent loop.
    ///
    /// `error` contains a human-readable description. The session
    /// remains valid and the caller may retry with a new send.
    fn on_error(&self, error: String);

    /// The current send was cancelled by the user.
    ///
    /// The session remains valid; the caller may issue a new send.
    fn on_cancelled(&self);
}

// ── Session lifecycle FFI exports ───────────────────────────────────────────

crate::ffi_export!(
    /// Creates a new live agent session from the running daemon's configuration.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if a session is already active or the
    /// daemon is not running, [`crate::FfiError::StateCorrupted`] if the session
    /// mutex is poisoned, [`crate::FfiError::SpawnError`] if provider creation fails,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn session_start() -> () = session_start_inner
);

crate::ffi_export!(
    /// Injects seed messages into the active session's conversation history.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is active,
    /// [`crate::FfiError::StateCorrupted`] if the session mutex is poisoned, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn session_seed(messages: Vec<SessionMessage>) -> () = session_seed_inner
);

crate::ffi_export!(
    /// Clears the active session's conversation history.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is active,
    /// [`crate::FfiError::StateCorrupted`] if the session mutex is poisoned, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn session_clear() -> () = session_clear_inner
);

crate::ffi_export!(
    /// Returns the current conversation history as a list of session messages.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is active,
    /// [`crate::FfiError::StateCorrupted`] if the session mutex is poisoned, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn session_history() -> Vec<SessionMessage> = session_history_inner
);

crate::ffi_export!(
    /// Destroys the active session and releases all resources.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is active,
    /// [`crate::FfiError::StateCorrupted`] if the session mutex is poisoned, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn session_destroy() -> () = session_destroy_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

pub(crate) fn session_start_inner() -> Result<(), FfiError> {
    let config = clone_daemon_config()?;

    // Upstream moved provider routing into the nested
    // `config.providers.models.<type>.<alias>` schema; `default_provider`
    // / `default_model` / `default_temperature` no longer exist as flat
    // fields. The canonical reads:
    //   - provider type: `effective_model_provider_type`
    //   - model:         `Config::resolve_default_model`
    //   - temperature:   `ModelProviderConfig.temperature` (Option, per-provider)
    let provider_name = crate::runtime::effective_model_provider_type(&config)?;
    let model = config
        .resolve_default_model()
        .ok_or_else(|| FfiError::ConfigError {
            detail: crate::runtime::NO_MODEL_CONFIGURED.into(),
        })?;
    let temperature = config
        .first_model_provider_alias()
        .and_then(|s| s.as_str().split_once('.').map(|(_, a)| a.to_string()))
        .and_then(|alias| config.providers.models.find(&provider_name, &alias).and_then(|p| p.temperature))
        .unwrap_or(0.7_f64);

    // Build tools registry from daemon memory + config.
    let tools_registry = if let Ok(mem) = clone_daemon_memory() {
        build_tools_registry(&config, mem)
    } else {
        tracing::warn!("Memory backend unavailable; session tools will be limited");
        Vec::new()
    };

    // Generate tool descriptions from the real tools registry, plus
    // static descriptions for tools the LLM should know about but that
    // cannot be constructed from the FFI crate.
    let mut tool_descs = build_android_tool_descs(&config);
    for tool in &tools_registry {
        let name = tool.name().to_string();
        if !tool_descs.iter().any(|(n, _)| n == &name) {
            tool_descs.push((name, tool.description().to_string()));
        }
    }

    let tool_desc_refs: Vec<(&str, &str)> = tool_descs
        .iter()
        .map(|(name, desc)| (name.as_str(), desc.as_str()))
        .collect();

    // Upstream moved `agent` from a field to a method: `Config::agent`
    // returns `Option<&AliasedAgentConfig>` looked up in `config.agents`.
    // Identity and `compact_context` live on the per-agent struct.
    let default_agent = config.agent("default");
    let bootstrap_max_chars = match default_agent {
        Some(a) if a.compact_context => Some(6000),
        _ => None,
    };

    // Probe native-tool support without forcing the simple factory
    // (`create_model_provider("custom", None)`) which ignores Config and
    // therefore can't see `[model_providers.custom.<alias>].uri`. For
    // self-hosted families (`custom`, `lmstudio`, etc.) Android users
    // routinely point at LM Studio / vLLM endpoints whose URI lives in
    // the config table — the simple factory then errors with
    // "Custom model_provider requires `uri`". The for-alias factory
    // reads the URI from Config directly.
    // The simple `create_model_provider(name, key)` factory ignores
    // Config entirely (passes None internally), so self-hosted families
    // (custom / lmstudio / llamacpp) can't read `uri` from
    // `[providers.models.<type>.<alias>]` through that path -- the
    // factory sees `api_url = None` and bails with "Custom
    // model_provider requires `uri`". Build the runtime options from
    // the alias entry (which holds the URI) and pass them through.
    let native_tools = {
        let dotted = config
            .first_model_provider_alias()
            .unwrap_or_else(|| format!("{provider_name}.default"));
        let alias = dotted
            .as_str()
            .split_once('.')
            .map(|(_, a)| a.to_string())
            .unwrap_or_else(|| "default".to_string());
        let opts = zeroclaw::providers::provider_runtime_options_for_alias(
            &config,
            &provider_name,
            &alias,
        );
        let provider = zeroclaw::providers::create_model_provider_with_options(
            &provider_name,
            None,
            &opts,
        )
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to create provider for native-tools check: {e}"),
        })?;
        provider.supports_native_tools()
    };

    // Upstream made `zeroclaw::skills` `pub(crate)`; reach the same fn through
    // the workspace sub-crate directly.
    let skills = zeroclaw_runtime::skills::load_skills_with_config(&config.data_dir, &config);
    // `config.email`, `config.identity`, and `config.system_prompt`
    // were removed from the top-level Config upstream. Identity is now
    // per-agent on `AliasedAgentConfig.identity`. `build_system_prompt_with_mode`
    // also dropped its `hub_app_context` parameter in the upstream
    // signature update — the call below matches the new arity.
    let identity_opt = default_agent.map(|a| &a.identity);
    let mut system_prompt = zeroclaw::channels::build_system_prompt_with_mode(
        &config.data_dir,
        &model,
        &tool_desc_refs,
        &skills,
        identity_opt,
        bootstrap_max_chars,
        native_tools,
        config.skills.prompt_injection_mode,
        zeroclaw_config::autonomy::AutonomyLevel::default(),
    );

    // When the provider does not support native tool calling, append the
    // full Tool Use Protocol to the system prompt so the model knows to
    // emit <tool_call> XML tags. Without this, the model will answer
    // directly instead of using tools.
    //
    // When native tools ARE supported, append a condensed XML fallback
    // hint. Some models (Qwen, GLM distills, small Ollama models) receive
    // the native tool schema but cannot generate structured tool_calls in
    // the response. Without this hint they spiral: they see tools in the
    // prompt, fail to invoke them, and retry the same text-based attempt
    // every turn. The agent loop already parses <tool_call> XML as a
    // fallback even when native tools are enabled.
    if !tools_registry.is_empty() {
        if native_tools {
            system_prompt.push_str(
                "\n## Tool Use Fallback\n\n\
                 Your tools are available via the API's native function-calling mechanism.\n\
                 If you cannot emit structured tool_calls, you may instead wrap a JSON \
                 object in <tool_call></tool_call> tags:\n\n\
                 <tool_call>\n\
                 {\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n\
                 </tool_call>\n\n\
                 Never describe or simulate tool calls in prose. \
                 Either use native function calling or the XML tags above.\n",
            );
        } else {
            system_prompt.push_str(&build_tool_use_protocol(&tools_registry, &config));
        }
    }

    // Upstream AIEOS only renders agent identity fields; Android onboarding
    // also stores user_name, timezone, and communication_style inside the
    // identity JSON object. Extract and append them so the model knows who
    // it is talking to.
    // `config.identity` moved to `AliasedAgentConfig.identity` upstream —
    // the Android extras (user_name, timezone, communication_style) live
    // in the agent's identity JSON, so we read them off the agent.
    if let Some(a) = default_agent {
        append_android_identity_extras(&mut system_prompt, &a.identity);
    }

    let history = vec![ChatMessage::system(&system_prompt)];

    let session = Session {
        history,
        config,
        system_prompt,
        model,
        temperature,
        provider_name,
        tools_registry,
    };

    let mut guard = lock_session();

    if guard.is_some() {
        return Err(FfiError::StateError {
            detail: "a session is already active; destroy it first".into(),
        });
    }

    *guard = Some(session);

    tracing::info!("Live agent session started");
    Ok(())
}

/// Maximum number of images per session send request.
const MAX_SESSION_IMAGES: usize = 5;

crate::ffi_export!(
    /// Sends a message through the live agent session's tool-call loop.
    ///
    /// Runs the full agent loop with memory recall, tool execution,
    /// streaming progress, and auto-compaction. Events are delivered to
    /// the listener callback in real time. The send can be cancelled by
    /// calling `session_cancel`.
    ///
    /// Images are optional. When provided, each entry in `image_data` is
    /// a base64-encoded image and `mime_types` holds the corresponding
    /// MIME type (e.g. `image/jpeg`). The images are embedded as
    /// `[IMAGE:...]` markers in the user message so the upstream
    /// provider can convert them to multimodal content parts.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::EstopEngaged`] when emergency stop is
    /// active, [`crate::FfiError::ConfigError`] for oversized messages
    /// or mismatched image arrays, [`crate::FfiError::StateError`] if no
    /// session is active, [`crate::FfiError::StateCorrupted`] if the
    /// session mutex is poisoned, [`crate::FfiError::SpawnError`] if the
    /// agent loop or provider creation fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn session_send(
        message: String,
        image_data: Vec<String>,
        mime_types: Vec<String>,
        listener: Box<dyn FfiSessionListener>
    ) -> () = session_send_boxed
);

crate::ffi_export!(
    /// Cancels the currently running `session_send` call.
    ///
    /// Sets the internal cancellation token. The agent loop aborts at
    /// the next check point and fires `on_cancelled()` on the listener.
    /// No-op if no send is in progress.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateCorrupted`] if the cancel token
    /// mutex is poisoned, or [`crate::FfiError::InternalPanic`] if
    /// native code panics.
    fn session_cancel() -> () = session_cancel_ffi
);

pub(crate) fn session_send_boxed(
    message: String,
    image_data: Vec<String>,
    mime_types: Vec<String>,
    listener: Box<dyn FfiSessionListener>,
) -> Result<(), FfiError> {
    if crate::estop::is_engaged() {
        return Err(FfiError::EstopEngaged {
            detail: "Emergency stop is engaged. Resume before sending messages.".into(),
        });
    }
    session_send_inner(message, image_data, mime_types, Arc::from(listener))
}

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn session_cancel_ffi() -> Result<(), FfiError> {
    session_cancel_inner();
    Ok(())
}

#[allow(clippy::too_many_lines)]
pub(crate) fn session_send_inner(
    message: String,
    image_data: Vec<String>,
    mime_types: Vec<String>,
    listener: Arc<dyn FfiSessionListener>,
) -> Result<(), FfiError> {
    // Validate image arrays before composing the message.
    if image_data.len() != mime_types.len() {
        return Err(FfiError::ConfigError {
            detail: format!(
                "image_data length ({}) != mime_types length ({})",
                image_data.len(),
                mime_types.len()
            ),
        });
    }
    if image_data.len() > MAX_SESSION_IMAGES {
        return Err(FfiError::ConfigError {
            detail: format!(
                "too many images ({}, max {MAX_SESSION_IMAGES})",
                image_data.len()
            ),
        });
    }

    // Capture raw user text for classification before multimodal markers are added.
    let raw_message_text = message.clone();
    // Compose the final message text, embedding image markers if present.
    let message = compose_multimodal_message(&message, &image_data, &mime_types);

    if message.len() > MAX_MESSAGE_BYTES {
        return Err(FfiError::ConfigError {
            detail: format!(
                "message too large ({} bytes, max {MAX_MESSAGE_BYTES})",
                message.len()
            ),
        });
    }

    // ── ClawBoy trigger intercept ────────────────────────────────
    // Check if this message triggers a ClawBoy start/stop BEFORE
    // entering the agent loop. If matched, short-circuit with the
    // trigger response — the agent never sees the message.
    {
        let trigger_result = crate::clawboy::chat::check_trigger(
            &message, "cli", // Terminal REPL is always the "cli" channel
        );
        match trigger_result {
            crate::clawboy::chat::TriggerResult::StartResponse(response)
            | crate::clawboy::chat::TriggerResult::StopResponse(response) => {
                tracing::info!(
                    target: "clawboy::chat",
                    channel = "cli",
                    "trigger matched in session_send — short-circuiting"
                );
                listener.on_response_chunk(response.clone());
                listener.on_complete(response);
                return Ok(());
            }
            crate::clawboy::chat::TriggerResult::PassThrough => {
                // Not a trigger — continue to normal agent processing.
            }
        }
    }

    tracing::info!(
        len = message.len(),
        images = image_data.len(),
        "session_send: start"
    );

    let cancel_token = CancellationToken::new();
    {
        let mut ct_guard = lock_cancel_token();
        *ct_guard = Some(cancel_token.clone());
    }

    // Snapshot session state while holding the lock briefly.
    // Wrap in a SessionStateGuard so that a panic during processing
    // automatically restores history + tools via Drop.
    let (mut state_guard, config, model, temperature, provider_name) = {
        let mut guard = lock_session();
        let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
            detail: "no active session; call session_start first".into(),
        })?;
        (
            SessionStateGuard::new(
                std::mem::take(&mut session.history),
                std::mem::take(&mut session.tools_registry),
            ),
            session.config.clone(),
            session.model.clone(),
            session.temperature,
            session.provider_name.clone(),
        )
    };

    let (history, tools) = state_guard.state_mut()?;
    let history_len_before = history.len();
    let handle = crate::runtime::get_or_create_runtime()?;

    // Clone the memory backend *before* entering block_on to avoid holding
    // the DAEMON mutex inside the async block, which could deadlock with a
    // concurrent stop_daemon call.
    let daemon_memory = clone_daemon_memory().ok();

    let result: Result<String, AgentLoopOutcome> = handle.block_on(async {
        // Build memory context (best-effort; skip if memory unavailable).
        let mem_context = match daemon_memory {
            Some(ref mem) => {
                listener.on_progress(FfiProgressPhase::SearchingMemory);
                build_memory_context(mem.as_ref(), &message).await
            }
            None => String::new(),
        };

        // Enrich the user message with memory context and timestamp.
        let timestamp = chrono::Utc::now().format("%Y-%m-%d %H:%M UTC");
        let enriched = if mem_context.is_empty() {
            format!("[{timestamp}] {message}")
        } else {
            format!("{mem_context}[{timestamp}] {message}")
        };

        history.push(ChatMessage::user(enriched));

        // Create provider.
        //
        // Upstream removed `Config.api_url`, `Config.api_key`,
        // `Config.model_providers`, and `Config.routing` from the
        // top-level Config, and dropped `custom_headers` from
        // `ModelProviderRuntimeOptions`. Per-provider auth + endpoints
        // now live exclusively under `[providers.models.<type>.<alias>]`
        // — the provider factory reads them on construction. The
        // routing classifier is bypassed (no fallback chain) until
        // upstream restores a router config.
        //
        let _ = &raw_message_text; // router classification dropped during port

        // Build the chat provider via the shared helper, which threads the
        // alias's api_key + runtime options (URI, reasoning, secrets) so
        // auth'd self-hosted endpoints get their `Authorization: Bearer`.
        // See crate::runtime::build_active_provider — one path shared by the
        // agent loop, compaction, and streaming so the None-credential 401
        // class can't recur per call site.
        let provider = crate::runtime::build_active_provider(&config, &provider_name)
            .map_err(|e| AgentLoopOutcome::Error(format!("failed to create provider: {e}")))?;

        // Build tool specs from the real tools registry plus static
        // descriptions for tools the LLM should know about.
        let mut tool_specs = tool_specs_from_registry(tools);
        for spec in build_android_tool_specs(&config) {
            if !tool_specs.iter().any(|s| s.name == spec.name) {
                tool_specs.push(spec);
            }
        }

        // Run the agent loop with real tool execution.
        run_agent_loop(
            provider.as_ref(),
            history,
            tools,
            &tool_specs,
            &model,
            temperature,
            &cancel_token,
            &listener,
        )
        .await
    });

    // Consume the guard (disarms Drop) and put state back explicitly.
    // If we reach this point, no panic occurred, so we handle all
    // three outcomes and restore state ourselves.
    let (mut history, tools) = state_guard.take()?;

    match result {
        Ok(full_response) => {
            tracing::info!(len = full_response.len(), "session_send: success");
            // Run compaction on the history (best-effort).
            if let Ok(true) = handle.block_on(async {
                // Compaction reuses the same shared provider builder so it
                // reaches the configured endpoint WITH its api_key. Best-
                // effort: .ok() skips compaction if the build fails.
                let provider = crate::runtime::build_active_provider(&config, &provider_name).ok();
                if let Some(provider) = provider {
                    auto_compact_history(
                        &mut history,
                        provider.as_ref(),
                        &model,
                        DEFAULT_MAX_HISTORY_MESSAGES,
                    )
                    .await
                } else {
                    Ok(false)
                }
            }) {
                // Find the compaction summary (most recent assistant message
                // that starts with "[Compaction summary]").
                if let Some(summary_msg) = history.iter().rev().find(|m| {
                    m.role == "assistant" && m.content.starts_with("[Compaction summary]")
                }) {
                    listener.on_progress(FfiProgressPhase::Compacting);
                    listener.on_compaction(summary_msg.content.clone());
                }
            }

            put_session_state_back(history, tools);
            clear_cancel_token();
            listener.on_progress(FfiProgressPhase::Idle);
            listener.on_complete(full_response);
            Ok(())
        }
        Err(AgentLoopOutcome::Cancelled) => {
            tracing::info!("session_send: cancelled");
            put_session_state_back(history, tools);
            clear_cancel_token();
            listener.on_progress(FfiProgressPhase::Idle);
            listener.on_cancelled();
            Ok(())
        }
        Err(AgentLoopOutcome::Error(msg)) => {
            tracing::error!(error = %msg, "session_send: agent loop error");
            // Rollback history to pre-send state.
            history.truncate(history_len_before);
            put_session_state_back(history, tools);
            clear_cancel_token();
            listener.on_progress(FfiProgressPhase::Idle);
            listener.on_error(msg.clone());
            Err(FfiError::SpawnError { detail: msg })
        }
    }
}

/// Injects seed messages into the active session's conversation history.
///
/// Used to restore prior context (e.g. from Room persistence) before the
/// first [`session_send_inner`] call. Messages are appended after the
/// system prompt in the order provided.
///
/// At most [`MAX_SEED_MESSAGES`] entries are accepted. The `role` field of
/// each [`SessionMessage`] must be `"user"` or `"assistant"`; system
/// messages are silently skipped to prevent system prompt corruption.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_seed_inner(messages: Vec<SessionMessage>) -> Result<(), FfiError> {
    let mut guard = lock_session();
    let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "no active session; call session_start first".into(),
    })?;

    let capped = if messages.len() > MAX_SEED_MESSAGES {
        tracing::warn!(
            count = messages.len(),
            max = MAX_SEED_MESSAGES,
            "Seed messages capped"
        );
        &messages[..MAX_SEED_MESSAGES]
    } else {
        &messages
    };

    for msg in capped {
        match msg.role.as_str() {
            "user" => session.history.push(ChatMessage::user(&msg.content)),
            "assistant" => session.history.push(ChatMessage::assistant(&msg.content)),
            "tool" => session.history.push(ChatMessage::tool(&msg.content)),
            _ => {
                // Skip system messages to protect the system prompt.
                tracing::debug!(role = %msg.role, "Skipping seed message with reserved role");
            }
        }
    }

    tracing::info!(count = capped.len(), "Seeded session history");
    Ok(())
}

/// Cancels the currently running [`session_send_inner`] call.
///
/// Sets the [`CANCEL_TOKEN`] to cancelled state. The agent loop checks
/// this token between iterations and tool executions, aborting with an
/// [`AgentLoopOutcome::Cancelled`] result. If no send is in progress,
/// this is a no-op.
pub(crate) fn session_cancel_inner() {
    let guard = lock_cancel_token();
    if let Some(token) = guard.as_ref() {
        token.cancel();
        tracing::info!("Session send cancelled");
    }
}

/// Clears the active session's conversation history, retaining only the
/// system prompt.
///
/// After this call the session behaves as if freshly started -- the
/// system prompt is preserved but all user/assistant/tool messages are
/// discarded.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_clear_inner() -> Result<(), FfiError> {
    let mut guard = lock_session();
    let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "no active session; call session_start first".into(),
    })?;

    let system_prompt = session.system_prompt.clone();
    session.history = vec![ChatMessage::system(&system_prompt)];

    tracing::info!("Session history cleared");
    Ok(())
}

/// Returns the current conversation history as a list of [`SessionMessage`]
/// records suitable for transfer across the FFI boundary.
///
/// The returned list includes the system prompt (role `"system"`) as the
/// first entry, followed by user, assistant, and tool messages in
/// chronological order.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_history_inner() -> Result<Vec<SessionMessage>, FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no active session; call session_start first".into(),
    })?;

    let messages = session
        .history
        .iter()
        .map(|m| SessionMessage {
            role: m.role.clone(),
            content: m.content.clone(),
        })
        .collect();

    Ok(messages)
}

/// Destroys the active session and releases all associated resources.
///
/// After this call, a new session may be created with
/// [`session_start_inner`]. Any in-flight [`session_send_inner`] call is
/// cancelled first via the [`CANCEL_TOKEN`].
///
/// # Thread Safety
///
/// The cancel and destroy operations are not atomic: the cancel token
/// is in a separate mutex from the session state. This is safe because
/// the Kotlin layer serialises all session lifecycle calls on a single
/// `Dispatchers.IO` coroutine (no concurrent callers).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active.
pub(crate) fn session_destroy_inner() -> Result<(), FfiError> {
    // Cancel any in-flight send (separate mutex, no deadlock).
    session_cancel_inner();

    let mut guard = lock_session();
    if guard.take().is_none() {
        return Err(FfiError::StateError {
            detail: "no active session to destroy".into(),
        });
    }

    tracing::info!("Live agent session destroyed");
    Ok(())
}

// ── Agent loop ──────────────────────────────────────────────────────────

/// Outcome categories for the agent loop, used internally to distinguish
/// success, cancellation, and errors without mixing them into `FfiError`.
pub(crate) enum AgentLoopOutcome {
    /// The send was cancelled via [`CANCEL_TOKEN`].
    Cancelled,
    /// An unrecoverable error occurred during the loop.
    Error(String),
}

/// Runs the agent tool-call loop until the LLM produces a final text
/// response, the maximum iteration count is reached, or cancellation
/// is signalled.
///
/// For each iteration:
/// 1. Check the cancellation token.
/// 2. Fire `on_thinking` / `on_progress` via the listener.
/// 3. Call `provider.chat(...)` with the current history and tool specs.
/// 4. If no tool calls: stream the final response, append to history, return.
/// 5. If tool calls: execute tools that exist in the registry and report
///    results; tools not in the registry get a fallback "unavailable" message.
///
/// Tools with real implementations (memory, cron, web search) are executed
/// directly. Tools that require upstream's `pub(crate)` `SecurityPolicy`
/// (shell, file I/O, git, browser) are not in the registry and receive
/// an unavailability response so the LLM can answer without them.
///
/// The function returns the full response text on success, or an
/// [`AgentLoopOutcome`] on failure/cancellation.
#[allow(clippy::too_many_arguments, clippy::too_many_lines)]
async fn run_agent_loop(
    provider: &dyn ModelProvider,
    history: &mut Vec<ChatMessage>,
    tools: &[Box<dyn Tool>],
    tool_specs: &[ToolSpec],
    model: &str,
    temperature: f64,
    cancel_token: &CancellationToken,
    listener: &Arc<dyn FfiSessionListener>,
) -> Result<String, AgentLoopOutcome> {
    let use_native_tools = provider.supports_native_tools() && !tool_specs.is_empty();
    let request_tools = if use_native_tools {
        Some(tool_specs)
    } else {
        None
    };

    tracing::info!(
        native_tools = use_native_tools,
        specs = tool_specs.len(),
        "agent_loop: start"
    );

    for iteration in 0..DEFAULT_MAX_TOOL_ITERATIONS {
        // Check cancellation before each iteration.
        if cancel_token.is_cancelled() {
            return Err(AgentLoopOutcome::Cancelled);
        }

        // Progress: calling provider.
        #[allow(clippy::cast_possible_truncation)] // iteration ≤ DEFAULT_MAX_TOOL_ITERATIONS (10)
        listener.on_progress(FfiProgressPhase::CallingProvider {
            round: (iteration + 1) as u32,
        });
        tracing::info!(
            iteration = iteration + 1,
            model,
            "agent_loop: calling provider"
        );

        // Call the provider.
        let llm_start_time = std::time::Instant::now();
        let chat_future = provider.chat(
            ChatRequest {
                messages: history,
                tools: request_tools,
                thinking: None,
            },
            model,
            Some(temperature),
        );

        let chat_result = tokio::select! {
            () = cancel_token.cancelled() => return Err(AgentLoopOutcome::Cancelled),
            result = chat_future => result,
        };

        let mut response = chat_result.map_err(|e| {
            tracing::error!(error = %e, "agent_loop: provider chat failed");
            AgentLoopOutcome::Error(format!("provider chat failed: {e}"))
        })?;

        tracing::info!(
            tool_calls = response.tool_calls.len(),
            text_len = response.text_or_empty().len(),
            has_reasoning = response.reasoning_content.is_some(),
            elapsed_secs = llm_start_time.elapsed().as_secs(),
            "agent_loop: provider responded"
        );

        // No tool calls -- check for prompt-guided XML tool calls before
        // treating as a final response.
        if response.tool_calls.is_empty() {
            let raw_text = response.text_or_empty().to_string();

            // Forward API-level reasoning_content (o1, o3) to the
            // thinking card.
            if let Some(ref reasoning) = response.reasoning_content
                && !reasoning.is_empty()
            {
                listener.on_thinking(reasoning.clone());
            }

            // Extract inline thinking tags (DeepSeek-R1, Qwen, etc.)
            // from the text field and route them to the thinking card
            // so they don't appear as the visible response.
            let (clean_text, inline_thinking) = extract_thinking_from_text(&raw_text);
            if !inline_thinking.is_empty() {
                tracing::info!(
                    thinking_len = inline_thinking.len(),
                    "agent_loop: extracted inline thinking"
                );
                listener.on_thinking(inline_thinking);
            }

            // Parse <tool_call> XML tags from the response text as a
            // fallback. This is always attempted when the provider returned
            // no native tool_calls, even if native tools were offered in
            // the request. Some models (Qwen, GLM distills, small Ollama
            // models) receive the native tool schema but still emit tool
            // invocations as XML text instead of structured tool_calls.
            // Without this fallback those models spiral — they see tools
            // in the prompt, fail to invoke them natively, and retry the
            // same text-based attempt every turn.
            {
                let (text_without_calls, mut xml_calls) = parse_xml_tool_calls(&clean_text);
                if !xml_calls.is_empty() {
                    if use_native_tools {
                        tracing::warn!(
                            count = xml_calls.len(),
                            "agent_loop: model emitted XML tool calls despite native tools; \
                             falling back to prompt-guided parsing"
                        );
                    }

                    // Filter out tool names not in the registry to prevent
                    // prompt-injection from invoking arbitrary tool names.
                    let before = xml_calls.len();
                    xml_calls.retain(|c| tools.iter().any(|t| t.name() == c.name));
                    if xml_calls.len() < before {
                        tracing::warn!(
                            dropped = before - xml_calls.len(),
                            "agent_loop: filtered unrecognised XML tool calls"
                        );
                    }
                    tracing::info!(
                        count = xml_calls.len(),
                        "agent_loop: parsed prompt-guided <tool_call> tags"
                    );

                    // Promote the parsed XML calls into the response so
                    // the existing tool-dispatch logic handles them.
                    response.tool_calls = xml_calls;

                    // Replace text with the cleaned version (tags removed)
                    // so the assistant history doesn't contain raw XML.
                    response.text = Some(text_without_calls);

                    // Fall through to the tool-call execution block below.
                }
            }

            // If still no tool calls after XML parsing, this is truly
            // the final response.
            if response.tool_calls.is_empty() {
                tracing::info!(
                    raw_len = raw_text.len(),
                    clean_len = clean_text.len(),
                    "agent_loop: final response"
                );

                // Signal that we are now streaming the final response.
                listener.on_progress(FfiProgressPhase::StreamingResponse);
                listener.on_progress_clear();

                // Stream the cleaned response (thinking blocks removed).
                stream_response_text(&clean_text, listener, cancel_token)?;

                // Keep the full raw text in history so the model retains
                // its own reasoning context for follow-up turns.
                history.push(ChatMessage::assistant(&raw_text));
                return Ok(clean_text);
            }
        }

        // Has tool calls -- execute those we have and report unavailable
        // for the rest.
        //
        // Forward API-level reasoning_content and inline thinking tags
        // to the thinking card.
        if let Some(ref reasoning) = response.reasoning_content
            && !reasoning.is_empty()
        {
            listener.on_thinking(reasoning.clone());
        }

        let raw_assistant = response.text_or_empty().to_string();
        let (_clean_assistant, inline_thinking) = extract_thinking_from_text(&raw_assistant);
        if !inline_thinking.is_empty() {
            listener.on_thinking(inline_thinking);
        }

        // Cap excessive tool calls from a single response.
        if response.tool_calls.len() > MAX_TOOL_CALLS_PER_RESPONSE {
            tracing::warn!(
                total = response.tool_calls.len(),
                limit = MAX_TOOL_CALLS_PER_RESPONSE,
                "agent_loop: capping tool calls per response"
            );
            response.tool_calls.truncate(MAX_TOOL_CALLS_PER_RESPONSE);
        }

        let tool_call_count = response.tool_calls.len();
        listener.on_progress(FfiProgressPhase::GotToolCalls {
            count: u32::try_from(tool_call_count).unwrap_or(u32::MAX),
            llm_duration_secs: llm_start_time.elapsed().as_secs(),
        });

        // Push assistant message with tool calls context.
        // Keep raw text (with thinking tags) so the model retains its
        // reasoning context for subsequent iterations.
        let assistant_text = raw_assistant;
        if use_native_tools {
            let native_history = build_native_assistant_history(
                &assistant_text,
                &response.tool_calls,
                response.reasoning_content.as_deref(),
            );
            history.push(ChatMessage::assistant(native_history));
        } else {
            history.push(ChatMessage::assistant(&assistant_text));
        }

        // Execute or respond to each tool call.
        let mut tool_results_text = String::new();

        for call in &response.tool_calls {
            if cancel_token.is_cancelled() {
                return Err(AgentLoopOutcome::Cancelled);
            }

            let args_hint = truncate_tool_args_hint(&call.name, &call.arguments);
            listener.on_tool_start(call.name.clone(), args_hint);
            tracing::info!(tool = %call.name, "agent_loop: tool start");

            let start_time = std::time::Instant::now();

            // Find the tool by name in the registry.
            let tool = tools.iter().find(|t| t.name() == call.name);

            let (success, output) = if let Some(tool) = tool {
                let args: serde_json::Value =
                    serde_json::from_str(&call.arguments).unwrap_or(serde_json::json!({}));

                let exec_result = tokio::select! {
                    () = cancel_token.cancelled() => {
                        return Err(AgentLoopOutcome::Cancelled);
                    }
                    result = tool.execute(args) => result,
                };

                match exec_result {
                    Ok(result) => {
                        if result.success {
                            (true, result.output)
                        } else {
                            (
                                false,
                                result
                                    .error
                                    .unwrap_or_else(|| "Tool failed without error message".into()),
                            )
                        }
                    }
                    Err(e) => (false, format!("Tool execution error: {e}")),
                }
            } else {
                tracing::warn!(tool = %call.name, "agent_loop: tool not found in registry");
                (
                    false,
                    format!(
                        "Tool '{}' is not available in this session. \
                         Please answer directly without this tool.",
                        call.name
                    ),
                )
            };

            let duration_secs = start_time.elapsed().as_secs();
            let output_preview: String = output.chars().take(200).collect();
            tracing::info!(
                tool = %call.name,
                success,
                duration_secs,
                output_len = output.len(),
                output_preview,
                "agent_loop: tool done"
            );
            listener.on_tool_result(call.name.clone(), success, duration_secs);
            listener.on_tool_output(call.name.clone(), output.clone());

            if use_native_tools {
                let tool_msg = serde_json::json!({
                    "tool_call_id": call.id,
                    "content": output,
                });
                history.push(ChatMessage::tool(tool_msg.to_string()));
            } else {
                let _ = writeln!(
                    tool_results_text,
                    "<tool_result name=\"{}\">\n{output}\n</tool_result>",
                    call.name
                );
            }
        }

        // For prompt-guided mode, append collected tool results as a user message.
        if !use_native_tools && !tool_results_text.is_empty() {
            history.push(ChatMessage::user(format!(
                "[Tool results]\n{tool_results_text}"
            )));
        }
    }

    Err(AgentLoopOutcome::Error(format!(
        "Agent exceeded maximum tool iterations ({DEFAULT_MAX_TOOL_ITERATIONS})"
    )))
}

// ── Helpers ─────────────────────────────────────────────────────────────

/// Streams text to the listener in chunks of at least
/// [`STREAM_CHUNK_MIN_CHARS`] characters, split on whitespace boundaries.
fn stream_response_text(
    text: &str,
    listener: &Arc<dyn FfiSessionListener>,
    cancel_token: &CancellationToken,
) -> Result<(), AgentLoopOutcome> {
    let mut chunk = String::new();
    for word in text.split_inclusive(char::is_whitespace) {
        if cancel_token.is_cancelled() {
            return Err(AgentLoopOutcome::Cancelled);
        }
        chunk.push_str(word);
        if chunk.len() >= STREAM_CHUNK_MIN_CHARS {
            listener.on_response_chunk(std::mem::take(&mut chunk));
        }
    }
    if !chunk.is_empty() {
        listener.on_response_chunk(chunk);
    }
    Ok(())
}

/// Builds a JSON-structured assistant history entry for native tool calling mode.
///
/// Preserves tool call IDs so subsequent `role=tool` messages can reference
/// the correct call. Also preserves `reasoning_content` from thinking models.
fn build_native_assistant_history(
    text: &str,
    tool_calls: &[zeroclaw::providers::ToolCall],
    reasoning_content: Option<&str>,
) -> String {
    // Each tool call entry carries both the flat ToolCall fields (id, name,
    // arguments) for the Anthropic provider's `parse_assistant_tool_call_message`
    // and the nested `function` object for the OpenAI provider's
    // `convert_messages`. Both providers attempt deserialization as
    // `Vec<ToolCall>` (id/name/arguments), while OpenAI also reads the
    // `function` wrapper. Serde's default mode ignores unknown fields, so
    // the extra keys are harmless in both directions.
    let calls_json: Vec<serde_json::Value> = tool_calls
        .iter()
        .map(|tc| {
            serde_json::json!({
                "id": tc.id,
                "name": tc.name,
                "arguments": tc.arguments,
                "type": "function",
                "function": {
                    "name": tc.name,
                    "arguments": tc.arguments,
                },
            })
        })
        .collect();

    let mut msg = serde_json::json!({
        "content": text,
        "tool_calls": calls_json,
    });

    if let Some(rc) = reasoning_content {
        msg["reasoning_content"] = serde_json::Value::String(rc.to_string());
    }

    msg.to_string()
}

/// Puts the working history and tools registry back into the [`SESSION`] mutex.
///
/// If the session was destroyed while the send was in progress, the
/// state is silently dropped (the session slot will be `None`).
fn put_session_state_back(history: Vec<ChatMessage>, tools: Vec<Box<dyn Tool>>) {
    let mut guard = lock_session();
    if let Some(session) = guard.as_mut() {
        session.history = history;
        session.tools_registry = tools;
    }
}

/// Clears the global [`CANCEL_TOKEN`].
fn clear_cancel_token() {
    *lock_cancel_token() = None;
}




#[cfg(test)]
#[path = "session_tests.rs"]
mod tests;
