/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.zeroclaw.android.data.OnDeviceLargeAgent
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.model.LiteRtModel
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Snapshot of the on-device large-model inference engine state.
 *
 * Drives both the picker UI's "Loaded in daemon" indicator and the
 * Terminal routing decision (only [Loaded] entries are eligible for
 * local inference). Distinct from [com.zeroclaw.android.model.LiteRtModelStatus],
 * which tracks per-variant disk presence.
 */
sealed interface OnDeviceInferenceState {
    /**
     * No engine work pending — either the user hasn't enabled the
     * on-device-large agent, the model file isn't downloaded, or
     * the daemon is stopped.
     */
    data object Idle : OnDeviceInferenceState

    /** Daemon started, engine load is in flight against [model]. */
    data class Loading(
        val model: LiteRtModel,
    ) : OnDeviceInferenceState

    /**
     * Engine ready to accept prompts.
     *
     * @property model Variant currently loaded and serving completions.
     */
    data class Loaded(
        val model: LiteRtModel,
    ) : OnDeviceInferenceState

    /**
     * Engine load failed (e.g. GPU init refused, file corrupted).
     *
     * @property model Variant we attempted to load.
     * @property reason User-facing failure description.
     */
    data class Failed(
        val model: LiteRtModel,
        val reason: String,
    ) : OnDeviceInferenceState
}

/**
 * Application-scoped coordinator that owns the LiteRT-LM engine
 * lifecycle. Single-driver architecture:
 *
 *  - All loads originate from [awaitReadyForActiveAgent], invoked
 *    by `ZeroAIDaemonService.handleStartFromSettings` before TOML
 *    emission. That call serialises against everything else via
 *    [mutex] and is the only place the engine is brought up.
 *  - Auto-unload runs reactively when the active agent toggles
 *    off, the model file disappears, or the user switches variants
 *    while the engine is hot. Reconciler is unload-only — it never
 *    re-loads. Service-scoped teardown is handled by [shutdown].
 *
 * Routing entry points for Terminal + Rust daemon providers:
 *  - [send] streams response text when [state] is `Loaded`.
 *  - [serveCompletion] services one OpenAI-compatible chat request
 *    against a freshly-built stateless `Conversation`. The loopback
 *    HTTP server in [LiteRtHttpServer] funnels requests here.
 *
 * Singleton per process — owned by `ZeroAIApplication`.
 *
 * @param context Application context for [LiteRtModelStore].
 * @param agentRepository Source of the active-agent flag.
 * @param scope Long-lived coroutine scope (typically the
 *   application's `applicationScope`).
 */
class OnDeviceInferenceManager(
    context: Context,
    private val agentRepository: AgentRepository,
    private val scope: CoroutineScope,
) {
    private val modelStore = LiteRtModelStore(context)
    private val inference = LiteRtGemmaInference()

    /**
     * Shared secret the loopback HTTP server requires on every
     * inbound request. Generated fresh per process start so a
     * stale token from a previous daemon session is rejected.
     *
     * Without auth, any app on the device with `INTERNET`
     * permission could reach `127.0.0.1:11434` and pump prompts
     * through the on-device model (battery / GPU / RAM DoS at
     * minimum; tool-call SSRF at worst). Loopback is not
     * isolated per-app on Android.
     *
     * The Rust daemon receives this secret through its TOML
     * `api_key` field on each daemon-start / hot-reload — see
     * [getLocalAuthToken].
     */
    private val localAuthToken: String =
        java.security
            .SecureRandom()
            .let { rng ->
                ByteArray(LOCAL_AUTH_TOKEN_BYTES).also(rng::nextBytes)
            }.joinToString("") { "%02x".format(it) }

    /**
     * Loopback HTTP server exposing the loaded engine as an OpenAI-
     * compatible `/v1/chat/completions` endpoint. Started when the
     * engine transitions to [OnDeviceInferenceState.Loaded] and
     * stopped on every transition out. The Rust daemon configures
     * `http://127.0.0.1:11434` as a provider when the on-device-large
     * agent is active, so chat channels route through it transparently.
     */
    private val httpServer =
        LiteRtHttpServer(
            serveCompletion = ::serveCompletion,
            modelName = {
                (state.value as? OnDeviceInferenceState.Loaded)?.model?.id ?: "on-device"
            },
            requiredAuthToken = { localAuthToken },
        )

    /**
     * Returns the shared secret callers must place in their
     * `Authorization: Bearer ...` header (or in the OpenAI-compat
     * `api_key` config field) to reach the local inference
     * server. Only used by the daemon-start path that constructs
     * the routing TOML override.
     */
    fun getLocalAuthToken(): String = localAuthToken

    /**
     * Single-thread dispatcher reserved for LiteRT-LM engine ops
     * (load / unload / reset). Engine init mmaps a multi-GB file
     * and initialises the GPU delegate for 2-6 s — running that on
     * the shared [kotlinx.coroutines.Dispatchers.IO] pool would
     * steal one of its 64 slots from every Room/SAF/HTTP caller
     * during boot. The dedicated thread also serialises load and
     * unload so a stop hook can't race a fresh load.
     */
    private val engineDispatcher =
        Executors
            .newSingleThreadExecutor { runnable ->
                Thread(runnable, "OnDeviceInference").apply { isDaemon = true }
            }.asCoroutineDispatcher()

    /**
     * Serialises engine load / unload against in-flight [send]
     * calls so the daemon stop hook can't yank the engine out from
     * under a streaming prompt. Inference itself is single-threaded
     * by the model — there's at most one [send] outstanding.
     */
    private val mutex = Mutex()

    private val _state = MutableStateFlow<OnDeviceInferenceState>(OnDeviceInferenceState.Idle)

    /** Observable engine state. Drives picker UI + Terminal routing. */
    val state: StateFlow<OnDeviceInferenceState> = _state.asStateFlow()

    /** Active reconciler job, restarted on every observable input change. */
    private var reconcileJob: Job? = null

    /**
     * Wires up the auto-unload reconciler. Idempotent.
     *
     * Single-driver architecture: this watcher exists ONLY to tear
     * the engine down when conditions flip false (user disables the
     * agent, deletes the model file, stops the daemon) OR when the
     * user picks a different variant while the engine is hot.
     * Loads are NOT triggered here — every load is initiated by
     * [awaitReadyForActiveAgent], the explicit daemon-start entry
     * point.
     *
     * Earlier versions ran a two-driver model where this reconciler
     * also called `loadIfNeeded` when all conditions became true.
     * That raced the daemon-start path on the same mutex, produced a
     * reentrancy guard against itself when callers published
     * optimistic state, and silently masked load failures behind a
     * cloud-config fallback. Folding all load triggers into one
     * function deletes that whole class of bug — the reconciler is
     * now strictly subtractive (load → unload), never additive.
     */
    fun attach() {
        reconcileJob?.cancel()
        reconcileJob =
            combine(
                agentRepository.isAgentEnabled(OnDeviceLargeAgent.ID),
                modelStore.selectedModel,
            ) { enabled, model ->
                Conditions(
                    enabled = enabled,
                    model = model,
                    fileReady = modelStore.isDownloaded(model),
                )
            }.distinctUntilChanged()
                .onEach { conditions -> autoUnloadIfStale(conditions) }
                .launchIn(scope)
    }

    /**
     * Streams a response from the loaded engine, holding the
     * lifecycle mutex for the duration of the call so a daemon stop
     * or model swap can't yank the conversation out from under us
     * mid-generation.
     *
     * Returns an empty flow when no engine is loadable (idle,
     * failed, or not-downloaded states) instead of throwing — the
     * caller (Terminal routing layer) treats an empty stream as
     * "fall through to the next route" rather than surfacing an
     * internal error message.
     *
     * @param prompt User message text.
     * @return Cold [Flow] of token-delta strings.
     */
    fun send(prompt: String): Flow<String> =
        kotlinx.coroutines.flow
            .flow {
                mutex.withLock {
                    if (_state.value !is OnDeviceInferenceState.Loaded) return@withLock
                    inference.send(prompt).collect { chunk -> emit(chunk) }
                }
            }

    /**
     * Stateless completion endpoint used by [LiteRtHttpServer] to
     * service one OpenAI `/v1/chat/completions` request.
     *
     * Builds a fresh [com.google.ai.edge.litertlm.Conversation] per
     * call so the request's full message history + tool definitions
     * are honoured exactly as sent — matches OpenAI's stateless
     * server semantics rather than the Terminal-style persistent
     * conversation. The mutex serialises calls so two HTTP clients
     * can't compete for the single underlying engine.
     *
     * Emits [OpenAiResponseEvent.TextDelta] entries while the model
     * produces text; emits [OpenAiResponseEvent.ToolCallsEmitted]
     * the first time the model decides to invoke a tool, and stops.
     * The HTTP server translates these into OpenAI's `delta` and
     * `tool_calls` response shapes.
     *
     * @param request The full OpenAI request, including `messages`,
     *   `tools`, and `stream` flag (used by the server, not us).
     * @return Cold [Flow] of events terminating in either
     *   `TextDelta`s or one `ToolCallsEmitted` or a `Failed`.
     */
    @Suppress(
        "TooGenericExceptionCaught",
        "LongMethod",
        "CyclomaticComplexMethod",
        "CognitiveComplexMethod",
    )
    fun serveCompletion(request: OpenAiChatRequest): Flow<OpenAiResponseEvent> =
        kotlinx.coroutines.flow.flow {
            // Decide what to send to the engine and what to retain
            // as conversation history. Two distinct cases the OpenAI
            // chat-completion protocol expresses through the SAME
            // `messages` array:
            //
            //  1) Fresh user turn: messages ends with role:"user".
            //     The new stimulus is that user message; history is
            //     everything before it.
            //
            //  2) Agent-loop continuation: messages ends with role:
            //     "tool" (the daemon dispatched a tool from the
            //     prior assistant turn and is feeding the result
            //     back). The new stimulus is the tool result; the
            //     entire prior chain (system + user + assistant
            //     tool_calls + earlier tool results) is history.
            //
            // Earlier versions used `dropLast(1)` + `lastUserPrompt`
            // unconditionally, which silently DROPPED the most recent
            // tool result on every continuation and re-sent the
            // original user message instead. The model interpreted
            // the re-asking as "they didn't get what they wanted",
            // tried another search, and looped indefinitely without
            // ever seeing its own prior search results — until the
            // agent-loop iteration cap killed it with an empty final
            // response. That was the real cause of the
            // 3-web-searches-then-empty failure mode.
            val lastMessage = request.messages.lastOrNull()
            if (lastMessage == null) {
                emit(OpenAiResponseEvent.Failed("Request has no messages"))
                return@flow
            }
            val isToolContinuation = lastMessage.role == "tool"
            val newStimulus = lastMessage.textContent
            if (newStimulus.isBlank()) {
                emit(
                    OpenAiResponseEvent.Failed(
                        if (isToolContinuation) {
                            "Tool result message has empty content"
                        } else {
                            "Request has no user message"
                        },
                    ),
                )
                return@flow
            }
            if (com.zeroclaw.android.BuildConfig.DEBUG) {
                // Conversation content + tool results may include
                // user PII (residence/hometown via AIEOS), recalled
                // memories, or secrets passed as tool args. Debug-
                // only so production logcat doesn't expose them.
                Log.d(
                    TAG,
                    "serveCompletion: ${if (isToolContinuation) "tool result" else "user prompt"} " +
                        "(${newStimulus.length} chars): ${newStimulus.take(LOG_PREVIEW_CHARS)}",
                )
            }
            // Apply Gemma-4-specific steering: when the user message
            // carries an <image> block, prepend a terse instruction
            // that biases the model toward describing rather than
            // tool-calling. Gated by `isToolContinuation` because
            // tool-result turns aren't user prompts and the hint
            // would just confuse a synthesis pass.
            val finalStimulus =
                if (!isToolContinuation) {
                    GemmaResponseHarness.applyImageIntentHint(newStimulus)
                } else {
                    newStimulus
                }
            val priorMessages =
                request.messages
                    .dropLast(1)
                    .map(::openAiMessageToLiteRt)
            val tools = openAiToolsToLiteRt(request.tools)
            // Acquire the engine, queueing if a previous request is
            // still generating. LiteRT-LM enforces one conversation per
            // engine, so concurrent requests cannot share — and our
            // sole HTTP client is the local daemon, which already
            // retries on its own. Fast-failing here just turns the
            // daemon's retry into a "busy" → 500 → retry → 500 storm
            // while the original generation is still in flight; better
            // to serialize and let the daemon see one result per call.
            mutex.lock()
            try {
                if (_state.value !is OnDeviceInferenceState.Loaded) {
                    emit(OpenAiResponseEvent.Failed("Engine not loaded"))
                    return@flow
                }
                val conversation =
                    inference.createStatelessConversation(priorMessages, tools)
                        ?: run {
                            emit(OpenAiResponseEvent.Failed("Engine refused conversation creation"))
                            return@flow
                        }
                conversation.use { conv ->
                    var toolCallSeen = false
                    var replyTextEmitted = false
                    val replyTextBuffer = StringBuilder()
                    val loadedModelId =
                        (_state.value as? OnDeviceInferenceState.Loaded)?.model?.id.orEmpty()
                    try {
                        conv
                            .sendMessageAsync(finalStimulus, emptyMap<String, Any>())
                            .collect { msg ->
                                if (toolCallSeen) return@collect
                                if (msg.toolCalls.isNotEmpty()) {
                                    toolCallSeen = true
                                    val mapped = msg.toolCalls.map(::toOpenAiToolCall)
                                    if (com.zeroclaw.android.BuildConfig.DEBUG) {
                                        Log.d(
                                            TAG,
                                            "Model emitted ${mapped.size} tool call(s): " +
                                                mapped.joinToString { it.function.name },
                                        )
                                    }
                                    emit(
                                        OpenAiResponseEvent.ToolCallsEmitted(
                                            toolCalls = mapped,
                                        ),
                                    )
                                } else {
                                    val text =
                                        msg.contents.contents
                                            .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                                            .joinToString(separator = "") { it.text }
                                    if (text.isNotEmpty()) {
                                        if (com.zeroclaw.android.BuildConfig.DEBUG) {
                                            // Debug-only: model output may
                                            // echo back recalled memory
                                            // content or other PII.
                                            Log.d(
                                                TAG,
                                                "Model text delta (${text.length} chars): " +
                                                    text.take(LOG_PREVIEW_CHARS),
                                            )
                                        }
                                        replyTextBuffer.append(text)
                                    }
                                }
                            }
                        // On-device Gemma models echo the channel prompt's
                        // bracket scaffolding ([Memory context], [No reply
                        // sent: …], gate verdicts) and can degenerate into
                        // short-token repetition. Scrub the fully-assembled
                        // reply at the harness level before it leaves the
                        // engine. Buffered rather than streamed per-delta
                        // because the strip needs the whole text; on-device
                        // generation is slow enough that clean output beats
                        // live token streaming.
                        if (!toolCallSeen) {
                            val cleaned =
                                ReplyScrubber.scrubReplyText(
                                    loadedModelId,
                                    replyTextBuffer.toString(),
                                )
                            if (cleaned.isNotEmpty()) {
                                replyTextEmitted = true
                                emit(OpenAiResponseEvent.TextDelta(cleaned))
                            }
                        }
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        // Propagate cancellation cleanly so the HTTP
                        // collector tears down the connection without
                        // emitting a misleading Failed event.
                        throw cancellation
                    } catch (e: Throwable) {
                        // Engine raised mid-generation. Two paths:
                        //
                        //  1) For Gemma 4 specifically, this is most
                        //     often the strict FC parser rejecting
                        //     model output that's "close enough" —
                        //     plain `"..."` quotes instead of
                        //     `<|"|>...<|"|>`, namespaced names, etc.
                        //     The harness re-parses the raw model
                        //     output and salvages structured tool
                        //     calls so the daemon's agent loop can
                        //     dispatch them rather than retry-storm.
                        //
                        //  2) For non-Gemma-4 engines or any other
                        //     error class, surface the verbatim
                        //     message — daemon's reliable layer
                        //     knows what to do with "Input token ids
                        //     are too long" and similar.
                        // Debug builds get the full exception (helpful
                        // when chasing tool-call format issues). Release
                        // builds get only the exception class name — the
                        // LiteRT-LM `Failed to parse tool calls from
                        // response: <RAW>…` message embeds verbatim
                        // model output, which on tool-loop continuations
                        // echoes user prompt fragments.
                        if (com.zeroclaw.android.BuildConfig.DEBUG) {
                            Log.w(TAG, "Engine failure during serveCompletion", e)
                        } else {
                            Log.w(
                                TAG,
                                "Engine failure during serveCompletion: " +
                                    (e::class.simpleName ?: "Throwable"),
                            )
                        }
                        val salvaged =
                            if (GemmaResponseHarness.appliesTo(loadedModelId)) {
                                GemmaResponseHarness.salvageToolCallsFromError(e)
                            } else {
                                null
                            }
                        if (salvaged != null && !toolCallSeen) {
                            toolCallSeen = true
                            emit(OpenAiResponseEvent.ToolCallsEmitted(toolCalls = salvaged))
                        } else {
                            emit(
                                OpenAiResponseEvent.Failed(
                                    reason = e.message ?: e::class.simpleName.orEmpty(),
                                ),
                            )
                        }
                    }
                    // Recovery pattern: empty response mid-tool-chain.
                    //
                    // E2B has a habit of going quiet on iteration N+1
                    // of a tool-call chain — it sees a successful
                    // tool result, decides "done", and emits only
                    // EOS without writing a confirmation. The daemon
                    // then sees tool_calls=0 + text_len=0 and the
                    // user gets a blank screen.
                    //
                    // Earlier version of this recovery did a SECOND
                    // inference pass with a synthesis nudge. That
                    // destabilised the HTTP stream — creating a new
                    // Conversation immediately after `use {}` closed
                    // the prior one occasionally raced LiteRT-LM's
                    // internal teardown, the SSE write hit a half-
                    // closed engine, and the daemon saw "connection
                    // closed before message completed" → 3-retry
                    // cascade fail. Worse than the empty response we
                    // were trying to fix.
                    //
                    // Pivot: emit a static acknowledgment instead.
                    // The model did its job (the tool succeeded);
                    // we're just papering over its failure to write
                    // the confirmation prose. Daemon sees text,
                    // user sees text, stream completes cleanly. The
                    // text is generic — but a generic "Done." is a
                    // much better user experience than silence.
                    @Suppress("ComplexCondition")
                    val shouldEmitMidChainFallback =
                        !toolCallSeen &&
                            !replyTextEmitted &&
                            GemmaResponseHarness.appliesTo(loadedModelId) &&
                            GemmaResponseHarness.isMidToolChain(request.messages)
                    if (shouldEmitMidChainFallback) {
                        val fallback =
                            GemmaResponseHarness.selectEmptyFallback(request.messages)
                        Log.i(
                            TAG,
                            "Empty response mid-tool-chain detected; emitting fallback: $fallback",
                        )
                        emit(OpenAiResponseEvent.TextDelta(fallback))
                    }
                }
            } finally {
                // Release the engine lease whether we exited cleanly,
                // via return@flow, or by an exception. Without the
                // finally, a thrown CancellationException would leak
                // the mutex and wedge every subsequent request behind
                // an unreachable lock holder.
                mutex.unlock()
            }
        }

    /**
     * Resets the multi-turn conversation history. Engine stays warm.
     * Called from the Terminal `/reset` path so the next prompt
     * starts a fresh dialog.
     */
    suspend fun resetConversation() {
        mutex.withLock {
            inference.resetConversation()
        }
    }

    /**
     * Outcome of a daemon-start engine readiness probe.
     *
     * The daemon needs to distinguish three cases that all looked
     * identical in the previous `Boolean` contract:
     *  - [NotConfigured]: the on-device-large agent isn't the active
     *    row, or the user hasn't downloaded its model file. Daemon
     *    silently writes its cloud TOML — this is expected.
     *  - [Ready]: engine loaded and the loopback HTTP server is up.
     *    Daemon writes the localhost-pointing TOML override.
     *  - [Failed]: load attempted but the engine crashed or refused
     *    GPU init. Daemon falls back to cloud TOML AND logs the
     *    reason so the user can see why on-device didn't kick in.
     *
     * The previous `Boolean` collapsed [NotConfigured] and [Failed]
     * into a silent `false`, hiding real engine failures behind a
     * "cloud is online" appearance. The Ollama-warmup-loop incident
     * was exactly that mode.
     */
    sealed interface EngineReadiness {
        /**
         * Active row isn't on-device-large or its model isn't on disk.
         * No load was attempted.
         */
        data object NotConfigured : EngineReadiness

        /**
         * Engine loaded against [modelId] and the loopback HTTP
         * server is accepting requests.
         */
        data class Ready(
            val modelId: String,
        ) : EngineReadiness

        /**
         * Load attempted but reached a terminal failure ([reason]
         * describes the underlying engine error). The daemon should
         * surface this through its log / activity stream — silently
         * falling back masks real misconfigurations.
         */
        data class Failed(
            val reason: String,
        ) : EngineReadiness

        /**
         * Load was cancelled mid-init by an explicit user action
         * (Stop tap, agent disabled during load). Distinguished from
         * [NotConfigured] so the daemon log can show a breadcrumb
         * rather than silently falling through to cloud as if the
         * user never asked for on-device.
         */
        data object Cancelled : EngineReadiness
    }

    /**
     * Snapshot of every reactive input we reconcile against.
     * Recomputed each emission of the combined flow.
     */
    private data class Conditions(
        val enabled: Boolean,
        val model: LiteRtModel,
        val fileReady: Boolean,
    )

    /**
     * Auto-unload driver invoked on every observable change of the
     * combined input flow. Only tears the engine down — never loads.
     *
     * Unload triggers:
     *  - Agent row no longer enabled (user switched away).
     *  - Model file deleted from disk.
     *  - User picked a different variant while the engine is hot
     *    (we drop the old engine; the next [awaitReadyForActiveAgent]
     *    call from a daemon restart loads the new variant fresh).
     *
     * Notably absent: `daemonRunning`. Earlier versions watched the
     * service-state flow and unloaded when the daemon stopped, but
     * that produced a race where `awaitReadyForActiveAgent` (called
     * during daemon-start, BEFORE the bridge transitions to RUNNING)
     * would load successfully — then the reconciler would observe
     * `daemonRunning = false` and immediately unload what we just
     * loaded. Service-scoped teardown belongs in [shutdown] (called
     * by `ZeroAIDaemonService.onDestroy`), not in the reactive
     * reconciler. The three conditions above are the ones that can
     * change *while the daemon is running* — those are the cases
     * worth reconciling reactively.
     *
     * When all conditions are satisfied AND a matching variant is
     * already Loaded, this is a no-op — the daemon will observe
     * `Ready` next time it asks.
     *
     * If a load is in flight when an unload is requested, we flag
     * cancellation BEFORE attempting to acquire the mutex. Without
     * that, the unload waits for the multi-second JNI initialise to
     * complete before it can begin — making the user-visible
     * disable-agent latency match the load duration. With the flag
     * raised, [LiteRtGemmaInference.load] tears the engine back down
     * the instant the JNI returns.
     */
    private suspend fun autoUnloadIfStale(conditions: Conditions) {
        val shouldStayLoaded = conditions.enabled && conditions.fileReady
        val current = _state.value
        val staleVariant =
            current is OnDeviceInferenceState.Loaded &&
                current.model.id != conditions.model.id
        if (!shouldStayLoaded || staleVariant) {
            inference.requestCancelLoad()
            mutex.withLock { unloadInternal() }
        }
    }

    /**
     * Internal load primitive used by [awaitReadyForActiveAgent].
     * Must be called while holding [mutex] so concurrent daemon
     * starts and auto-unload reconciles can't race the JNI handoff.
     *
     * Publishes the Loading sentinel before the suspending engine-
     * dispatcher hop so the Dashboard banner appears as fast as the
     * call chain allows.
     */
    private suspend fun loadInternal(model: LiteRtModel): EngineReadiness {
        _state.value = OnDeviceInferenceState.Loading(model)
        val path = modelStore.modelFilePath(model).absolutePath
        Log.i(
            TAG,
            "Loading LiteRT-LM model ${model.id} (GPU, ${model.contextTokens} tokens) path=$path",
        )
        val result =
            withContext(engineDispatcher) {
                inference.load(
                    modelPath = path,
                    // GPU only, by design: CPU inference is too slow
                    // for interactive use on the flagship devices we
                    // target, so we never fall back to it. A GPU init
                    // failure becomes a visible Failed state (see
                    // EngineReadiness.Failed) instead of a silent,
                    // unusably-slow CPU degrade.
                    backend = Backend.GPU(),
                    maxNumTokens = model.contextTokens,
                )
            }
        return result.fold(
            onSuccess = {
                Log.i(TAG, "LiteRT-LM model ${model.id} loaded successfully")
                httpServer.start()
                _state.value = OnDeviceInferenceState.Loaded(model)
                EngineReadiness.Ready(modelId = model.id)
            },
            onFailure = { error ->
                httpServer.stop()
                if (error is LiteRtGemmaInference.LoadCancelledException) {
                    Log.i(TAG, "LiteRT-LM model ${model.id} load cancelled by user")
                    _state.value = OnDeviceInferenceState.Idle
                    EngineReadiness.Cancelled
                } else {
                    Log.e(TAG, "LiteRT-LM model ${model.id} load failed", error)
                    val reason = error.message ?: error::class.simpleName.orEmpty()
                    _state.value =
                        OnDeviceInferenceState.Failed(model = model, reason = reason)
                    EngineReadiness.Failed(reason = reason)
                }
            },
        )
    }

    /** Companion holding the manager's logcat tag. */
    private companion object {
        private const val TAG = "OnDeviceInference"

        /**
         * Maximum number of characters logged when previewing model
         * input / output. Long enough to read a typical tool-call
         * body or a short reply; short enough to keep logcat from
         * truncating its 4 KB line cap.
         */
        private const val LOG_PREVIEW_CHARS = 600

        /**
         * Entropy (in bytes) for the loopback HTTP server's bearer
         * token. 32 bytes = 256 bits, well above what a co-installed
         * malicious app could brute-force during the daemon's
         * lifetime even at millions of requests per second.
         */
        private const val LOCAL_AUTH_TOKEN_BYTES: Int = 32
    }

    /**
     * Internal unload primitive used by the auto-unload reconciler,
     * [awaitReadyForActiveAgent] (when swapping variants), and
     * [shutdown]. Must be called while holding [mutex].
     */
    private suspend fun unloadInternal() {
        if (_state.value is OnDeviceInferenceState.Idle) return
        // Flag any in-flight load so the JNI return path tears the
        // engine down immediately instead of briefly publishing Loaded
        // before this unload runs. No-op when no load is in flight.
        inference.requestCancelLoad()
        httpServer.stop()
        withContext(engineDispatcher) { inference.unload() }
        _state.value = OnDeviceInferenceState.Idle
    }

    /**
     * Single load entry point. Called by
     * [com.zeroclaw.android.service.ZeroAIDaemonService] before the
     * daemon writes its TOML so the loopback HTTP server is up by
     * the time channels start polling.
     *
     * Behaviour:
     *  - Returns [EngineReadiness.NotConfigured] when the active row
     *    isn't on-device-large or the model file isn't on disk.
     *  - Returns [EngineReadiness.Ready] when the engine is loaded
     *    against the active variant — either because it already was,
     *    or because this call just loaded it.
     *  - Returns [EngineReadiness.Failed] when the load attempt hit
     *    a terminal error (GPU init refused, file corrupted, …).
     *
     * Variant swaps are handled inline: if the engine is already
     * Loaded against a different variant, the prior engine is torn
     * down and the requested variant loaded fresh.
     *
     * Holds [mutex] for the full load — concurrent callers (a daemon
     * restart racing a model-swap from the picker UI) serialise
     * cleanly. There is no separate reactive loader to race against;
     * the reconciler is unload-only.
     */
    suspend fun awaitReadyForActiveAgent(): EngineReadiness {
        val enabled =
            agentRepository.isAgentEnabled(OnDeviceLargeAgent.ID).first()
        if (!enabled) return EngineReadiness.NotConfigured
        val model = modelStore.selectedModel.first()
        if (!modelStore.isDownloaded(model)) return EngineReadiness.NotConfigured
        return mutex.withLock {
            when (val current = _state.value) {
                is OnDeviceInferenceState.Loaded ->
                    if (current.model.id == model.id) {
                        EngineReadiness.Ready(modelId = current.model.id)
                    } else {
                        // Variant swap: drop the prior engine before
                        // initialising the new one. The LiteRT-LM
                        // engine itself enforces single-instance, so
                        // we have to unload first anyway.
                        unloadInternal()
                        loadInternal(model)
                    }
                is OnDeviceInferenceState.Idle,
                is OnDeviceInferenceState.Failed,
                is OnDeviceInferenceState.Loading,
                -> loadInternal(model)
            }
        }
    }

    /**
     * Returns the currently-loaded model variant ID, or `null` when
     * no engine is loaded. Used by the daemon-start path to fill the
     * synthetic `default_model` in the localhost-pointing TOML.
     */
    fun loadedModelId(): String? = (state.value as? OnDeviceInferenceState.Loaded)?.model?.id

    /**
     * Convenience predicate used by the chat-routing layer to
     * decide whether to dispatch a prompt locally instead of
     * sending it through the daemon's cloud-provider path.
     */
    fun isLocalActive(): Boolean = state.value is OnDeviceInferenceState.Loaded

    /**
     * Tears down the watcher + engine. Unloads on the dedicated
     * engine dispatcher directly — avoids the
     * `scope.launch { ... }.join()` race where a cancelled scope
     * silently skips the unload and leaks GPU memory.
     */
    suspend fun shutdown() {
        reconcileJob?.cancel()
        reconcileJob = null
        // Flag in-flight load before grabbing the mutex so a 6-second
        // JNI initialise call still results in a prompt unload as soon
        // as it returns. Without this, the mutex.withLock here waits
        // for the load to finish AND then waits for a separate unload
        // hop — doubling the user-visible Stop latency.
        inference.requestCancelLoad()
        httpServer.shutdown()
        mutex.withLock { unloadInternal() }
        // `unloadInternal` already publishes Idle; no redundant write.
        // Release the single-thread executor so the daemon thread
        // doesn't survive the manager. Without this, debug
        // rebuild-reattaches accumulate "OnDeviceInference" threads.
        engineDispatcher.close()
    }
}
