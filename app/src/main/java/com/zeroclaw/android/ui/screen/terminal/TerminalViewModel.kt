/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.CachedTailscalePeer
import com.zeroclaw.android.model.LogSeverity
import com.zeroclaw.android.model.ProcessedImage
import com.zeroclaw.android.model.ProviderAuthType
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.model.TerminalEntry
import com.zeroclaw.android.model.VoiceState
import com.zeroclaw.android.service.AgentNotificationBridge
import com.zeroclaw.android.service.LocationBridge
import com.zeroclaw.android.service.OnDeviceImageDescriberBridge
import com.zeroclaw.android.service.OnDeviceInferenceBridge
import com.zeroclaw.android.service.OnDeviceProofreaderBridge
import com.zeroclaw.android.service.OnDeviceRewriterBridge
import com.zeroclaw.android.service.OnDeviceSummarizerBridge
import com.zeroclaw.android.service.ScreenCaptureBridge
import com.zeroclaw.android.service.SlotAwareAgentConfig
import com.zeroclaw.android.service.VoiceBridge
import com.zeroclaw.android.service.ZeroAIDaemonService
import com.zeroclaw.android.tailscale.PeerMatchResult
import com.zeroclaw.android.tailscale.PeerMessageRouter
import com.zeroclaw.android.tailscale.PeerRouteEntry
import com.zeroclaw.android.tailscale.isAgentKind
import com.zeroclaw.android.tailscale.normalizeKind
import com.zeroclaw.android.util.ErrorSanitizer
import com.zeroclaw.android.util.ImageProcessor
import com.zeroclaw.ffi.FfiException
import com.zeroclaw.ffi.FfiProgressPhase
import com.zeroclaw.ffi.FfiScriptValidation
import com.zeroclaw.ffi.FfiSessionListener
import com.zeroclaw.ffi.FfiWorkspaceScript
import com.zeroclaw.ffi.TailnetServiceKind
import com.zeroclaw.ffi.TtyCursorState
import com.zeroclaw.ffi.TtyCursorStyle
import com.zeroclaw.ffi.TtyHostKeyDecision
import com.zeroclaw.ffi.TtyRenderFrame
import com.zeroclaw.ffi.TtyRenderRow
import com.zeroclaw.ffi.evalScriptWithCapabilities
import com.zeroclaw.ffi.getProviderSupportsVision
import com.zeroclaw.ffi.listWorkspaceScripts
import com.zeroclaw.ffi.peerSendMessage
import com.zeroclaw.ffi.runWorkspaceScript
import com.zeroclaw.ffi.sessionCancel
import com.zeroclaw.ffi.sessionDestroy
import com.zeroclaw.ffi.sessionSend
import com.zeroclaw.ffi.sessionStart
import com.zeroclaw.ffi.ttyAnswerHostKey
import com.zeroclaw.ffi.ttyCreate
import com.zeroclaw.ffi.ttyDestroy
import com.zeroclaw.ffi.ttyDisconnectSsh
import com.zeroclaw.ffi.ttyGetOutputSnapshot
import com.zeroclaw.ffi.ttyGetPendingHostKey
import com.zeroclaw.ffi.ttyGetRenderFrame
import com.zeroclaw.ffi.ttyResize
import com.zeroclaw.ffi.ttyStartSsh
import com.zeroclaw.ffi.ttySubmitKey
import com.zeroclaw.ffi.ttySubmitPassword
import com.zeroclaw.ffi.ttyWaitForRenderSignal
import com.zeroclaw.ffi.ttyWrite
import com.zeroclaw.ffi.validateScript
import com.zeroclaw.ffi.validateWorkspaceScript
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * ViewModel for the terminal REPL screen.
 *
 * Routes user input through the [CommandRegistry] to classify it as a Rhai
 * expression, a local action, or a chat message, then dispatches accordingly.
 * Rhai expressions are evaluated against the daemon via FFI on
 * [Dispatchers.IO]. All inputs and outputs are persisted through the
 * [TerminalEntryRepository][com.zeroclaw.android.data.repository.TerminalEntryRepository]
 * so history survives navigation and app restarts.
 *
 * Also manages TTY/SSH terminal sessions. When the user switches to TTY mode a
 * local PTY is spawned (or an SSH connection is established), a polling
 * coroutine keeps [ttyRenderFrame] up to date, and [ttyFontSize] / [ttySelection]
 * expose pinch-to-zoom and text-selection state to the UI.
 *
 * Supports image attachments via the photo picker, with vision requests
 * routed through the `send_vision` Rhai function.
 *
 * @param application Application context for accessing repositories and bridges.
 * @param savedStateHandle Jetpack SavedState handle for surviving process death.
 */
@Suppress("LargeClass", "TooManyFunctions")
class TerminalViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val repository = app.terminalEntryRepository
    private val logRepository = app.logRepository
    private val settingsRepository = app.settingsRepository
    private val agentRepository = app.agentRepository
    private val apiKeyRepository = app.apiKeyRepository

    /**
     * Bridge for on-device Gemini Nano inference via ML Kit GenAI Prompt API.
     *
     * Lazily initialized to avoid allocating AI Core resources unless the
     * user actually invokes the `/nano` command.
     */
    private val onDeviceBridge: OnDeviceInferenceBridge by lazy {
        OnDeviceInferenceBridge()
    }

    /**
     * Bridge for on-device text summarization via ML Kit GenAI Summarization API.
     *
     * Lazily initialized to avoid allocating AI Core resources unless the
     * user invokes a summarization `/nano` subcommand.
     */
    private val summarizerBridge: OnDeviceSummarizerBridge by lazy {
        OnDeviceSummarizerBridge(app)
    }

    /**
     * Bridge for on-device text proofreading via ML Kit GenAI Proofreading API.
     *
     * Lazily initialized to avoid allocating AI Core resources unless the
     * user invokes a proofreading `/nano` subcommand.
     */
    private val proofreaderBridge: OnDeviceProofreaderBridge by lazy {
        OnDeviceProofreaderBridge(app)
    }

    /**
     * Bridge for on-device text rewriting via ML Kit GenAI Rewriting API.
     *
     * Lazily initialized to avoid allocating AI Core resources unless the
     * user invokes a rewriting `/nano` subcommand.
     */
    private val rewriterBridge: OnDeviceRewriterBridge by lazy {
        OnDeviceRewriterBridge(app)
    }

    /**
     * Bridge for on-device image description via ML Kit GenAI Image Description API.
     *
     * Lazily initialized to avoid allocating AI Core resources unless the
     * user invokes a describe `/nano` subcommand.
     */
    private val imageDescriberBridge: OnDeviceImageDescriberBridge by lazy {
        OnDeviceImageDescriberBridge(app)
    }

    /**
     * Bridge for device location queries.
     *
     * Lazily initialized to defer Play Services binding until
     * the user invokes `/location` or `/location-watch`.
     */
    private val locationBridge: LocationBridge by lazy {
        LocationBridge(app)
    }

    /**
     * Bridge for agent-initiated notifications.
     *
     * Lazily initialized on first `/notify` invocation.
     */
    private val notificationBridge: AgentNotificationBridge by lazy {
        AgentNotificationBridge(app)
    }

    /**
     * Bridge for screen capture via [MediaProjection][android.media.projection.MediaProjection].
     *
     * Lazily initialized on first `/screenshot` invocation.
     */
    private val screenCaptureBridge: ScreenCaptureBridge by lazy {
        ScreenCaptureBridge(app)
    }

    /**
     * Bridge for voice input (STT) and output (TTS).
     *
     * Lazily initialized on first voice interaction.
     */
    private val voiceBridge: VoiceBridge by lazy {
        VoiceBridge(app).also { bridge ->
            bridge.onSpeechResult = { text ->
                if (text.isNotBlank()) {
                    submitInput(text)
                }
            }
        }
    }

    /** Whether continuous location updates are active. */
    private val _locationWatchActive = MutableStateFlow(false)

    /**
     * Current terminal presentation mode.
     *
     * Defaults to [TerminalMode.Repl]. Switches to [TerminalMode.Tty] when
     * the `@tty` command opens a raw terminal session. Persisted across
     * navigation via [SavedStateHandle] so tab switches do not reset the mode.
     */
    private val _terminalMode =
        MutableStateFlow<TerminalMode>(
            if (savedStateHandle.get<Boolean>(KEY_TTY_ACTIVE) == true) {
                TerminalMode.Tty(session = TtySessionUiState.LocalShell)
            } else {
                TerminalMode.Repl
            },
        )

    /**
     * Lines of ANSI-stripped output from the active PTY session.
     *
     * Populated by an event-driven coroutine that calls [ttyGetOutputSnapshot]
     * after [ttyWaitForRenderSignal] signals new data, with a [TTY_RENDER_WAIT_TIMEOUT_MS]
     * heartbeat interval while TTY mode is active.
     */
    private val _ttyOutputLines = MutableStateFlow<List<String>>(emptyList())

    private val _ttyRenderFrame = MutableStateFlow<TtyRenderFrame?>(null)

    /**
     * The most recent [TtyRenderFrame] produced by the VT backend, or `null`
     * before the first frame has been received. Updated by the polling
     * coroutine started in [startTtyPolling].
     */
    val ttyRenderFrame: StateFlow<TtyRenderFrame?> = _ttyRenderFrame.asStateFlow()

    private val _ttyFontSize = MutableStateFlow(TTY_DEFAULT_FONT_SIZE)

    /**
     * Current terminal font size in sp, adjusted by pinch-to-zoom gestures
     * in the [TtyCanvasView] and clamped to the range [8, 32].
     */
    val ttyFontSize: StateFlow<Float> = _ttyFontSize.asStateFlow()

    private val _ttySelection = MutableStateFlow<TtySelectionState?>(null)

    /**
     * Current text selection in grid coordinates, or `null` when no selection
     * is active. Updated by [setTtySelection] in response to drag gestures.
     */
    val ttySelection: StateFlow<TtySelectionState?> = _ttySelection.asStateFlow()

    /**
     * Whether the Ctrl modifier key is currently toggled on.
     *
     * When active, the next character key press is combined with Ctrl
     * (e.g. 'c' becomes `0x03`) and the modifier resets to `false`.
     */
    private val _ttyCtrlActive = MutableStateFlow(false)

    /**
     * Whether the Alt modifier key is currently toggled on.
     *
     * When active, the next character key press is prefixed with ESC
     * (`0x1B`) to form an Alt sequence and the modifier resets to `false`.
     */
    private val _ttyAltActive = MutableStateFlow(false)

    /** Coroutine job for polling PTY output while TTY mode is active. */
    private var ttyPollJob: Job? = null

    /** Coroutine job for polling SSH host key prompts during the handshake. */
    private var sshPollJob: Job? = null

    /**
     * Signals the UI layer to launch the screen capture consent dialog.
     *
     * Set to `true` when the `/screenshot` command is invoked and the
     * bridge does not hold a valid [MediaProjection][android.media.projection.MediaProjection] token.
     * Reset to `false` after the UI observes the event.
     */
    private val _requestScreenCapture = MutableStateFlow(false)

    /**
     * Signals the UI layer to request `RECORD_AUDIO` runtime permission.
     *
     * Set to `true` when voice is activated but audio permission is missing.
     * Reset to `false` after the UI observes the event.
     */
    private val _requestAudioPermission = MutableStateFlow(false)

    /**
     * Signals the UI layer to request location runtime permissions.
     *
     * Set to `true` when `/location` or `/location-watch` is invoked but
     * location permissions are missing. Reset to `false` after the UI
     * observes the event.
     */
    private val _requestLocationPermission = MutableStateFlow(false)

    /**
     * Tracks whether the pending location request is for watch mode or
     * a single fix. Used to resume the correct operation after permission
     * is granted.
     */
    @Volatile
    private var pendingLocationWatch = false

    private val cachedSettings: StateFlow<AppSettings> =
        settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Peer agent aliases for `@` autocomplete in the terminal input. */
    val peerAliases: StateFlow<List<String>> =
        cachedSettings
            .map { settings ->
                getPeerRoutes().map { "@${it.alias}" }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(PEER_ALIAS_TIMEOUT), emptyList())

    private val loadingState = MutableStateFlow(false)
    private val pendingImagesState = MutableStateFlow<List<ProcessedImage>>(emptyList())
    private val processingImagesState = MutableStateFlow(false)
    private val _streamingState = MutableStateFlow(StreamingState.idle())
    private val _history = MutableStateFlow<List<String>>(emptyList())
    private val _historyIndex = MutableStateFlow(NO_HISTORY_SELECTION)
    private val _showCamera = MutableStateFlow(false)
    private val _cameraPrompt = MutableStateFlow(DEFAULT_CAMERA_PROMPT)
    private val _speakRepliesEnabled = MutableStateFlow(false)
    private val _lastAgentResponse = MutableStateFlow<String?>(null)
    private val _scriptPermissionRequest =
        MutableStateFlow<TerminalScriptPermissionRequest?>(null)
    private val screenshotPromptState = MutableStateFlow(DEFAULT_SCREENSHOT_PROMPT)

    /** Whether [sessionStart] has succeeded at least once. */
    @Volatile
    private var sessionReady = false

    /** Observable terminal state combining persisted entries with transient UI state. */
    val state: StateFlow<TerminalState> =
        combine(
            repository.entries,
            loadingState,
            pendingImagesState,
            processingImagesState,
        ) { entries, loading, images, processingImages ->
            TerminalState(
                blocks = entries.map(::toBlock),
                isLoading = loading,
                pendingImages = images,
                isProcessingImages = processingImages,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TerminalState())

    /** Previous input lines for history navigation, newest last. */
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /** Current position in the input history, or -1 when not navigating. */
    val historyIndex: StateFlow<Int> = _historyIndex.asStateFlow()

    /** Observable streaming state for the live agent session. */
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    /** Whether the camera preview sheet should be shown. */
    val showCamera: StateFlow<Boolean> = _showCamera.asStateFlow()

    /** The prompt to send with the captured camera image. */
    val cameraPrompt: StateFlow<String> = _cameraPrompt.asStateFlow()

    /**
     * Whether voice talk mode is currently active.
     *
     * When `true`, agent responses are automatically emitted via
     * [lastAgentResponse] for TTS playback by the UI layer.
     */
    val speakRepliesEnabled: StateFlow<Boolean> = _speakRepliesEnabled.asStateFlow()

    /**
     * The most recent agent response text for TTS consumption.
     *
     * Emitted only when [speakRepliesEnabled] is `true` and a new response
     * completes. The UI layer should collect this flow and call
     * [com.zeroclaw.android.service.VoiceBridge.speak] with the value.
     * Reset to `null` after emission.
     */
    val lastAgentResponse: StateFlow<String?> = _lastAgentResponse.asStateFlow()

    /** Pending packaged-script run awaiting Android-side capability approval. */
    val scriptPermissionRequest: StateFlow<TerminalScriptPermissionRequest?> =
        _scriptPermissionRequest.asStateFlow()

    /** Current voice bridge state for the VoiceFab. */
    val voiceState: StateFlow<VoiceState>
        get() = voiceBridge.state

    /** Whether continuous location tracking is active. */
    val locationWatchActive: StateFlow<Boolean> = _locationWatchActive.asStateFlow()

    /**
     * Signals the UI layer to launch the screen capture consent dialog.
     *
     * When this emits `true`, the [TerminalScreen] should launch its
     * registered [ActivityResultLauncher][androidx.activity.result.ActivityResultLauncher]
     * with the [MediaProjectionManager][android.media.projection.MediaProjectionManager]
     * screen capture intent.
     */
    val requestScreenCapture: StateFlow<Boolean> = _requestScreenCapture.asStateFlow()

    /**
     * Signals the UI layer to request `RECORD_AUDIO` runtime permission.
     *
     * When this emits `true`, the [TerminalScreen] should launch a
     * permission request for [android.Manifest.permission.RECORD_AUDIO].
     */
    val requestAudioPermission: StateFlow<Boolean> = _requestAudioPermission.asStateFlow()

    /**
     * Signals the UI layer to request location runtime permissions.
     *
     * When this emits `true`, the [TerminalScreen] should request
     * both `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`.
     */
    val requestLocationPermission: StateFlow<Boolean> = _requestLocationPermission.asStateFlow()

    /**
     * Current terminal presentation mode.
     *
     * The UI layer observes this to switch between the interactive REPL
     * composable and the raw TTY surface. Defaults to [TerminalMode.Repl].
     */
    val terminalMode: StateFlow<TerminalMode> = _terminalMode.asStateFlow()

    /**
     * ANSI-stripped output lines from the active PTY session.
     *
     * Empty when no TTY session is running. The UI renders these in a
     * monospace [LazyColumn][androidx.compose.foundation.lazy.LazyColumn].
     */
    val ttyOutputLines: StateFlow<List<String>> = _ttyOutputLines.asStateFlow()

    /** Whether the Ctrl modifier is toggled on for the next key press. */
    val ttyCtrlActive: StateFlow<Boolean> = _ttyCtrlActive.asStateFlow()

    /** Whether the Alt modifier is toggled on for the next key press. */
    val ttyAltActive: StateFlow<Boolean> = _ttyAltActive.asStateFlow()

    /** Whether the daemon foreground service is currently running. */
    val isDaemonRunning: StateFlow<Boolean> =
        app.daemonBridge.serviceState
            .combine(kotlinx.coroutines.flow.flowOf(Unit)) { state, _ ->
                state == ServiceState.RUNNING
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000L),
                app.daemonBridge.serviceState.value == ServiceState.RUNNING,
            )

    init {
        repository.clear()
        initAgentSession()
        observeDaemonRestarts()
    }

    /**
     * Initialises the live agent session on a background thread.
     *
     * Calls [sessionStart] on [Dispatchers.IO]. Failures are logged but
     * do not prevent the terminal from operating in Rhai-only mode.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun initAgentSession() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sessionStart()
                }
                sessionReady = true
            } catch (e: Exception) {
                logRepository.append(
                    LogSeverity.WARN,
                    TAG,
                    "Agent session init failed: ${e.message}",
                )
            }
        }
    }

    /**
     * Observes daemon service state and re-creates the session on restart.
     *
     * When the daemon transitions back to [ServiceState.RUNNING] after being
     * in a non-running state, the old session is destroyed and a fresh one
     * is started so it picks up the latest provider/model config.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun observeDaemonRestarts() {
        viewModelScope.launch {
            var wasRunning = app.daemonBridge.serviceState.value == ServiceState.RUNNING
            app.daemonBridge.serviceState.collect { state ->
                val isRunning = state == ServiceState.RUNNING
                if (isRunning && !wasRunning && sessionReady) {
                    logRepository.append(
                        LogSeverity.DEBUG,
                        TAG,
                        "Daemon restarted — re-creating agent session",
                    )
                    try {
                        withContext(Dispatchers.IO) { sessionDestroy() }
                    } catch (_: Exception) {
                        // session may not exist
                    }
                    sessionReady = false
                    try {
                        withContext(Dispatchers.IO) { sessionStart() }
                        sessionReady = true
                    } catch (e: Exception) {
                        logRepository.append(
                            LogSeverity.WARN,
                            TAG,
                            "Session re-init after restart failed: ${e.message}",
                        )
                    }
                }
                wasRunning = isRunning
            }
        }
    }

    /**
     * Attempts [sessionStart] if the initial call in [initAgentSession] failed.
     *
     * This handles the race where the daemon service hasn't finished
     * starting when the ViewModel initialises. Called on [Dispatchers.IO]
     * before the first [sessionSend].
     *
     * @return `true` if a session is now active.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ensureSession(): Boolean {
        if (sessionReady) return true
        return try {
            sessionStart()
            sessionReady = true
            true
        } catch (e: Exception) {
            logRepository.append(
                LogSeverity.WARN,
                TAG,
                "Session retry failed: ${e.message}",
            )
            false
        }
    }

    /**
     * Tears down the agent session and PTY when the ViewModel is destroyed.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun onCleared() {
        super.onCleared()
        sshPollJob?.cancel()
        try {
            ttyDisconnectSsh()
        } catch (e: Exception) {
            logRepository.append(LogSeverity.WARN, TAG, "SSH disconnect failed: ${e.message}")
        }
        stopTtyPolling()
        try {
            ttyDestroy()
        } catch (e: Exception) {
            logRepository.append(LogSeverity.WARN, TAG, "TTY destroy failed: ${e.message}")
        }
        try {
            sessionDestroy()
        } catch (e: Exception) {
            logRepository.append(LogSeverity.WARN, TAG, "Session destroy failed: ${e.message}")
        }
        try {
            voiceBridge.destroy()
        } catch (e: Exception) {
            logRepository.append(LogSeverity.WARN, TAG, "Voice bridge destroy failed: ${e.message}")
        }
        if (_locationWatchActive.value) {
            locationBridge.stopLocationUpdates()
        }
        screenCaptureBridge.release()
    }

    /**
     * Switches the terminal into raw TTY mode with a local shell session.
     *
     * Creates a PTY via [ttyCreate], starts an output polling coroutine,
     * and updates [terminalMode] so the UI renders the TTY surface.
     */
    @Suppress("TooGenericExceptionCaught")
    fun switchToTty() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ttyCreate(TTY_DEFAULT_COLS.toUInt(), TTY_DEFAULT_ROWS.toUInt())
                _terminalMode.value =
                    TerminalMode.Tty(
                        session = TtySessionUiState.LocalShell,
                    )
                savedStateHandle[KEY_TTY_ACTIVE] = true
                startTtyPolling()
            } catch (e: Exception) {
                logRepository.append(
                    LogSeverity.ERROR,
                    TAG,
                    "TTY create failed: ${e.message}",
                )
                _terminalMode.value =
                    TerminalMode.Tty(
                        session =
                            TtySessionUiState.Error(
                                e.message ?: "Failed to create PTY session",
                            ),
                    )
            }
        }
    }

    /**
     * Returns the terminal to interactive REPL mode.
     *
     * Destroys the PTY session via [ttyDestroy], stops output polling,
     * clears modifier state, and restores the REPL composable.
     */
    @Suppress("TooGenericExceptionCaught")
    fun switchToRepl() {
        stopTtyPolling()
        _ttyOutputLines.value = emptyList()
        _ttyRenderFrame.value = null
        _ttySelection.value = null
        _ttyCtrlActive.value = false
        _ttyAltActive.value = false
        _terminalMode.value = TerminalMode.Repl
        savedStateHandle[KEY_TTY_ACTIVE] = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ttyDestroy()
            } catch (e: Exception) {
                logRepository.append(
                    LogSeverity.WARN,
                    TAG,
                    "TTY destroy failed: ${e.message}",
                )
            }
        }
        viewModelScope.launch {
            repository.append(
                content = "TTY session ended.",
                entryType = ENTRY_TYPE_SYSTEM,
            )
        }
    }

    /**
     * Sends raw bytes to the active PTY session.
     *
     * @param bytes Raw byte data to write to the PTY input.
     */
    @Suppress("TooGenericExceptionCaught")
    fun ttyWriteBytes(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ttyWrite(bytes)
            } catch (e: Exception) {
                logRepository.append(
                    LogSeverity.WARN,
                    TAG,
                    "TTY write failed: ${e.message}",
                )
            }
        }
    }

    /**
     * Sends a text string to the active PTY session as UTF-8 bytes.
     *
     * Intercepts `/ssh user@host [-p port]` before forwarding to the PTY,
     * routing such input to [sshConnect] instead. All other input is forwarded
     * as raw UTF-8 bytes via [ttyWriteBytes].
     *
     * @param text Text to write (typically a single character or line).
     */
    @Suppress("MagicNumber")
    fun ttyWriteText(text: String) {
        val match = SSH_COMMAND_PATTERN.matchEntire(text.trimEnd('\n'))
        if (match != null) {
            val user = match.groupValues[1]
            val host = match.groupValues[2]
            val port = match.groupValues[3].toIntOrNull() ?: SSH_DEFAULT_PORT
            if (!SSH_USER_PATTERN.matches(user) || !SSH_HOST_PATTERN.matches(host) || port > SSH_MAX_PORT) {
                viewModelScope.launch {
                    repository.append(content = "Invalid SSH target.", entryType = ENTRY_TYPE_ERROR)
                }
                return
            }
            sshConnect(user, host, port)
            return
        }
        ttyWriteBytes(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Handles a special key press from the [TtyKeyRow].
     *
     * Modifier keys (Ctrl, Alt) toggle their respective state flags.
     * All other keys are encoded and sent to the PTY, with modifier
     * prefixes applied if active.
     *
     * @param key The special key that was pressed.
     */
    fun ttyHandleSpecialKey(key: TtySpecialKey) {
        when (key) {
            TtySpecialKey.CTRL -> {
                _ttyCtrlActive.update { !it }
                return
            }
            TtySpecialKey.ALT -> {
                _ttyAltActive.update { !it }
                return
            }
            else -> Unit
        }
        val bytes = encodeTtyKey(key, _ttyCtrlActive.value, _ttyAltActive.value)
        _ttyCtrlActive.value = false
        _ttyAltActive.value = false
        ttyWriteBytes(bytes)
    }

    /**
     * Starts the coroutine that waits for render-signal notifications from the PTY read loop
     * and updates [_ttyOutputLines] and [_ttyRenderFrame] when new data arrives.
     *
     * Uses [ttyWaitForRenderSignal] which blocks on [Dispatchers.IO] until the Rust PTY read
     * loop signals new data via a Condvar, or until [TTY_RENDER_WAIT_TIMEOUT_MS] elapses.
     * This replaces the previous fixed-interval polling approach with an event-driven model.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun startTtyPolling() {
        ttyPollJob?.cancel()
        ttyPollJob =
            viewModelScope.launch(Dispatchers.IO) {
                while (true) {
                    val hasData = waitForRenderSignal()
                    if (hasData) applyTtyRenderFrame()
                }
            }
    }

    /**
     * Blocks on [Dispatchers.IO] until the Rust PTY read loop signals new render data or the
     * [TTY_RENDER_WAIT_TIMEOUT_MS] heartbeat elapses.
     *
     * @return `true` if new data is available; `false` on a clean timeout (no new data).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun waitForRenderSignal(): Boolean =
        try {
            ttyWaitForRenderSignal(TTY_RENDER_WAIT_TIMEOUT_MS.toULong())
        } catch (e: Exception) {
            delay(TTY_RENDER_WAIT_TIMEOUT_MS)
            true
        }

    /**
     * Reads the latest PTY output snapshot and render frame from Rust, then pushes both to
     * [_ttyOutputLines] and [_ttyRenderFrame]. Falls back to [buildFallbackFrame] if the frame
     * is empty or an FFI exception is thrown.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun applyTtyRenderFrame() {
        val textLines =
            try {
                val lines = ttyGetOutputSnapshot(TTY_SNAPSHOT_MAX_LINES.toUInt())
                _ttyOutputLines.value = lines
                lines
            } catch (e: Exception) {
                _ttyOutputLines.value
            }
        val nextFrame =
            try {
                val frame = ttyGetRenderFrame()
                if (frame.rows.isNotEmpty()) frame else buildFallbackFrame(textLines)
            } catch (e: Exception) {
                buildFallbackFrame(textLines)
            }
        _ttyRenderFrame.value = nextFrame
    }

    /** Cancels the PTY output polling coroutine. */
    private fun stopTtyPolling() {
        ttyPollJob?.cancel()
        ttyPollJob = null
    }

    /** Updates the terminal font size from pinch-to-zoom. */
    fun setTtyFontSize(sizeSp: Float) {
        _ttyFontSize.value = sizeSp.coerceIn(TTY_MIN_FONT_SIZE, TTY_MAX_FONT_SIZE)
    }

    /** Updates the terminal text selection state. */
    fun setTtySelection(selection: TtySelectionState?) {
        _ttySelection.value = selection
    }

    /** Resizes the PTY when the Canvas grid dimensions change. */
    @Suppress("TooGenericExceptionCaught")
    fun onTtyGridSizeChanged(
        cols: Int,
        rows: Int,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ttyResize(
                    cols.coerceAtLeast(1).toUInt(),
                    rows.coerceAtLeast(1).toUInt(),
                    0u,
                    0u,
                )
            } catch (e: Exception) {
                logRepository.append(LogSeverity.WARN, TAG, "TTY resize failed: ${e.message}")
            }
        }
    }

    /**
     * Builds a [TtyRenderFrame] from plain text lines when the VT backend
     * is unavailable (stub backend). Provides basic terminal output with
     * default green-on-dark styling.
     */
    private fun buildFallbackFrame(lines: List<String>): TtyRenderFrame {
        val rows =
            lines.map { line ->
                TtyRenderRow(
                    text = line,
                    spans = emptyList(),
                    dirty = true,
                )
            }
        return TtyRenderFrame(
            rows = rows,
            cols = TTY_DEFAULT_COLS.toUShort(),
            numRows = rows.size.toUShort(),
            cursor =
                TtyCursorState(
                    col = 0u.toUShort(),
                    row =
                        rows.size
                            .coerceAtLeast(1)
                            .toUShort()
                            .dec(),
                    visible = true,
                    style = TtyCursorStyle.BLOCK,
                    blinking = true,
                ),
            defaultBgArgb = 0xFF1A1A2Eu,
            defaultFgArgb = 0xFF4AF626u,
            hasChanges = true,
        )
    }

    /**
     * Initiates an SSH connection to a remote host.
     *
     * Destroys any active local shell, transitions to connecting state,
     * and begins polling for host key prompts.
     *
     * @param user SSH username.
     * @param host Remote hostname or IP address.
     * @param port Remote SSH port.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sshConnect(
        user: String,
        host: String,
        port: Int,
    ) {
        stopTtyPolling()
        _terminalMode.value =
            TerminalMode.Tty(
                session = TtySessionUiState.SshConnecting(host, port, user),
            )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ttyStartSsh(host, port.toUInt(), user)
                startHostKeyPolling()
            } catch (e: Exception) {
                _terminalMode.value =
                    TerminalMode.Tty(
                        session =
                            TtySessionUiState.Error(
                                "SSH connection failed: ${e.message}",
                            ),
                    )
            }
        }
    }

    /**
     * Polls for a pending host key prompt from the SSH handshake.
     *
     * Runs every [SSH_HOST_KEY_POLL_MS] until a prompt arrives or
     * [SSH_HANDSHAKE_TIMEOUT_MS] elapses.
     */
    @Suppress("CognitiveComplexMethod", "LongMethod")
    private fun startHostKeyPolling() {
        sshPollJob?.cancel()
        sshPollJob =
            viewModelScope.launch(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + SSH_HANDSHAKE_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    try {
                        // Check SSH state machine transitions.
                        val state = com.zeroclaw.ffi.ttyGetSshState()

                        if (state == com.zeroclaw.ffi.SshState.DISCONNECTED) {
                            val lines = ttyGetOutputSnapshot(TTY_SNAPSHOT_MAX_LINES.toUInt())
                            val errorMsg =
                                lines.lastOrNull { it.startsWith("SSH error:") }
                                    ?: "SSH connection failed"
                            _terminalMode.value =
                                TerminalMode.Tty(
                                    session = TtySessionUiState.Error(errorMsg),
                                )
                            return@launch
                        }

                        // SSH connected without user interaction (host
                        // already trusted + key auth auto-succeeded).
                        if (state == com.zeroclaw.ffi.SshState.CONNECTED) {
                            _terminalMode.value =
                                TerminalMode.Tty(
                                    session = TtySessionUiState.SshConnected(hostLabel = "SSH"),
                                )
                            startTtyPolling()
                            return@launch
                        }

                        // Awaiting host key — show trust dialog.
                        if (state == com.zeroclaw.ffi.SshState.AWAITING_HOST_KEY) {
                            val prompt = ttyGetPendingHostKey()
                            if (prompt != null) {
                                _terminalMode.value =
                                    TerminalMode.Tty(
                                        session =
                                            TtySessionUiState.HostKeyVerification(
                                                host = prompt.host,
                                                port = prompt.port.toInt(),
                                                algorithm = prompt.algorithm,
                                                fingerprintSha256 = prompt.fingerprintSha256,
                                                isChanged = prompt.isChanged,
                                            ),
                                    )
                                return@launch
                            }
                        }

                        // Authenticating — show auth dialog.
                        if (state == com.zeroclaw.ffi.SshState.AUTHENTICATING) {
                            _terminalMode.value =
                                TerminalMode.Tty(
                                    session =
                                        TtySessionUiState.SshAuthRequired(
                                            methods = listOf("password", "publickey"),
                                        ),
                                )
                            return@launch
                        }
                    } catch (_: Exception) {
                        break
                    }
                    delay(SSH_HOST_KEY_POLL_MS)
                }
                // Timeout — show error
                _terminalMode.value =
                    TerminalMode.Tty(
                        session = TtySessionUiState.Error("SSH connection timed out"),
                    )
            }
    }

    /**
     * Responds to a host key verification prompt.
     *
     * @param accept Whether to trust the presented host key.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sshAnswerHostKey(accept: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val decision =
                    if (accept) {
                        TtyHostKeyDecision.ACCEPT
                    } else {
                        TtyHostKeyDecision.REJECT
                    }
                ttyAnswerHostKey(decision)
                if (accept) {
                    _terminalMode.value =
                        TerminalMode.Tty(
                            session =
                                TtySessionUiState.SshAuthRequired(
                                    methods = listOf("password", "publickey"),
                                ),
                        )
                } else {
                    sshDisconnect()
                }
            } catch (e: Exception) {
                _terminalMode.value =
                    TerminalMode.Tty(
                        session =
                            TtySessionUiState.Error(
                                "Host key error: ${e.message}",
                            ),
                    )
            }
        }
    }

    /**
     * Submits a password for SSH authentication.
     *
     * Both the [CharArray] and intermediate [ByteArray] are zeroed
     * on all exit paths.
     *
     * @param chars Password as a character array.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sshSubmitPassword(chars: CharArray) {
        viewModelScope.launch(Dispatchers.IO) {
            var bytes: ByteArray? = null
            try {
                val encoder = Charsets.UTF_8.newEncoder()
                val buf = encoder.encode(java.nio.CharBuffer.wrap(chars))
                bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                val success = ttySubmitPassword(bytes)
                if (success) {
                    _terminalMode.value =
                        TerminalMode.Tty(
                            session = TtySessionUiState.SshConnected(hostLabel = "SSH"),
                        )
                    startTtyPolling()
                } else {
                    _terminalMode.value =
                        TerminalMode.Tty(
                            session =
                                TtySessionUiState.SshAuthRequired(
                                    methods = listOf("password", "publickey"),
                                ),
                        )
                }
            } catch (e: Exception) {
                _terminalMode.value =
                    TerminalMode.Tty(
                        session =
                            TtySessionUiState.Error(
                                "Auth failed: ${e.message}",
                            ),
                    )
            } finally {
                chars.fill('\u0000')
                bytes?.fill(0)
            }
        }
    }

    /**
     * Submits an SSH key for authentication.
     *
     * @param keyId UUID of the key in the Rust key store.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sshSubmitKey(keyId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val success = ttySubmitKey(keyId)
                if (success) {
                    _terminalMode.value =
                        TerminalMode.Tty(
                            session = TtySessionUiState.SshConnected(hostLabel = "SSH"),
                        )
                    startTtyPolling()
                } else {
                    _terminalMode.value =
                        TerminalMode.Tty(
                            session =
                                TtySessionUiState.SshAuthRequired(
                                    methods = listOf("password", "publickey"),
                                ),
                        )
                }
            } catch (e: Exception) {
                _terminalMode.value =
                    TerminalMode.Tty(
                        session =
                            TtySessionUiState.Error(
                                "Key auth failed: ${e.message}",
                            ),
                    )
            }
        }
    }

    /**
     * Disconnects the active SSH session and returns to REPL mode.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sshDisconnect() {
        sshPollJob?.cancel()
        sshPollJob = null
        stopTtyPolling()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ttyDisconnectSsh()
            } catch (_: Exception) {
                // idempotent
            }
        }
        _terminalMode.value = TerminalMode.Repl
        viewModelScope.launch {
            repository.append(
                content = "SSH session ended.",
                entryType = ENTRY_TYPE_SYSTEM,
            )
        }
    }

    /**
     * Submits user input for processing.
     *
     * Before command parsing, checks for an `@alias` prefix matching a known
     * tailnet peer. If matched, routes to [dispatchPeerMessage] and returns early.
     *
     * Otherwise, parses the input through [CommandRegistry.parseAndTranslate] and
     * dispatches based on the result type:
     * - [CommandResult.RhaiExpression]: persists the input, evaluates
     *   via FFI, and persists the response or error.
     * - [CommandResult.WorkspaceScriptCommand]: routes packaged workspace
     *   scripts through dedicated validation and explicit permission review.
     * - [CommandResult.LocalAction]: handles "help" and "clear" locally.
     * - [CommandResult.ChatMessage]: wraps in a `send()` Rhai call,
     *   or `send_vision()` when images are attached.
     *
     * @param text The raw text entered by the user.
     */
    fun submitInput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && pendingImagesState.value.isEmpty()) return

        appendToHistory(trimmed)
        _historyIndex.value = NO_HISTORY_SELECTION

        val peerMatch = PeerMessageRouter.matchAlias(trimmed, getPeerRoutes())
        if (peerMatch != null) {
            dispatchPeerMessage(trimmed, peerMatch)
            return
        }

        val result = CommandRegistry.parseAndTranslate(trimmed)
        when (result) {
            is CommandResult.RhaiExpression -> executeRhai(trimmed, result.expression)
            is CommandResult.WorkspaceScriptCommand ->
                handleWorkspaceScriptCommand(trimmed, result.action)
            is CommandResult.LocalAction -> handleLocalAction(result.action)
            is CommandResult.ChatMessage -> executeChatMessage(trimmed, result.text)
            is CommandResult.NanoCommand -> executeNanoIntent(trimmed, result.intent)
            is CommandResult.TtyOpen -> switchToTty()
        }
    }

    /**
     * Navigates backward (older) through input history.
     *
     * @return The history entry at the new position, or `null` if history is empty.
     */
    fun historyUp(): String? {
        val items = _history.value
        if (items.isEmpty()) return null
        val current = _historyIndex.value
        val newIndex =
            if (current == NO_HISTORY_SELECTION) {
                items.lastIndex
            } else {
                (current - 1).coerceAtLeast(0)
            }
        _historyIndex.value = newIndex
        return items[newIndex]
    }

    /**
     * Navigates forward (newer) through input history.
     *
     * @return The history entry at the new position, or `null` if past the newest entry.
     */
    fun historyDown(): String? {
        val items = _history.value
        val current = _historyIndex.value
        if (current == NO_HISTORY_SELECTION || items.isEmpty()) return null
        val newIndex = current + 1
        if (newIndex > items.lastIndex) {
            _historyIndex.value = NO_HISTORY_SELECTION
            return null
        }
        _historyIndex.value = newIndex
        return items[newIndex]
    }

    /**
     * Processes and stages images from the photo picker.
     *
     * Runs [ImageProcessor.process] on [Dispatchers.IO] to downscale,
     * compress, and base64-encode the selected images. Results are appended
     * to the pending images list, capped at [MAX_IMAGES].
     *
     * @param uris Content URIs returned by the photo picker.
     */
    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            processingImagesState.value = true
            try {
                val contentResolver = app.contentResolver
                val processed = ImageProcessor.process(contentResolver, uris)
                val current = pendingImagesState.value
                pendingImagesState.value = (current + processed).take(MAX_IMAGES)
            } finally {
                processingImagesState.value = false
            }
        }
    }

    /**
     * Removes a pending image at the given index.
     *
     * @param index Zero-based index into the pending images list.
     */
    fun removeImage(index: Int) {
        val current = pendingImagesState.value
        if (index in current.indices) {
            pendingImagesState.value = current.toMutableList().apply { removeAt(index) }
        }
    }

    /**
     * Evaluates a Rhai expression via FFI and persists the result.
     *
     * The input is immediately persisted as an "input" entry. The expression
     * is then evaluated on [Dispatchers.IO]. Successful results are persisted
     * as "response" entries; failures are persisted as "error" entries.
     *
     * @param displayText The original user input shown in the scrollback.
     * @param expression The Rhai expression to evaluate.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun executeRhai(
        displayText: String,
        expression: String,
    ) {
        loadingState.value = true
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            try {
                val validation =
                    withContext(Dispatchers.IO) {
                        validateScript(expression)
                    }
                if (validation.requestedCapabilities.isNotEmpty()) {
                    repository.append(
                        content =
                            "Requested script capabilities: " +
                                validation.requestedCapabilities.joinToString(", "),
                        entryType = ENTRY_TYPE_SYSTEM,
                    )
                }
                if (validation.warnings.isNotEmpty()) {
                    repository.append(
                        content = validation.warnings.joinToString("\n"),
                        entryType = ENTRY_TYPE_SYSTEM,
                    )
                }

                val rawResult =
                    withContext(Dispatchers.IO) {
                        evalScriptWithCapabilities(
                            expression,
                            validation.requestedCapabilities,
                        )
                    }
                val cleaned = stripToolCallTags(rawResult)
                val result =
                    if (cachedSettings.value.stripThinkingTags) {
                        stripThinkingTags(cleaned)
                    } else {
                        cleaned
                    }
                val displayResult =
                    result.ifBlank {
                        rawResult.trim().ifBlank { EMPTY_RESPONSE_FALLBACK }
                    }
                repository.append(content = displayResult, entryType = ENTRY_TYPE_RESPONSE)
                handleBindResult(displayResult)
                emitRefreshIfNeeded(expression)
            } catch (e: FfiException) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "REPL eval failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } catch (e: Exception) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "REPL eval failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    /**
     * Handles a packaged workspace script command from `/scripts ...`.
     *
     * Dedicated packaged-script flows use Rust-side validation and explicit
     * capability grants instead of the legacy REPL expression path.
     *
     * @param displayText Original user input shown in the terminal history.
     * @param action Parsed packaged-script action.
     */
    private fun handleWorkspaceScriptCommand(
        displayText: String,
        action: WorkspaceScriptAction,
    ) {
        when (action) {
            is WorkspaceScriptAction.List -> showWorkspaceScriptList(displayText)
            is WorkspaceScriptAction.Validate ->
                showWorkspaceScriptValidation(displayText, action.relativePath)
            is WorkspaceScriptAction.Run ->
                prepareWorkspaceScriptRun(displayText, action.relativePath)
            is WorkspaceScriptAction.Invalid -> {
                viewModelScope.launch {
                    repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
                    repository.append(content = action.message, entryType = ENTRY_TYPE_ERROR)
                }
            }
        }
    }

    /**
     * Lists discoverable packaged workspace scripts.
     *
     * @param displayText Original user input shown in the terminal history.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun showWorkspaceScriptList(displayText: String) {
        loadingState.value = true
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            try {
                val scripts =
                    withContext(Dispatchers.IO) {
                        listWorkspaceScripts()
                    }
                repository.append(
                    content = formatWorkspaceScriptList(scripts),
                    entryType = ENTRY_TYPE_RESPONSE,
                )
            } catch (e: FfiException) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "Script listing failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } catch (e: Exception) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "Script listing failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    /**
     * Validates a packaged workspace script and renders the result in the terminal.
     *
     * @param displayText Original user input shown in the terminal history.
     * @param relativePath Script path relative to the workspace root.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun showWorkspaceScriptValidation(
        displayText: String,
        relativePath: String,
    ) {
        loadingState.value = true
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            try {
                val validation =
                    withContext(Dispatchers.IO) {
                        validateWorkspaceScript(relativePath)
                    }
                repository.append(
                    content = formatScriptValidation(relativePath, validation),
                    entryType = ENTRY_TYPE_RESPONSE,
                )
            } catch (e: FfiException) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(
                    LogSeverity.ERROR,
                    TAG,
                    "Script validation failed: $sanitized",
                )
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } catch (e: Exception) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(
                    LogSeverity.ERROR,
                    TAG,
                    "Script validation failed: $sanitized",
                )
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    /**
     * Prepares a packaged workspace script run and opens the grant/deny dialog.
     *
     * @param displayText Original user input shown in the terminal history.
     * @param relativePath Script path relative to the workspace root.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun prepareWorkspaceScriptRun(
        displayText: String,
        relativePath: String,
    ) {
        if (_scriptPermissionRequest.value != null) {
            viewModelScope.launch {
                repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
                repository.append(
                    content = "Resolve the current script permission prompt first.",
                    entryType = ENTRY_TYPE_ERROR,
                )
            }
            return
        }

        loadingState.value = true
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            try {
                val validation =
                    withContext(Dispatchers.IO) {
                        validateWorkspaceScript(relativePath)
                    }
                if (validation.runtime != SCRIPT_RUNTIME_RHAI) {
                    repository.append(
                        content = formatScriptValidation(relativePath, validation),
                        entryType = ENTRY_TYPE_RESPONSE,
                    )
                    repository.append(
                        content =
                            "Runtime ${validation.runtime} is not executable from the Android " +
                                "terminal in this build yet.",
                        entryType = ENTRY_TYPE_SYSTEM,
                    )
                    return@launch
                }

                _scriptPermissionRequest.value =
                    TerminalScriptPermissionRequest(
                        relativePath = relativePath,
                        manifestName = validation.manifestName,
                        runtime = validation.runtime,
                        requestedCapabilities = validation.requestedCapabilities,
                        grantedCapabilities = validation.requestedCapabilities,
                        missingCapabilities = validation.missingCapabilities,
                        warnings = validation.warnings,
                    )
                repository.append(
                    content = "Review permissions for $relativePath before running.",
                    entryType = ENTRY_TYPE_SYSTEM,
                )
            } catch (e: FfiException) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "Script run setup failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } catch (e: Exception) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "Script run setup failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    /**
     * Toggles one capability in the pending packaged-script grant set.
     *
     * @param capability Capability name to toggle.
     */
    fun toggleScriptCapability(capability: String) {
        _scriptPermissionRequest.update { current ->
            current ?: return@update null
            val nextGranted =
                if (capability in current.grantedCapabilities) {
                    current.requestedCapabilities.filterNot { it == capability }
                } else {
                    (current.grantedCapabilities + capability).distinct().let { granted ->
                        current.requestedCapabilities.filter { it in granted }
                    }
                }
            current.copy(grantedCapabilities = nextGranted)
        }
    }

    /** Grants every capability requested by the pending packaged-script run. */
    fun grantAllScriptCapabilities() {
        _scriptPermissionRequest.update { current ->
            current?.copy(grantedCapabilities = current.requestedCapabilities)
        }
    }

    /** Denies every capability requested by the pending packaged-script run. */
    fun denyAllScriptCapabilities() {
        _scriptPermissionRequest.update { current ->
            current?.copy(grantedCapabilities = emptyList())
        }
    }

    /**
     * Dismisses the pending packaged-script permission dialog without running the script.
     */
    fun dismissScriptPermissionRequest() {
        val request = _scriptPermissionRequest.value ?: return
        _scriptPermissionRequest.value = null
        viewModelScope.launch {
            repository.append(
                content = "Cancelled packaged script run: ${request.relativePath}",
                entryType = ENTRY_TYPE_SYSTEM,
            )
        }
    }

    /**
     * Executes the pending packaged-script run with the user-selected grant set.
     */
    @Suppress("TooGenericExceptionCaught")
    fun confirmScriptPermissionRequest() {
        val request = _scriptPermissionRequest.value ?: return
        _scriptPermissionRequest.value = null
        loadingState.value = true
        viewModelScope.launch {
            try {
                repository.append(
                    content =
                        buildString {
                            append("Running ${request.relativePath}")
                            if (request.grantedCapabilities.isEmpty()) {
                                append(" with no granted host capabilities.")
                            } else {
                                append(" with capabilities: ")
                                append(request.grantedCapabilities.joinToString(", "))
                            }
                        },
                    entryType = ENTRY_TYPE_SYSTEM,
                )

                val rawResult =
                    withContext(Dispatchers.IO) {
                        runWorkspaceScript(
                            request.relativePath,
                            request.grantedCapabilities,
                        )
                    }
                val cleaned = stripToolCallTags(rawResult)
                val result =
                    if (cachedSettings.value.stripThinkingTags) {
                        stripThinkingTags(cleaned)
                    } else {
                        cleaned
                    }
                val displayResult =
                    result.ifBlank {
                        rawResult.trim().ifBlank { EMPTY_RESPONSE_FALLBACK }
                    }
                repository.append(content = displayResult, entryType = ENTRY_TYPE_RESPONSE)
            } catch (e: FfiException) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "Packaged script run failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } catch (e: Exception) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "Packaged script run failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    /**
     * Returns enabled peer route entries derived from cached tailscale discovery data.
     *
     * Reads the JSON-serialized peer cache from [AppSettings.tailscaleCachedDiscovery]
     * and maps each agent service (zeroclaw or openclaw) to a [PeerRouteEntry].
     * Returns an empty list when no peers are cached or the setting is blank.
     *
     * @return List of peer routes available for @alias routing.
     */
    private fun getPeerRoutes(): List<PeerRouteEntry> {
        val cached = cachedSettings.value.tailscaleCachedDiscovery
        if (cached.isBlank()) return emptyList()
        return try {
            val peers = Json.decodeFromString<List<CachedTailscalePeer>>(cached)
            val raw =
                peers.flatMap { peer ->
                    peer.services
                        .filter { svc -> isAgentKind(svc.kind) }
                        .map { svc ->
                            PeerRouteEntry(
                                alias = normalizeKind(svc.kind),
                                ip = peer.ip,
                                port = svc.port,
                                kind = normalizeKind(svc.kind),
                            )
                        }
                }
            val defaults =
                PeerMessageRouter.resolveAliasConflicts(raw.map { it.alias })
            val masterKey =
                MasterKey
                    .Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val prefs =
                EncryptedSharedPreferences.create(
                    app,
                    "tailscale_peer_tokens",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            raw.mapIndexed { i, entry ->
                val sanitizedIp =
                    entry.ip.replace(Regex("[^a-fA-F0-9.:]"), "")
                val savedAlias =
                    prefs.getString(
                        "tailscale_alias_${sanitizedIp}_${entry.port}",
                        null,
                    )
                entry.copy(alias = savedAlias ?: defaults[i])
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Retrieves a stored bearer token for the given peer from encrypted preferences.
     *
     * Uses the same storage key convention as [TailscaleConfigViewModel].
     * Returns `null` when no token has been saved for this peer.
     *
     * @param ip Tailscale IP of the peer.
     * @param port Gateway port of the peer service.
     * @return Stored bearer token, or `null` if absent.
     */
    private fun getPeerToken(
        ip: String,
        port: Int,
    ): String? =
        try {
            val masterKey =
                MasterKey
                    .Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val prefs =
                EncryptedSharedPreferences.create(
                    app,
                    "tailscale_peer_tokens",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            val sanitizedIp = ip.replace(Regex("[^a-fA-F0-9.:]"), "")
            prefs.getString("tailscale_peer_${sanitizedIp}_$port", null)
        } catch (_: Exception) {
            null
        }

    /**
     * Converts a service kind string to its [TailnetServiceKind] enum value.
     *
     * @param kind Service kind identifier, either `"zeroclaw"` or `"openclaw"`.
     * @return Corresponding [TailnetServiceKind], defaulting to [TailnetServiceKind.Zeroclaw].
     */
    private fun serviceKindFromString(kind: String): TailnetServiceKind =
        when (kind.lowercase()) {
            "openclaw" -> TailnetServiceKind.OPEN_CLAW
            else -> TailnetServiceKind.ZEROCLAW
        }

    /**
     * Dispatches a matched @alias message to the target peer agent via FFI.
     *
     * Persists the user input, sends the stripped message to the peer on
     * [Dispatchers.IO], and appends the peer response or a reachability error
     * to the terminal. Returns early without invoking normal agent processing.
     *
     * @param displayText The original raw input shown in the scrollback.
     * @param match The alias match result containing peer info and stripped message.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun dispatchPeerMessage(
        displayText: String,
        match: PeerMatchResult,
    ) {
        loadingState.value = true
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            try {
                val token =
                    withContext(Dispatchers.IO) {
                        getPeerToken(match.peer.ip, match.peer.port)
                    }
                val kind = serviceKindFromString(match.peer.kind)
                val response =
                    withContext(Dispatchers.IO) {
                        peerSendMessage(
                            ip = match.peer.ip,
                            port = match.peer.port.toUShort(),
                            kind = kind,
                            token = token,
                            message = match.strippedMessage,
                        )
                    }
                repository.append(
                    content = "{@${match.alias}} $response",
                    entryType = ENTRY_TYPE_RESPONSE,
                )
                if (_speakRepliesEnabled.value && response.isNotBlank()) {
                    _lastAgentResponse.value = response
                }
            } catch (e: FfiException) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "Peer send failed: $sanitized")
                repository.append(
                    content = "{@${match.alias}} Unreachable: $sanitized",
                    entryType = ENTRY_TYPE_ERROR,
                )
            } catch (e: Exception) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "Peer send failed: $sanitized")
                repository.append(
                    content = "{@${match.alias}} Unreachable: $sanitized",
                    entryType = ENTRY_TYPE_ERROR,
                )
            } finally {
                loadingState.value = false
            }
        }
    }

    /**
     * Dispatches a chat message through the live agent session.
     *
     * Both text-only and vision (image-attached) messages are routed
     * through [executeAgentTurn]. Images are passed as base64 data and
     * MIME types to the FFI layer where they are embedded as `[IMAGE:...]`
     * markers for the upstream provider.
     *
     * @param displayText The original user input shown in the scrollback.
     * @param escapedText The Rhai-escaped message text (unused for agent path).
     */
    @Suppress("UnusedParameter")
    private fun executeChatMessage(
        displayText: String,
        escapedText: String,
    ) {
        val images = pendingImagesState.value
        pendingImagesState.value = emptyList()

        val inputImageUris = images.map { it.originalUri }
        viewModelScope.launch {
            repository.append(
                content = displayText,
                entryType = ENTRY_TYPE_INPUT,
                imageUris = inputImageUris,
            )

            if (images.isNotEmpty()) {
                logRepository.append(
                    LogSeverity.DEBUG,
                    TAG,
                    "Images attached — routing to smart image path",
                )
                executeSmartImageRoute(displayText, images.first())
                return@launch
            }

            val chatProviderConfigured = isChatProviderConfigured()
            if (handleManagedAuthFallback(chatProviderConfigured)) return@launch

            val decision =
                decideDefaultChatRouting(
                    hasConfiguredCloudProvider = chatProviderConfigured,
                    query = displayText,
                    hasImageAttachment = false,
                )

            if (decision is RoutingDecision.Local) {
                logRepository.append(LogSeverity.DEBUG, TAG, "Routing to Nano: ${decision.reason}")
                loadingState.value = true
                executeNanoGeneral(displayText)
                loadingState.value = false
                return@launch
            }

            logRepository.append(LogSeverity.DEBUG, TAG, "Routing to cloud: ${(decision as RoutingDecision.Cloud).reason}")

            if (!chatProviderConfigured) {
                repository.append(
                    content = NO_PROVIDER_WARNING,
                    entryType = ENTRY_TYPE_SYSTEM,
                )
                return@launch
            }

            executeAgentTurn(displayText, images)
        }
    }

    /**
     * Shows a warning when only managed (non-daemon) auth is connected.
     *
     * @return `true` if the warning was shown and the caller should abort.
     */
    private suspend fun handleManagedAuthFallback(chatProviderConfigured: Boolean): Boolean {
        if (chatProviderConfigured) return false
        val warning = unsupportedManagedChatWarning() ?: return false
        logRepository.append(LogSeverity.DEBUG, TAG, "Managed auth connected but not daemon-usable")
        repository.append(content = warning, entryType = ENTRY_TYPE_SYSTEM)
        return true
    }

    /**
     * Executes a user message through the live agent session.
     *
     * Sends the message to the Rust-side agent loop via [sessionSend].
     * Progress events are delivered to [_streamingState] through the
     * [KotlinSessionListener] callback. On completion, the full response
     * is persisted to the terminal repository.
     *
     * Images are passed as separate lists of base64 data and MIME types.
     * The Rust layer composes `[IMAGE:...]` markers so the upstream
     * provider can convert them to multimodal content parts.
     *
     * @param message The message text to send to the agent.
     * @param images Attached images to include in the request.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun executeAgentTurn(
        message: String,
        images: List<ProcessedImage> = emptyList(),
    ) {
        val reportedSessionError = AtomicReference<String?>(null)
        viewModelScope.launch {
            _streamingState.update { StreamingState(phase = StreamingPhase.THINKING) }

            try {
                withContext(Dispatchers.IO) {
                    check(ensureSession()) {
                        "No active session — is the daemon running?"
                    }
                    sessionSend(
                        message,
                        images.map { it.base64Data },
                        images.map { it.mimeType },
                        KotlinSessionListener(reportedSessionError),
                    )
                }
            } catch (e: FfiException) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                persistAgentTurnFailureIfNeeded(sanitized, reportedSessionError.get())
            } catch (e: Exception) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                persistAgentTurnFailureIfNeeded(sanitized, reportedSessionError.get())
            } finally {
                _streamingState.update { current ->
                    if (current.phase.isActive) {
                        StreamingState(
                            phase = StreamingPhase.ERROR,
                            errorMessage = "Session ended unexpectedly",
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    /**
     * Persists an agent-turn failure unless the session listener already
     * surfaced the same error to the terminal.
     *
     * The Rust session loop reports agent failures through
     * [FfiSessionListener.onError] and then returns an [FfiException].
     * This guard keeps session-send failures to a single terminal error
     * entry while still surfacing setup failures that occur before the
     * listener can run.
     *
     * @param sanitizedError Error text already sanitized for UI display.
     * @param listenerError Sanitized error previously emitted by the listener, if any.
     */
    private suspend fun persistAgentTurnFailureIfNeeded(
        sanitizedError: String,
        listenerError: String?,
    ) {
        if (!shouldPersistAgentTurnFailure(listenerError, sanitizedError)) {
            return
        }

        _streamingState.update {
            StreamingState(phase = StreamingPhase.ERROR, errorMessage = sanitizedError)
        }
        logRepository.append(LogSeverity.ERROR, TAG, "Agent turn failed: $sanitizedError")
        repository.append(content = sanitizedError, entryType = ENTRY_TYPE_ERROR)
    }

    /**
     * Cancels the currently running agent turn.
     *
     * Signals the Rust-side cancellation token. The [KotlinSessionListener]
     * will receive [FfiSessionListener.onCancelled] and transition the
     * streaming state to [StreamingPhase.CANCELLED].
     */
    @Suppress("TooGenericExceptionCaught")
    fun cancelAgentTurn() {
        try {
            sessionCancel()
        } catch (e: Exception) {
            logRepository.append(LogSeverity.WARN, TAG, "Cancel failed: ${e.message}")
        }
    }

    /**
     * Handles a photo captured by the camera preview sheet.
     *
     * Persists the user prompt as a terminal input, then routes the
     * captured image and prompt through the live agent session via
     * [executeAgentTurn]. Resets the camera visibility state after
     * processing.
     *
     * @param image The [ProcessedImage] captured by the camera.
     * @param prompt The text prompt to send alongside the image.
     */
    fun handleCameraCapture(
        image: ProcessedImage,
        prompt: String,
    ) {
        _showCamera.value = false
        val displayText = "/camera $prompt"
        viewModelScope.launch {
            repository.append(
                content = displayText,
                entryType = ENTRY_TYPE_INPUT,
                imageUris = listOf(image.originalUri),
            )

            executeSmartImageRoute(prompt, image)
        }
    }

    /**
     * Routes an image through smart description and vision-aware dispatch.
     *
     * Always runs an on-device Nano description first. Then checks whether
     * the cloud provider supports vision via [getProviderSupportsVision].
     * If vision is supported, the image is forwarded to the cloud with the
     * original prompt. If not, only the Nano description is shown.
     *
     * @param prompt The user's text prompt accompanying the image.
     * @param image The processed image to describe and optionally forward.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeSmartImageRoute(
        prompt: String,
        image: ProcessedImage,
    ) {
        loadingState.value = true
        try {
            val bytes = android.util.Base64.decode(image.base64Data, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                val descBuilder = StringBuilder()
                imageDescriberBridge
                    .describe(bitmap)
                    .catch { e ->
                        logRepository.append(
                            LogSeverity.WARN,
                            TAG,
                            "Nano image description failed: ${e.message}",
                        )
                    }.collect { chunk -> descBuilder.append(chunk) }

                if (descBuilder.isNotEmpty()) {
                    repository.append(
                        content =
                            tagProvider(
                                ProviderIconRegistry.NANO_PROVIDER,
                                "\uD83D\uDCF7 ${descBuilder.toString().trim()}",
                            ),
                        entryType = ENTRY_TYPE_RESPONSE,
                    )
                }
            }

            val cloudSupportsVision =
                try {
                    withContext(Dispatchers.IO) { getProviderSupportsVision() }
                } catch (_: Exception) {
                    false
                }

            if (cloudSupportsVision && isChatProviderConfigured()) {
                executeAgentTurn(prompt, listOf(image))
            }
        } catch (e: Exception) {
            repository.append(
                content = "Image processing failed: ${e.message}",
                entryType = ENTRY_TYPE_ERROR,
            )
        } finally {
            loadingState.value = false
        }
    }

    /**
     * Dismisses the camera preview sheet without capturing.
     */
    fun dismissCamera() {
        _showCamera.value = false
        _cameraPrompt.value = DEFAULT_CAMERA_PROMPT
    }

    /**
     * Checks whether the first enabled agent has a usable chat provider.
     *
     * Mirrors the `resolveEffectiveDefaults` pattern from
     * [ZeroAIDaemonService][com.zeroclaw.android.service.ZeroAIDaemonService].
     * Providers with [ProviderAuthType.URL_ONLY], [ProviderAuthType.URL_AND_OPTIONAL_KEY],
     * or [ProviderAuthType.NONE] are considered configured without an API key.
     * Providers requiring a key ([ProviderAuthType.API_KEY_ONLY],
     * [ProviderAuthType.API_KEY_OR_OAUTH]) are only considered configured when
     * a non-blank key exists in the repository.
     *
     * @return True if a chat provider is ready for use.
     */
    private suspend fun isChatProviderConfigured(): Boolean = resolveEffectiveChatProviderId() != null

    /**
     * Resolves the provider ID currently usable for terminal chat routing.
     *
     * Mirrors the daemon's slot-aware precedence and only returns providers
     * whose credentials are usable by the current live daemon session.
     *
     * @return Normalized provider ID for attribution, or null when no chat
     *   provider is currently usable.
     */
    private suspend fun resolveEffectiveChatProviderId(): String? {
        val agents = agentRepository.agents.first()
        val authProfiles = AuthProfileStore.listStandalone(app)
        val primary =
            SlotAwareAgentConfig
                .orderedConfiguredAgents(agents)
                .firstOrNull { agent ->
                    val providerInfo = ProviderRegistry.findById(agent.provider) ?: return@firstOrNull false
                    when (providerInfo.authType) {
                        ProviderAuthType.URL_ONLY,
                        ProviderAuthType.URL_AND_OPTIONAL_KEY,
                        ProviderAuthType.NONE,
                        -> true
                        ProviderAuthType.API_KEY_ONLY,
                        ProviderAuthType.API_KEY_OR_OAUTH,
                        -> {
                            SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
                                provider = agent.provider,
                                apiKey = apiKeyRepository.getByProvider(agent.provider),
                                authProfiles = authProfiles,
                            )
                        }
                    }
                } ?: return null
        return SlotAwareAgentConfig.configProvider(primary)
    }

    private suspend fun unsupportedManagedChatWarning(): String? {
        val agents = agentRepository.agents.first()
        val authProfiles = AuthProfileStore.listStandalone(app)
        val providerLabel =
            SlotAwareAgentConfig
                .orderedConfiguredAgents(agents)
                .firstNotNullOfOrNull { agent ->
                    val providerInfo = ProviderRegistry.findById(agent.provider) ?: return@firstNotNullOfOrNull null
                    if (providerInfo.authType != ProviderAuthType.API_KEY_OR_OAUTH) {
                        return@firstNotNullOfOrNull null
                    }
                    if (
                        SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
                            provider = agent.provider,
                            apiKey = apiKeyRepository.getByProvider(agent.provider),
                            authProfiles = authProfiles,
                        )
                    ) {
                        return@firstNotNullOfOrNull null
                    }
                    SlotAwareAgentConfig.connectedManagedAuthDisplayLabel(
                        provider = agent.provider,
                        authProfiles = authProfiles,
                    )
                } ?: return null
        return when (providerLabel) {
            "ChatGPT" ->
                "ChatGPT login is connected, but terminal chat still needs a direct OpenAI API key " +
                    "today. Use the OpenAI API slot or /nano for now."
            "Claude Code" ->
                "Claude Code login is connected, but terminal chat still needs a direct " +
                    "Anthropic API key today. Use the Anthropic API slot or /nano for now."
            else -> null
        }
    }

    /**
     * Handles a local action that does not require FFI evaluation.
     *
     * @param action The action identifier (e.g. "help", "clear").
     */
    private fun handleLocalAction(action: String) {
        when (action) {
            "help" -> showHelp()
            "clear" -> clearTerminal()
            "camera" -> showCameraSheet()
            "screenshot" -> handleScreenshot()
            "location" -> handleLocation()
            "location-watch" -> handleLocationWatch()
            "notify" -> handleNotify()
            "ssh-keys" ->
                viewModelScope.launch {
                    repository.append(
                        content = "Open Settings \u2192 SSH Keys to manage your keys.",
                        entryType = ENTRY_TYPE_SYSTEM,
                    )
                }
        }
    }

    /**
     * Shows the camera preview sheet for photo capture.
     *
     * Extracts the optional prompt argument from the last submitted
     * input. If no prompt is provided, uses [DEFAULT_CAMERA_PROMPT].
     */
    private fun showCameraSheet() {
        val lastInput = _history.value.lastOrNull().orEmpty()
        val promptArg =
            lastInput
                .removePrefix("/camera")
                .trim()
        _cameraPrompt.value = promptArg.ifBlank { DEFAULT_CAMERA_PROMPT }
        _showCamera.value = true
    }

    /**
     * Handles the `/screenshot` command.
     *
     * Extracts the optional prompt argument and initiates screen capture
     * via the [ScreenCaptureBridge]. If the bridge already holds a valid
     * permission token, capture proceeds immediately. Otherwise, emits
     * [requestScreenCapture] to signal the UI layer to launch the system
     * consent dialog.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun handleScreenshot() {
        val lastInput = _history.value.lastOrNull().orEmpty()
        val promptArg = lastInput.removePrefix("/screenshot").trim()
        val prompt = promptArg.ifBlank { DEFAULT_SCREENSHOT_PROMPT }
        screenshotPromptState.value = prompt

        viewModelScope.launch {
            repository.append(content = "/screenshot $prompt", entryType = ENTRY_TYPE_INPUT)

            if (screenCaptureBridge.hasPermission.value) {
                executeScreenCapture(prompt)
            } else {
                _requestScreenCapture.value = true
            }
        }
    }

    /**
     * Called by the UI layer after the screen capture consent dialog returns.
     *
     * Forwards the result to [ScreenCaptureBridge.handlePermissionResult]
     * and, on success, proceeds with the pending capture.
     *
     * @param resultCode The activity result code from the consent dialog.
     * @param data The [Intent] data from the consent dialog, or null on denial.
     */
    fun onScreenCaptureResult(
        resultCode: Int,
        data: Intent?,
    ) {
        _requestScreenCapture.value = false
        screenCaptureBridge.handlePermissionResult(resultCode, data)

        if (screenCaptureBridge.hasPermission.value) {
            viewModelScope.launch {
                executeScreenCapture(screenshotPromptState.value)
            }
        } else {
            viewModelScope.launch {
                repository.append(
                    content = "Screen capture permission denied.",
                    entryType = ENTRY_TYPE_ERROR,
                )
            }
        }
    }

    /**
     * Captures the screen and sends it to the agent as a vision message.
     *
     * @param prompt The user prompt to accompany the screenshot.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeScreenCapture(prompt: String) {
        repository.append(
            content =
                MiniZeroCanvasPayloads.typingStatus(
                    label = "Capturing screen\u2026",
                    detail = "Mini Zero is grabbing the current screen for the next turn.",
                ),
            entryType = ENTRY_TYPE_RESPONSE,
        )
        try {
            val result = screenCaptureBridge.captureScreen(app)
            result.fold(
                onSuccess = { image ->
                    executeSmartImageRoute(prompt, image)
                },
                onFailure = { error ->
                    repository.append(
                        content = "Screen capture failed: ${error.message}",
                        entryType = ENTRY_TYPE_ERROR,
                    )
                },
            )
        } catch (e: Exception) {
            repository.append(
                content = "Screen capture failed: ${e.message}",
                entryType = ENTRY_TYPE_ERROR,
            )
        }
    }

    /**
     * Dispatches a classified [NanoIntent] to the appropriate on-device bridge.
     *
     * Routes each intent variant to its handler:
     * - [NanoIntent.General] → multi-turn chat via [OnDeviceInferenceBridge]
     * - [NanoIntent.Summarize] → [OnDeviceSummarizerBridge]
     * - [NanoIntent.Proofread] → [OnDeviceProofreaderBridge]
     * - [NanoIntent.Rewrite] → [OnDeviceRewriterBridge]
     * - [NanoIntent.Describe] → [OnDeviceImageDescriberBridge]
     *
     * When a text-based intent has blank text, the last agent response is
     * substituted automatically.
     *
     * @param displayText The original user input shown in the scrollback.
     * @param intent The classified intent from [NanoIntentParser].
     */
    private fun executeNanoIntent(
        displayText: String,
        intent: NanoIntent,
    ) {
        loadingState.value = true
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            when (intent) {
                is NanoIntent.General -> executeNanoGeneral(intent.prompt)
                is NanoIntent.Summarize -> executeNanoSummarize(intent)
                is NanoIntent.Proofread -> executeNanoProofread(intent)
                is NanoIntent.Rewrite -> executeNanoRewrite(intent)
                is NanoIntent.Describe -> executeNanoDescribe()
            }
            loadingState.value = false
        }
    }

    /**
     * Executes a general-purpose Nano prompt through multi-turn chat.
     *
     * Uses [OnDeviceInferenceBridge.sendChatMessage] for multi-turn
     * conversation history, falling back to single-shot if the prompt
     * is empty (lists chat status).
     *
     * @param prompt The user's raw prompt text.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeNanoGeneral(prompt: String) {
        if (prompt.isBlank()) {
            val count = onDeviceBridge.chatTurnCount
            val status =
                if (count > 0) {
                    "Nano chat active ($count turns). Send a message or use `/nano` subcommands."
                } else {
                    "Nano ready. Type `/nano <message>` to start a conversation."
                }
            repository.append(
                content = tagProvider(ProviderIconRegistry.NANO_PROVIDER, status),
                entryType = ENTRY_TYPE_RESPONSE,
            )
            return
        }
        try {
            val builder = StringBuilder()
            var errorHandled = false
            onDeviceBridge
                .sendChatMessage(prompt)
                .catch { e ->
                    errorHandled = true
                    repository.append(
                        content = "Nano chat failed: ${e.message}",
                        entryType = ENTRY_TYPE_ERROR,
                    )
                }.collect { chunk -> builder.append(chunk) }
            if (!errorHandled && builder.isNotEmpty()) {
                val display = builder.toString().ifBlank { EMPTY_RESPONSE_FALLBACK }
                repository.append(
                    content = tagProvider(ProviderIconRegistry.NANO_PROVIDER, display),
                    entryType = ENTRY_TYPE_RESPONSE,
                )
                if (_speakRepliesEnabled.value && display.isNotBlank()) {
                    _lastAgentResponse.value = display
                }
            }
        } catch (e: Exception) {
            repository.append(
                content = "Nano chat failed: ${e.message}",
                entryType = ENTRY_TYPE_ERROR,
            )
        }
    }

    /**
     * Executes a summarization request via [OnDeviceSummarizerBridge].
     *
     * Substitutes the last agent response if no text is provided.
     *
     * @param intent The summarize intent with text and conversation flag.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeNanoSummarize(intent: NanoIntent.Summarize) {
        val text = intent.text.ifBlank { _lastAgentResponse.value.orEmpty() }
        if (text.isBlank()) {
            repository.append(
                content = "Nothing to summarize. Provide text or get a response first.",
                entryType = ENTRY_TYPE_ERROR,
            )
            return
        }
        try {
            val builder = StringBuilder()
            var errorHandled = false
            summarizerBridge
                .summarize(text, intent.isConversation)
                .catch { e ->
                    errorHandled = true
                    repository.append(
                        content = "Summarization failed: ${e.message}",
                        entryType = ENTRY_TYPE_ERROR,
                    )
                }.collect { chunk -> builder.append(chunk) }
            if (!errorHandled && builder.isNotEmpty()) {
                val display = builder.toString().ifBlank { EMPTY_RESPONSE_FALLBACK }
                repository.append(
                    content = tagProvider(ProviderIconRegistry.NANO_PROVIDER, display),
                    entryType = ENTRY_TYPE_RESPONSE,
                )
                if (_speakRepliesEnabled.value && display.isNotBlank()) {
                    _lastAgentResponse.value = display
                }
            }
        } catch (e: Exception) {
            repository.append(
                content = "Summarization failed: ${e.message}",
                entryType = ENTRY_TYPE_ERROR,
            )
        }
    }

    /**
     * Executes a proofreading request via [OnDeviceProofreaderBridge].
     *
     * Substitutes the last agent response if no text is provided.
     *
     * @param intent The proofread intent with text and voice input flag.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeNanoProofread(intent: NanoIntent.Proofread) {
        val text = intent.text.ifBlank { _lastAgentResponse.value.orEmpty() }
        if (text.isBlank()) {
            repository.append(
                content = "Nothing to proofread. Provide text or get a response first.",
                entryType = ENTRY_TYPE_ERROR,
            )
            return
        }
        try {
            val result = proofreaderBridge.proofread(text, intent.isVoiceInput)
            result.fold(
                onSuccess = { corrected ->
                    val display = corrected.ifBlank { EMPTY_RESPONSE_FALLBACK }
                    repository.append(
                        content = tagProvider(ProviderIconRegistry.NANO_PROVIDER, display),
                        entryType = ENTRY_TYPE_RESPONSE,
                    )
                    if (_speakRepliesEnabled.value && display.isNotBlank()) {
                        _lastAgentResponse.value = display
                    }
                },
                onFailure = { error ->
                    repository.append(
                        content = "Proofreading failed: ${error.message}",
                        entryType = ENTRY_TYPE_ERROR,
                    )
                },
            )
        } catch (e: Exception) {
            repository.append(
                content = "Proofreading failed: ${e.message}",
                entryType = ENTRY_TYPE_ERROR,
            )
        }
    }

    /**
     * Executes a rewriting request via [OnDeviceRewriterBridge].
     *
     * Substitutes the last agent response if no text is provided.
     *
     * @param intent The rewrite intent with text and style.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeNanoRewrite(intent: NanoIntent.Rewrite) {
        val text = intent.text.ifBlank { _lastAgentResponse.value.orEmpty() }
        if (text.isBlank()) {
            repository.append(
                content = "Nothing to rewrite. Provide text or get a response first.",
                entryType = ENTRY_TYPE_ERROR,
            )
            return
        }
        try {
            val builder = StringBuilder()
            var errorHandled = false
            rewriterBridge
                .rewrite(text, intent.style)
                .catch { e ->
                    errorHandled = true
                    repository.append(
                        content = "Rewrite (${intent.style.displayName}) failed: ${e.message}",
                        entryType = ENTRY_TYPE_ERROR,
                    )
                }.collect { chunk -> builder.append(chunk) }
            if (!errorHandled && builder.isNotEmpty()) {
                val display = builder.toString().ifBlank { EMPTY_RESPONSE_FALLBACK }
                repository.append(
                    content = tagProvider(ProviderIconRegistry.NANO_PROVIDER, display),
                    entryType = ENTRY_TYPE_RESPONSE,
                )
                if (_speakRepliesEnabled.value && display.isNotBlank()) {
                    _lastAgentResponse.value = display
                }
            }
        } catch (e: Exception) {
            repository.append(
                content = "Rewrite (${intent.style.displayName}) failed: ${e.message}",
                entryType = ENTRY_TYPE_ERROR,
            )
        }
    }

    /**
     * Executes an image description request via [OnDeviceImageDescriberBridge].
     *
     * Uses the first pending image attachment. If no image is pending,
     * shows an error prompting the user to attach one first.
     */
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    private suspend fun executeNanoDescribe() {
        val images = pendingImagesState.value
        if (images.isEmpty()) {
            repository.append(
                content = "No image attached. Use the photo picker or `/camera` first.",
                entryType = ENTRY_TYPE_ERROR,
            )
            return
        }
        val image = images.first()
        pendingImagesState.value = images.drop(1)
        try {
            val bytes = android.util.Base64.decode(image.base64Data, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                repository.append(
                    content = "Failed to decode image for description.",
                    entryType = ENTRY_TYPE_ERROR,
                )
                return
            }
            try {
                val builder = StringBuilder()
                var errorHandled = false
                imageDescriberBridge
                    .describe(bitmap)
                    .catch { e ->
                        errorHandled = true
                        repository.append(
                            content = "Image description failed: ${e.message}",
                            entryType = ENTRY_TYPE_ERROR,
                        )
                    }.collect { chunk -> builder.append(chunk) }
                if (!errorHandled && builder.isNotEmpty()) {
                    val display = builder.toString().ifBlank { EMPTY_RESPONSE_FALLBACK }
                    repository.append(
                        content =
                            tagProvider(
                                ProviderIconRegistry.NANO_PROVIDER,
                                display,
                            ),
                        entryType = ENTRY_TYPE_RESPONSE,
                    )
                    if (_speakRepliesEnabled.value && display.isNotBlank()) {
                        _lastAgentResponse.value = display
                    }
                }
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            repository.append(
                content = "Image description failed: ${e.message}",
                entryType = ENTRY_TYPE_ERROR,
            )
        }
    }

    /**
     * Handles the `/location` command.
     *
     * Fetches the current device location via [LocationBridge] and
     * displays the result as a readable string in the terminal.
     * Requests location permissions if not yet granted.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun handleLocation() {
        if (!hasLocationPermission()) {
            pendingLocationWatch = false
            _requestLocationPermission.value = true
            viewModelScope.launch {
                repository.append(content = "/location", entryType = ENTRY_TYPE_INPUT)
            }
            return
        }

        viewModelScope.launch {
            repository.append(content = "/location", entryType = ENTRY_TYPE_INPUT)
            try {
                val result = locationBridge.getCurrentLocation()
                result.fold(
                    onSuccess = { location ->
                        repository.append(
                            content = location.toReadableString(),
                            entryType = ENTRY_TYPE_RESPONSE,
                        )
                    },
                    onFailure = { error ->
                        val message =
                            when (error) {
                                is SecurityException ->
                                    "Location permission not granted. Grant ACCESS_FINE_LOCATION in app settings."
                                else -> "Location unavailable: ${error.message}"
                            }
                        repository.append(content = message, entryType = ENTRY_TYPE_ERROR)
                    },
                )
            } catch (e: Exception) {
                repository.append(
                    content = "Location failed: ${e.message}",
                    entryType = ENTRY_TYPE_ERROR,
                )
            }
        }
    }

    /**
     * Handles the `/location-watch` command.
     *
     * Toggles continuous location updates on or off. When active,
     * location fixes are appended to the terminal as system messages.
     * Requests location permissions if not yet granted.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun handleLocationWatch() {
        if (!hasLocationPermission() && !_locationWatchActive.value) {
            pendingLocationWatch = true
            _requestLocationPermission.value = true
            viewModelScope.launch {
                repository.append(content = "/location-watch", entryType = ENTRY_TYPE_INPUT)
            }
            return
        }

        viewModelScope.launch {
            repository.append(content = "/location-watch", entryType = ENTRY_TYPE_INPUT)

            if (_locationWatchActive.value) {
                locationBridge.stopLocationUpdates()
                _locationWatchActive.value = false
                repository.append(
                    content = "Location tracking stopped.",
                    entryType = ENTRY_TYPE_SYSTEM,
                )
            } else {
                try {
                    locationBridge.startLocationUpdates { location ->
                        viewModelScope.launch {
                            repository.append(
                                content = "Location: ${location.toReadableString()}",
                                entryType = ENTRY_TYPE_SYSTEM,
                            )
                        }
                    }
                    _locationWatchActive.value = true
                    repository.append(
                        content = "Location tracking started (60s interval, balanced power).",
                        entryType = ENTRY_TYPE_SYSTEM,
                    )
                } catch (e: Exception) {
                    repository.append(
                        content = "Location watch failed: ${e.message}",
                        entryType = ENTRY_TYPE_ERROR,
                    )
                }
            }
        }
    }

    /**
     * Handles the `/notify` command.
     *
     * Parses title and body arguments and posts an Android notification
     * via [AgentNotificationBridge].
     */
    private fun handleNotify() {
        val lastInput = _history.value.lastOrNull().orEmpty()
        val argsText = lastInput.removePrefix("/notify").trim()

        viewModelScope.launch {
            repository.append(content = "/notify $argsText", entryType = ENTRY_TYPE_INPUT)

            if (argsText.isBlank()) {
                repository.append(
                    content = "Usage: /notify <title> <body>",
                    entryType = ENTRY_TYPE_SYSTEM,
                )
                return@launch
            }

            val parts = argsText.split(" ", limit = 2)
            val title = parts.first()
            val body = parts.getOrElse(1) { "" }

            val notificationId = notificationBridge.notify(title, body)
            repository.append(
                content = "Notification posted (id=$notificationId).",
                entryType = ENTRY_TYPE_SYSTEM,
            )
        }
    }

    /**
     * Starts or stops microphone listening via the [VoiceBridge].
     *
     * Requests `RECORD_AUDIO` permission if not yet granted. Spoken reply
     * playback is configured separately through [setSpeakRepliesEnabled].
     */
    fun toggleVoice() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            _requestAudioPermission.value = true
            return
        }

        val currentState = voiceBridge.state.value
        when (currentState) {
            is VoiceState.Idle, is VoiceState.Error -> voiceBridge.startListening()
            is VoiceState.Listening -> voiceBridge.stopListening()
            is VoiceState.Speaking -> voiceBridge.stopSpeaking()
            is VoiceState.Processing -> { /* wait for result */ }
        }
    }

    /**
     * Enables or disables spoken assistant replies.
     *
     * When disabled, any in-flight TTS playback is stopped immediately.
     *
     * @param enabled True to speak future assistant replies aloud.
     */
    fun setSpeakRepliesEnabled(enabled: Boolean) {
        _speakRepliesEnabled.value = enabled
        if (!enabled) {
            _lastAgentResponse.value = null
            voiceBridge.stopSpeaking()
        }
    }

    /**
     * Stops any active microphone capture or TTS playback.
     */
    fun stopVoice() {
        voiceBridge.stopListening()
        voiceBridge.stopSpeaking()
    }

    /**
     * Called by the UI layer after `RECORD_AUDIO` permission result.
     *
     * If granted, immediately starts voice listening. Otherwise posts
     * an error to the terminal.
     *
     * @param granted Whether the permission was granted.
     */
    fun onAudioPermissionResult(granted: Boolean) {
        _requestAudioPermission.value = false
        if (granted) {
            voiceBridge.startListening()
        } else {
            viewModelScope.launch {
                repository.append(
                    content = "Microphone permission denied. Voice mode requires RECORD_AUDIO.",
                    entryType = ENTRY_TYPE_ERROR,
                )
            }
        }
    }

    /**
     * Called by the UI layer after location permission result.
     *
     * If granted, resumes the pending location operation (single fix
     * or continuous watch). Otherwise posts an error to the terminal.
     *
     * @param granted Whether at least coarse location was granted.
     */
    fun onLocationPermissionResult(granted: Boolean) {
        _requestLocationPermission.value = false
        if (granted) {
            if (pendingLocationWatch) {
                handleLocationWatch()
            } else {
                handleLocation()
            }
        } else {
            viewModelScope.launch {
                repository.append(
                    content = "Location permission denied. Grant location access in app settings.",
                    entryType = ENTRY_TYPE_ERROR,
                )
            }
        }
    }

    /**
     * Consumes the screen capture request signal.
     *
     * Called by the UI layer immediately before launching the consent
     * dialog to prevent double-fire on recomposition.
     */
    fun consumeScreenCaptureRequest() {
        _requestScreenCapture.value = false
    }

    /**
     * Consumes the audio permission request signal.
     *
     * Called by the UI layer immediately before launching the permission
     * dialog to prevent double-fire on recomposition.
     */
    fun consumeAudioPermissionRequest() {
        _requestAudioPermission.value = false
    }

    /**
     * Consumes the location permission request signal.
     *
     * Called by the UI layer immediately before launching the permission
     * dialog to prevent double-fire on recomposition.
     */
    fun consumeLocationPermissionRequest() {
        _requestLocationPermission.value = false
    }

    /**
     * Checks whether the given permission is granted.
     *
     * @param permission The permission string to check.
     * @return `true` if the permission is granted.
     */
    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether either fine or coarse location permission is granted.
     *
     * @return `true` if at least one location permission is granted.
     */
    private fun hasLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Speaks the given text via TTS and clears [lastAgentResponse].
     *
     * Called by the UI layer when it observes a non-null emission from
     * [lastAgentResponse]. Clears the flow value after initiating
     * playback to prevent duplicate reads.
     *
     * @param text The response text to speak.
     */
    fun speakResponse(text: String) {
        _lastAgentResponse.value = null
        voiceBridge.speak(text)
    }

    /**
     * Sends a canvas action string back to the agent as a chat message.
     *
     * Called when a user taps a button or chip in a rendered canvas frame.
     * The action is validated against [CANVAS_ACTION_PATTERN] and routed
     * directly as a chat message, bypassing [CommandRegistry] to prevent
     * agent-generated canvas actions from injecting slash commands or
     * Rhai expressions.
     *
     * @param action The action string from the canvas element.
     */
    fun handleCanvasAction(action: String) {
        if (action.isBlank()) return
        if (!CANVAS_ACTION_PATTERN.matches(action)) {
            viewModelScope.launch {
                repository.append(
                    content = MiniZeroCanvasPayloads.errorNotice("Invalid canvas action rejected."),
                    entryType = ENTRY_TYPE_RESPONSE,
                )
            }
            return
        }
        executeChatMessage(action, action)
    }

    /**
     * Formats discoverable packaged workspace scripts for terminal output.
     *
     * @param scripts Scripts returned by the Rust scripting manifest discovery path.
     * @return Human-readable multi-line summary.
     */
    private fun formatWorkspaceScriptList(scripts: List<FfiWorkspaceScript>): String =
        if (scripts.isEmpty()) {
            "No workspace or skill-packaged scripts were found."
        } else {
            buildString {
                appendLine("Workspace and skill-packaged scripts:")
                appendLine()
                scripts.forEach { script ->
                    appendLine("\u2022 ${script.relativePath} [${script.runtime}]")
                    appendLine("  Name: ${script.name}")
                    if (script.requestedCapabilities.isNotEmpty()) {
                        appendLine(
                            "  Capabilities: ${script.requestedCapabilities.joinToString(", ")}",
                        )
                    } else {
                        appendLine("  Capabilities: none")
                    }
                    if (script.triggerSummaries.isNotEmpty()) {
                        appendLine(
                            "  Triggers: ${script.triggerSummaries.joinToString("; ")}",
                        )
                    }
                    appendLine()
                }
            }.trimEnd()
        }

    /**
     * Formats a packaged-script validation result for terminal output.
     *
     * @param relativePath Script path relative to the workspace root.
     * @param validation Rust-side validation result.
     * @return Human-readable multi-line summary.
     */
    private fun formatScriptValidation(
        relativePath: String,
        validation: FfiScriptValidation,
    ): String =
        buildString {
            appendLine("Script validation")
            appendLine()
            appendLine("Path: $relativePath")
            appendLine("Manifest: ${validation.manifestName}")
            appendLine("Runtime: ${validation.runtime}")
            if (validation.requestedCapabilities.isNotEmpty()) {
                appendLine(
                    "Requested capabilities: ${validation.requestedCapabilities.joinToString(", ")}",
                )
            } else {
                appendLine("Requested capabilities: none")
            }
            if (validation.missingCapabilities.isNotEmpty()) {
                appendLine(
                    "Missing from manifest: ${validation.missingCapabilities.joinToString(", ")}",
                )
            }
            if (validation.warnings.isNotEmpty()) {
                appendLine("Warnings:")
                validation.warnings.forEach { warning ->
                    appendLine("  \u2022 $warning")
                }
            }
        }.trimEnd()

    /**
     * Generates and persists the help text listing all available commands.
     */
    private fun showHelp() {
        viewModelScope.launch {
            repository.append(content = "/help", entryType = ENTRY_TYPE_INPUT)
            val helpText =
                buildString {
                    appendLine("Available commands:")
                    appendLine()
                    for (command in CommandRegistry.commands) {
                        val usage =
                            if (command.usage.isNotEmpty()) {
                                " ${command.usage}"
                            } else {
                                ""
                            }
                        appendLine("  /${command.name}$usage")
                        appendLine("    ${command.description}")
                    }
                    appendLine()
                    append("Any other input is sent as a chat message.")
                }
            repository.append(content = helpText, entryType = ENTRY_TYPE_SYSTEM)
        }
    }

    /**
     * Clears all terminal history and adds a confirmation message.
     */
    private fun clearTerminal() {
        repository.clear()
        viewModelScope.launch {
            repository.append(
                content =
                    MiniZeroCanvasPayloads.successNotice(
                        title = "Terminal cleared",
                        message = CLEAR_CONFIRMATION,
                    ),
                entryType = ENTRY_TYPE_RESPONSE,
            )
        }
    }

    /**
     * Appends a non-blank input to the history buffer.
     *
     * Duplicate consecutive entries are suppressed to keep the history clean.
     *
     * @param text The input text to record.
     */
    private fun appendToHistory(text: String) {
        if (text.isBlank()) return
        val current = _history.value
        if (current.lastOrNull() == text) return
        _history.value = current + text
    }

    /**
     * Detects and handles a bind result from the REPL.
     *
     * When the user runs `bind("channel", "identity")` in the terminal,
     * the REPL returns a structured message. This method parses it,
     * persists the binding to Room, and restarts the daemon so the
     * channel picks up the new allowlist entry.
     *
     * @param response The raw REPL response string.
     */
    private suspend fun handleBindResult(response: String) {
        val match = BIND_RESULT_PATTERN.find(response) ?: return
        val (userId, channelKey, fieldName) = match.destructured

        val channels = app.channelConfigRepository.channels.first()
        val channel = channels.find { it.type.tomlKey == channelKey } ?: return

        val currentList = channel.configValues[fieldName].orEmpty()
        val entries = currentList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (userId in entries) return

        val updatedList = (entries + userId).joinToString(", ")
        val updatedValues = channel.configValues.toMutableMap()
        updatedValues[fieldName] = updatedList

        val updatedChannel = channel.copy(configValues = updatedValues)
        app.channelConfigRepository.save(
            updatedChannel,
            app.channelConfigRepository.getSecrets(channel.id),
        )

        restartDaemonWithCurrentConfig()
    }

    /**
     * Restarts the daemon by sending [ZeroAIDaemonService.ACTION_START].
     *
     * The service rebuilds its config from the current Room/DataStore state,
     * picking up any changes made by [handleBindResult].
     */
    @Suppress("TooGenericExceptionCaught")
    private fun restartDaemonWithCurrentConfig() {
        try {
            val intent =
                Intent(app, ZeroAIDaemonService::class.java)
                    .setAction(ZeroAIDaemonService.ACTION_START)
            app.startForegroundService(intent)
        } catch (e: Exception) {
            logRepository.append(LogSeverity.ERROR, TAG, "Failed to restart daemon after bind: ${e.message}")
        }
    }

    /**
     * Emits a [RefreshCommand] to trigger immediate data refresh in other
     * ViewModels after a successful mutating REPL command.
     *
     * @param expression The Rhai expression that was successfully evaluated.
     */
    private fun emitRefreshIfNeeded(expression: String) {
        val command =
            when {
                expression.startsWith("cron_add(") ||
                    expression.startsWith("cron_oneshot(") ||
                    expression.startsWith("cron_remove(") ||
                    expression.startsWith("cron_pause(") ||
                    expression.startsWith("cron_resume(") -> RefreshCommand.Cron
                expression.startsWith("send(") ||
                    expression.startsWith("send_vision(") -> RefreshCommand.Cost
                expression.startsWith("skill_install(") ||
                    expression.startsWith("skill_remove(") -> RefreshCommand.Health
                else -> null
            }
        if (command != null) {
            app.refreshCommands.tryEmit(command)
        }
    }

    /**
     * Callback adapter that translates FFI session events into
     * [StreamingState] updates and terminal repository entries.
     *
     * All methods are called from the tokio runtime thread. State updates
     * use [MutableStateFlow.update] which is thread-safe.
     */
    private inner class KotlinSessionListener(
        private val reportedError: AtomicReference<String?>,
    ) : FfiSessionListener {
        override fun onThinking(text: String) {
            _streamingState.update { current ->
                current.copy(
                    phase = StreamingPhase.THINKING,
                    thinkingText = current.thinkingText + text,
                )
            }
        }

        override fun onResponseChunk(text: String) {
            val cleaned = STREAMING_THINKING_TAG_REGEX.replace(text, "")
            if (cleaned.isEmpty()) return
            _streamingState.update { current ->
                current.copy(
                    phase = StreamingPhase.RESPONDING,
                    responseText = current.responseText + cleaned,
                    providerRound = 0,
                    toolCallCount = 0,
                    llmDurationSecs = 0,
                )
            }
        }

        override fun onToolStart(
            name: String,
            argumentsHint: String,
        ) {
            _streamingState.update { current ->
                current.copy(
                    phase = StreamingPhase.TOOL_EXECUTING,
                    activeTools = current.activeTools + ToolProgress(name, argumentsHint),
                )
            }
        }

        override fun onToolResult(
            name: String,
            success: Boolean,
            durationSecs: ULong,
        ) {
            _streamingState.update { current ->
                current.copy(
                    activeTools = current.activeTools.filter { it.name != name },
                    toolResults =
                        current.toolResults +
                            ToolResultEntry(
                                name = name,
                                success = success,
                                durationSecs = durationSecs.toLong(),
                            ),
                )
            }
        }

        override fun onToolOutput(
            name: String,
            output: String,
        ) {
            _streamingState.update { current ->
                val updated =
                    current.toolResults.map { entry ->
                        if (entry.name == name && entry.output.isEmpty()) {
                            entry.copy(output = output)
                        } else {
                            entry
                        }
                    }
                current.copy(toolResults = updated)
            }
        }

        override fun onProgress(phase: FfiProgressPhase) {
            _streamingState.update { current ->
                when (phase) {
                    is FfiProgressPhase.SearchingMemory ->
                        current.copy(
                            phase = StreamingPhase.SEARCHING_MEMORY,
                        )
                    is FfiProgressPhase.CallingProvider ->
                        current.copy(
                            phase = StreamingPhase.CALLING_PROVIDER,
                            providerRound = phase.round.toInt(),
                        )
                    is FfiProgressPhase.GotToolCalls ->
                        current.copy(
                            phase = StreamingPhase.TOOL_EXECUTING,
                            toolCallCount = phase.count.toInt(),
                            llmDurationSecs = phase.llmDurationSecs.toLong(),
                        )
                    is FfiProgressPhase.StreamingResponse ->
                        current.copy(
                            phase = StreamingPhase.RESPONDING,
                        )
                    is FfiProgressPhase.Compacting ->
                        current.copy(
                            phase = StreamingPhase.COMPACTING,
                        )
                    is FfiProgressPhase.Idle ->
                        current.copy(
                            phase = StreamingPhase.IDLE,
                        )
                    is FfiProgressPhase.Raw -> current
                }
            }
        }

        override fun onProgressClear() {
            _streamingState.update { current ->
                current.copy(
                    providerRound = 0,
                    toolCallCount = 0,
                    llmDurationSecs = 0,
                )
            }
        }

        override fun onCompaction(summary: String) {
            _streamingState.update { current ->
                current.copy(phase = StreamingPhase.COMPACTING)
            }
        }

        override fun onComplete(fullResponse: String) {
            val cleaned = stripToolCallTags(fullResponse)
            val stripped =
                if (cachedSettings.value.stripThinkingTags) {
                    stripThinkingTags(cleaned)
                } else {
                    cleaned
                }
            val display = stripped.ifBlank { EMPTY_RESPONSE_FALLBACK }

            viewModelScope.launch {
                val providerId = resolveEffectiveChatProviderId()
                repository.append(
                    content =
                        if (providerId != null) {
                            tagProvider(providerId, display)
                        } else {
                            display
                        },
                    entryType = ENTRY_TYPE_RESPONSE,
                )
            }

            _streamingState.update { StreamingState(phase = StreamingPhase.COMPLETE) }

            if (_speakRepliesEnabled.value && display.isNotBlank()) {
                _lastAgentResponse.value = display
            }

            app.refreshCommands.tryEmit(RefreshCommand.Cost)
        }

        override fun onError(error: String) {
            val sanitized = ErrorSanitizer.sanitizeMessage(error)
            reportedError.set(sanitized)

            viewModelScope.launch {
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
                logRepository.append(LogSeverity.ERROR, TAG, "Agent session error: $sanitized")
            }

            _streamingState.update {
                StreamingState(phase = StreamingPhase.ERROR, errorMessage = sanitized)
            }
        }

        override fun onCancelled() {
            viewModelScope.launch {
                repository.append(
                    content = MiniZeroCanvasPayloads.cancelledNotice(),
                    entryType = ENTRY_TYPE_RESPONSE,
                )
            }

            _streamingState.update {
                StreamingState(phase = StreamingPhase.CANCELLED)
            }
        }
    }

    /** Constants for [TerminalViewModel]. */
    companion object {
        private const val TAG = "Terminal"

        /** [SavedStateHandle] key for persisting TTY mode across tab switches. */
        private const val KEY_TTY_ACTIVE = "tty_active"

        /** Default PTY column count. */
        private const val TTY_DEFAULT_COLS = 80

        /** Default PTY row count. */
        private const val TTY_DEFAULT_ROWS = 24

        /** Timeout for the blocking render-data wait (ms). Also serves as
         *  the heartbeat interval for cursor blink and connection health. */
        private const val TTY_RENDER_WAIT_TIMEOUT_MS = 500L

        /** Maximum number of lines to retrieve per output snapshot. */
        private const val TTY_SNAPSHOT_MAX_LINES = 500

        /** Default terminal font size in sp. */
        private const val TTY_DEFAULT_FONT_SIZE = 14f

        /** Minimum terminal font size in sp, enforced by pinch-to-zoom. */
        private const val TTY_MIN_FONT_SIZE = 8f

        /** Maximum terminal font size in sp, enforced by pinch-to-zoom. */
        private const val TTY_MAX_FONT_SIZE = 32f

        /** Timeout in milliseconds before upstream Flow collection stops. */
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Maximum number of images per message (matches FFI-side limit). */
        private const val MAX_IMAGES = 5

        /** Sentinel value indicating no history selection is active. */
        private const val NO_HISTORY_SELECTION = -1

        /** Entry type constant for user input entries. */
        private const val ENTRY_TYPE_INPUT = "input"

        /** Entry type constant for daemon response entries. */
        private const val ENTRY_TYPE_RESPONSE = "response"

        /** Entry type constant for error entries. */
        private const val ENTRY_TYPE_ERROR = "error"

        /** Entry type constant for system message entries. */
        private const val ENTRY_TYPE_SYSTEM = "system"

        /** Subscription timeout for [peerAliases] flow collection. */
        private const val PEER_ALIAS_TIMEOUT = 5_000L

        /** Pattern matching valid SSH usernames (RFC 952 / common practice). */
        private val SSH_USER_PATTERN = Regex("""^[a-zA-Z0-9._-]{1,64}$""")

        /** Pattern matching valid SSH hostnames or IP addresses. */
        private val SSH_HOST_PATTERN = Regex("""^[a-zA-Z0-9._:-]{1,253}$""")

        /** Pattern matching the `/ssh user@host [-p port]` terminal command. */
        private val SSH_COMMAND_PATTERN = Regex("""^/ssh\s+(\S+)@(\S+)(?:\s+-p\s+(\d+))?\s*$""")

        /** Default SSH port when `-p` is not specified. */
        private const val SSH_DEFAULT_PORT = 22

        /** Polling interval in milliseconds for host key prompts during handshake. */
        private const val SSH_HOST_KEY_POLL_MS = 200L

        /** Maximum time in milliseconds to wait for an SSH handshake host key prompt. */
        private const val SSH_HANDSHAKE_TIMEOUT_MS = 30_000L

        /** Maximum valid TCP port number. */
        private const val SSH_MAX_PORT = 65535

        /** Runtime identifier for the currently executable embedded script engine. */
        private const val SCRIPT_RUNTIME_RHAI = "rhai"

        /** Prefix for provider attribution in response content. */
        private val PROVIDER_PREFIX_REGEX = Regex("""^\[provider:(\w+)]""")

        /**
         * Wraps response content with a provider attribution prefix.
         *
         * The prefix `[provider:ID]` is parsed by [toBlock] to set the
         * [TerminalBlock.Response.providerId] for icon rendering.
         *
         * @param providerId The provider identifier (e.g. "nano", "anthropic").
         * @param content The response text content.
         * @return Content prefixed with the provider tag.
         */
        private fun tagProvider(
            providerId: String,
            content: String,
        ): String = "[provider:$providerId]$content"

        /** Displayed when the model response is empty after stripping markup. */
        private const val EMPTY_RESPONSE_FALLBACK =
            "The model did not generate a text response."

        /** Confirmation message shown after clearing the terminal. */
        private const val CLEAR_CONFIRMATION = "Terminal cleared."

        /** Default prompt sent with camera captures when no prompt is specified. */
        private const val DEFAULT_CAMERA_PROMPT =
            "Describe what you see in this image."

        /** Default prompt sent with screen captures when no prompt is specified. */
        private const val DEFAULT_SCREENSHOT_PROMPT =
            "Describe what you see on this screen."

        /** Warning shown when user sends a chat message without a configured provider. */
        private const val NO_PROVIDER_WARNING =
            "No chat app is ready yet \u2014 use /help for admin commands, " +
                "or connect one in Agents."

        /**
         * Allowed pattern for canvas action strings.
         *
         * Restricts actions to alphanumeric identifiers, underscores,
         * hyphens, and spaces to prevent command injection from
         * agent-generated canvas content.
         */
        private val CANVAS_ACTION_PATTERN = Regex("^[a-zA-Z0-9_ -]{1,64}$")

        /**
         * Pattern matching chain-of-thought and internal reasoning tags
         * across models.
         *
         * Covers `<think>`, `<thinking>`, `<reasoning>`, `<commentary>`,
         * `<tool_output>`, `<analysis>`, `<reflection>`, and
         * `<inner_monologue>` tag pairs.
         */
        private val THINKING_TAG_REGEX =
            Regex(
                "<(?:think|thinking|reasoning|commentary|tool_output" +
                    "|analysis|reflection|inner_monologue)>" +
                    "[\\s\\S]*?" +
                    "</(?:think|thinking|reasoning|commentary|tool_output" +
                    "|analysis|reflection|inner_monologue)>",
                RegexOption.IGNORE_CASE,
            )

        /**
         * Pattern matching tool-call markup leaked by models that support
         * function calling.
         *
         * Matches self-closing tags (`<tool_call name="..." args="..."/>`),
         * complete blocks (`<tool_call>...</tool_call>`), and unclosed tags
         * through end-of-string. Also covers the `<function_call>` variant
         * used by some models.
         */
        private val TOOL_CALL_TAG_REGEX =
            Regex(
                "<(?:tool_call|function_call)\\b[\\s\\S]*?/>" +
                    "|<(?:tool_call|function_call)>[\\s\\S]*?</(?:tool_call|function_call)>" +
                    "|<(?:tool_call|function_call)[\\s\\S]*$",
                RegexOption.IGNORE_CASE,
            )

        /** Strips leaked thinking tags from streamed response chunks. */
        private val STREAMING_THINKING_TAG_REGEX =
            Regex(
                "</?(?:think|thinking|reasoning|analysis|reflection|inner_monologue)>",
                RegexOption.IGNORE_CASE,
            )

        /** Pattern matching successful REPL bind results. */
        val BIND_RESULT_PATTERN: Regex =
            Regex("""Bound (.+) to (\w+) \((\w+)\)\. Restart daemon to apply\.""")

        /**
         * Returns whether an exception caught around [sessionSend] still needs
         * to be persisted after the listener callback path.
         *
         * @param listenerError Sanitized error already emitted by the listener, if any.
         * @param caughtError Sanitized error caught from the FFI call.
         * @return `true` when the caught error still needs a terminal entry.
         */
        internal fun shouldPersistAgentTurnFailure(
            listenerError: String?,
            caughtError: String,
        ): Boolean = listenerError == null || listenerError != caughtError

        /**
         * Chooses the default route for a plain-text terminal message.
         *
         * When a cloud provider is configured, freeform chat should prefer the
         * live daemon session; users can still opt into on-device inference
         * explicitly with `/nano`. When no chat provider is ready, the local
         * Nano classifier remains available as a fallback.
         *
         * @param hasConfiguredCloudProvider Whether a daemon-backed chat provider is ready.
         * @param query Raw user message.
         * @param hasImageAttachment Whether the message includes image attachments.
         * @return Routing decision for the default terminal send path.
         */
        internal fun decideDefaultChatRouting(
            hasConfiguredCloudProvider: Boolean,
            query: String,
            hasImageAttachment: Boolean = false,
        ): RoutingDecision =
            if (hasConfiguredCloudProvider) {
                RoutingDecision.Cloud(reason = "configured cloud provider available")
            } else {
                QueryClassifier.classify(
                    query = query,
                    hasImageAttachment = hasImageAttachment,
                )
            }

        /**
         * Removes chain-of-thought and internal reasoning tags from a model response.
         *
         * Strips `<think>`, `<thinking>`, `<commentary>`, `<tool_output>`,
         * `<analysis>`, `<reflection>`, and `<inner_monologue>` blocks
         * emitted by reasoning models.
         *
         * @param text Raw model response.
         * @return Response with reasoning blocks removed and whitespace trimmed.
         */
        fun stripThinkingTags(text: String): String = text.replace(THINKING_TAG_REGEX, "").trim()

        /**
         * Removes tool-call markup from a model response.
         *
         * Some models (notably Qwen) emit raw `<tool_call>` tags in their
         * text content when they attempt a function call with no tools
         * available or produce a malformed tool invocation.
         *
         * @param text Raw model response.
         * @return Response with tool-call blocks removed and whitespace trimmed.
         */
        fun stripToolCallTags(text: String): String = text.replace(TOOL_CALL_TAG_REGEX, "").trim()

        /**
         * Encodes a [TtySpecialKey] into the raw bytes expected by a VT100
         * terminal, applying Ctrl and Alt modifiers when active.
         *
         * @param key The special key to encode.
         * @param ctrl Whether the Ctrl modifier is active.
         * @param alt Whether the Alt modifier is active.
         * @return Byte array to write to the PTY.
         */
        @Suppress("MagicNumber", "CyclomaticComplexMethod")
        fun encodeTtyKey(
            key: TtySpecialKey,
            ctrl: Boolean = false,
            alt: Boolean = false,
        ): ByteArray {
            val raw =
                when (key) {
                    TtySpecialKey.TAB -> byteArrayOf(0x09)
                    TtySpecialKey.ESC -> byteArrayOf(0x1B)
                    TtySpecialKey.UP -> byteArrayOf(0x1B, 0x5B, 0x41)
                    TtySpecialKey.DOWN -> byteArrayOf(0x1B, 0x5B, 0x42)
                    TtySpecialKey.RIGHT -> byteArrayOf(0x1B, 0x5B, 0x43)
                    TtySpecialKey.LEFT -> byteArrayOf(0x1B, 0x5B, 0x44)
                    TtySpecialKey.HOME -> byteArrayOf(0x1b, 0x5b, 0x48) // ESC [ H
                    TtySpecialKey.END -> byteArrayOf(0x1b, 0x5b, 0x46) // ESC [ F
                    TtySpecialKey.PAGE_UP -> byteArrayOf(0x1b, 0x5b, 0x35, 0x7e) // ESC [ 5 ~
                    TtySpecialKey.PAGE_DOWN -> byteArrayOf(0x1b, 0x5b, 0x36, 0x7e) // ESC [ 6 ~
                    TtySpecialKey.PIPE -> "|".toByteArray(Charsets.UTF_8)
                    TtySpecialKey.SLASH -> "/".toByteArray(Charsets.UTF_8)
                    TtySpecialKey.TILDE -> "~".toByteArray(Charsets.UTF_8)
                    TtySpecialKey.DASH -> "-".toByteArray(Charsets.UTF_8)
                    TtySpecialKey.ENTER -> byteArrayOf(0x0D) // CR
                    TtySpecialKey.CTRL, TtySpecialKey.ALT -> return byteArrayOf()
                }
            if (ctrl && raw.size == 1 && raw[0] in 0x40..0x7E) {
                return byteArrayOf((raw[0].toInt() and 0x1F).toByte())
            }
            if (alt && raw.size == 1) {
                return byteArrayOf(0x1B, raw[0])
            }
            return raw
        }

        /**
         * Maps a persisted [TerminalEntry] to a display [TerminalBlock].
         *
         * Response entries whose content starts with `{` or `[` are
         * classified as [TerminalBlock.Structured] for JSON rendering.
         *
         * @param entry The persisted terminal entry.
         * @return The corresponding display block.
         */
        fun toBlock(entry: TerminalEntry): TerminalBlock =
            when (entry.entryType) {
                ENTRY_TYPE_INPUT ->
                    TerminalBlock.Input(
                        id = entry.id,
                        timestamp = entry.timestamp,
                        text = entry.content,
                        imageNames =
                            entry.imageUris.map { uri ->
                                uri.substringAfterLast('/')
                            },
                    )
                ENTRY_TYPE_RESPONSE -> {
                    val trimmed = entry.content.trimStart()
                    val match = PROVIDER_PREFIX_REGEX.find(trimmed)
                    val providerId = match?.groupValues?.getOrNull(1)
                    val stripped =
                        if (match != null) {
                            trimmed.removePrefix(match.value).trimStart()
                        } else {
                            entry.content
                        }
                    val body = stripped.trimStart()
                    if (providerId == null &&
                        (body.startsWith("{") || body.startsWith("["))
                    ) {
                        TerminalBlock.Structured(
                            id = entry.id,
                            timestamp = entry.timestamp,
                            json = stripped,
                        )
                    } else {
                        TerminalBlock.Response(
                            id = entry.id,
                            timestamp = entry.timestamp,
                            content = stripped,
                            providerId = providerId,
                        )
                    }
                }
                ENTRY_TYPE_ERROR ->
                    TerminalBlock.Error(
                        id = entry.id,
                        timestamp = entry.timestamp,
                        message = entry.content,
                    )
                else ->
                    TerminalBlock.System(
                        id = entry.id,
                        timestamp = entry.timestamp,
                        text = entry.content,
                    )
            }
    }
}

/**
 * Immutable snapshot of the terminal REPL screen state.
 *
 * @property blocks Ordered list of terminal blocks for the scrollback buffer.
 * @property isLoading True while waiting for an FFI response.
 * @property pendingImages Images staged for the next message.
 * @property isProcessingImages True while images are being downscaled and encoded.
 */
data class TerminalState(
    val blocks: List<TerminalBlock> = emptyList(),
    val isLoading: Boolean = false,
    val pendingImages: List<ProcessedImage> = emptyList(),
    val isProcessingImages: Boolean = false,
)
