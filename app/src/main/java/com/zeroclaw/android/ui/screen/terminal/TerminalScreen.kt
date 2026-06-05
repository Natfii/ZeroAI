/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("TooManyFunctions", "MagicNumber")

package com.zeroclaw.android.ui.screen.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.ProcessedImage
import com.zeroclaw.android.model.VoiceState
import com.zeroclaw.android.ui.component.CameraPreviewSheet
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.MiniZeroMascot
import com.zeroclaw.android.ui.component.MiniZeroMascotState
import com.zeroclaw.android.ui.component.VoiceFab
import com.zeroclaw.android.ui.screen.terminal.theme.LocalTerminalTheme
import com.zeroclaw.android.ui.screen.terminal.theme.TerminalThemePicker
import com.zeroclaw.android.ui.theme.TerminalTypography
import com.zeroclaw.android.util.LocalPowerSaveMode
import com.zeroclaw.ffi.TtyRenderFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Horizontal padding inside the input bar. */
private const val INPUT_BAR_PADDING_DP = 8

/** Spacing between items in the scrollback. */
private const val BLOCK_SPACING_DP = 4

/** Maximum images per picker invocation. */
private const val MAX_PICKER_IMAGES = 5

/** Autocomplete popup corner radius. */
private const val AUTOCOMPLETE_CORNER_DP = 8

/** Autocomplete popup elevation. */
private const val AUTOCOMPLETE_ELEVATION_DP = 4

/** Autocomplete item vertical padding. */
private const val AUTOCOMPLETE_ITEM_V_PAD_DP = 12

/** Autocomplete item horizontal padding. */
private const val AUTOCOMPLETE_ITEM_H_PAD_DP = 12

/** Maximum height for the autocomplete popup before scrolling kicks in. */
private const val AUTOCOMPLETE_MAX_HEIGHT_DP = 240

/** Small spacing used between elements. */
private const val SMALL_SPACING_DP = 4

/** Pending image strip item horizontal padding. */
private const val STRIP_ITEM_H_PAD_DP = 8

/** Pending image strip item vertical padding. */
private const val STRIP_ITEM_V_PAD_DP = 4

/** Pending image strip corner radius. */
private const val STRIP_ITEM_CORNER_DP = 4

/** Dismiss badge size for pending images. */
private const val DISMISS_BADGE_DP = 16

/** Dismiss icon size. */
private const val DISMISS_ICON_DP = 12

/** Loading indicator size in the pending strip. */
private const val PROCESSING_INDICATOR_DP = 16

/** Maximum characters of paste text shown in the safety confirmation dialog. */
private const val MAX_PASTE_PREVIEW_LENGTH = 120

/** Maximum lines of paste text shown in the safety confirmation dialog. */
private const val MAX_PASTE_PREVIEW_LINES = 4

/** Alpha for selection highlight overlay drawn over the canvas cells. */
private const val SELECTION_HIGHLIGHT_ALPHA = 0.4f

/**
 * Terminal REPL screen for interacting with the ZeroAI daemon.
 *
 * Thin stateful wrapper that collects [TerminalViewModel] flows and
 * delegates rendering to [TerminalContent]. Provides the photo picker
 * launcher for image attachments.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param terminalViewModel The [TerminalViewModel] for terminal state.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun TerminalScreen(
    edgeMargin: Dp,
    terminalViewModel: TerminalViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by terminalViewModel.state.collectAsStateWithLifecycle()
    val streamingState by terminalViewModel.streamingState.collectAsStateWithLifecycle()
    val onDeviceWarmupLabel by terminalViewModel.onDeviceWarmupLabel.collectAsStateWithLifecycle()
    val showCamera by terminalViewModel.showCamera.collectAsStateWithLifecycle()
    val cameraPrompt by terminalViewModel.cameraPrompt.collectAsStateWithLifecycle()
    val voiceState by terminalViewModel.voiceState.collectAsStateWithLifecycle()
    val speakRepliesEnabled by terminalViewModel.speakRepliesEnabled.collectAsStateWithLifecycle()
    val lastAgentResponse by terminalViewModel.lastAgentResponse.collectAsStateWithLifecycle()
    val scriptPermissionRequest by
        terminalViewModel.scriptPermissionRequest.collectAsStateWithLifecycle()
    val requestScreenCapture by terminalViewModel.requestScreenCapture.collectAsStateWithLifecycle()
    val requestAudioPerm by terminalViewModel.requestAudioPermission.collectAsStateWithLifecycle()
    val requestLocationPerm by terminalViewModel.requestLocationPermission.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val screenCaptureLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            terminalViewModel.onScreenCaptureResult(result.resultCode, result.data)
        }

    val audioPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            terminalViewModel.onAudioPermissionResult(granted)
        }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.values.any { it }
            terminalViewModel.onLocationPermissionResult(granted)
        }

    LaunchedEffect(requestScreenCapture) {
        if (requestScreenCapture) {
            terminalViewModel.consumeScreenCaptureRequest()
            val projectionManager =
                context.getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE,
                ) as MediaProjectionManager
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    LaunchedEffect(requestAudioPerm) {
        if (requestAudioPerm) {
            terminalViewModel.consumeAudioPermissionRequest()
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(requestLocationPerm) {
        if (requestLocationPerm) {
            terminalViewModel.consumeLocationPermissionRequest()
            locationPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    LaunchedEffect(lastAgentResponse) {
        val text = lastAgentResponse
        if (text != null) {
            terminalViewModel.speakResponse(text)
        }
    }

    val isDaemonRunning by terminalViewModel.isDaemonRunning.collectAsStateWithLifecycle()
    val peerAliases by terminalViewModel.peerAliases.collectAsStateWithLifecycle()
    val terminalMode by terminalViewModel.terminalMode.collectAsStateWithLifecycle()
    val ttyOutputLines by terminalViewModel.ttyOutputLines.collectAsStateWithLifecycle()
    val ttyRenderFrameState = terminalViewModel.ttyRenderFrame.collectAsStateWithLifecycle()
    val hasFrame by remember {
        derivedStateOf { ttyRenderFrameState.value?.rows?.isNotEmpty() == true }
    }
    val ttyFontSize by terminalViewModel.ttyFontSize.collectAsStateWithLifecycle()
    val ttyCtrlActive by terminalViewModel.ttyCtrlActive.collectAsStateWithLifecycle()
    val ttyAltActive by terminalViewModel.ttyAltActive.collectAsStateWithLifecycle()
    val currentTheme by terminalViewModel.currentTheme.collectAsStateWithLifecycle()
    val showThemePicker by terminalViewModel.showThemePicker.collectAsStateWithLifecycle()
    val ttyCursorBlinking by terminalViewModel.ttyCursorBlinking.collectAsStateWithLifecycle()
    val ttyCursorPosition by terminalViewModel.ttyCursorPosition.collectAsStateWithLifecycle()
    val ttyGridCols by terminalViewModel.ttyGridCols.collectAsStateWithLifecycle()
    val ttySelection by terminalViewModel.ttySelection.collectAsStateWithLifecycle()
    val showPasteBar by terminalViewModel.showPasteBar.collectAsStateWithLifecycle()
    val pendingPaste by terminalViewModel.pendingPaste.collectAsStateWithLifecycle()
    val ttyTitle by terminalViewModel.terminalTitle.collectAsStateWithLifecycle()
    val isPowerSave = LocalPowerSaveMode.current

    CompositionLocalProvider(LocalTerminalTheme provides currentTheme) {
        Column(modifier = modifier.fillMaxSize()) {
            TerminalModeToggleBar(
                isShellMode = terminalMode is TerminalMode.Tty,
                onSelectRepl = terminalViewModel::switchToRepl,
                onSelectShell = terminalViewModel::switchToTty,
                edgeMargin = edgeMargin,
                modifier = Modifier.zIndex(1f),
            )
            Crossfade(
                targetState = terminalMode,
                modifier = Modifier.weight(1f),
                animationSpec = if (isPowerSave) snap() else tween(),
                label = "terminal-mode",
            ) { mode ->
                when (mode) {
                    is TerminalMode.Repl -> {
                        TerminalContent(
                            state = state,
                            streamingState = streamingState,
                            onDeviceWarmupLabel = onDeviceWarmupLabel,
                            isDaemonRunning = isDaemonRunning,
                            voiceState = voiceState,
                            speakRepliesEnabled = speakRepliesEnabled,
                            onSubmit = terminalViewModel::submitInput,
                            onAttachImages = terminalViewModel::attachImages,
                            onRemoveImage = terminalViewModel::removeImage,
                            onCancelAgent = terminalViewModel::cancelAgentTurn,
                            onVoiceTap = terminalViewModel::toggleVoice,
                            onVoiceLongPress = terminalViewModel::stopVoice,
                            onSpeakRepliesChanged = terminalViewModel::setSpeakRepliesEnabled,
                            onCanvasAction = terminalViewModel::handleCanvasAction,
                            peerAliases = peerAliases,
                            edgeMargin = edgeMargin,
                        )
                    }

                    is TerminalMode.Tty -> {
                        TtySessionContent(
                            session = mode.session,
                            outputLines = ttyOutputLines,
                            frameProvider = { ttyRenderFrameState.value },
                            hasFrame = hasFrame,
                            fontSize = ttyFontSize,
                            onFontSizeChange = terminalViewModel::setTtyFontSize,
                            onSizeChanged = terminalViewModel::onTtyGridSizeChanged,
                            ctrlActive = ttyCtrlActive,
                            altActive = ttyAltActive,
                            onClose = terminalViewModel::switchToRepl,
                            onKeyPress = terminalViewModel::ttyHandleSpecialKey,
                            onTextInput = terminalViewModel::ttyOnText,
                            onInputBytes = terminalViewModel::ttyWriteBytes,
                            cursorBlinking = ttyCursorBlinking,
                            cursorPosition = ttyCursorPosition,
                            gridCols = ttyGridCols,
                            selection = ttySelection,
                            showPasteBar = showPasteBar,
                            pendingPaste = pendingPaste,
                            onCopy = {
                                val sel = ttySelection ?: return@TtySessionContent
                                val frame =
                                    terminalViewModel.ttyRenderFrame.value
                                        ?: return@TtySessionContent
                                val text = extractSelectedText(sel, frame.rows)
                                copyToClipboard(context, text, "Terminal")
                                terminalViewModel.clearSelection()
                            },
                            onPaste = { terminalViewModel.pasteFromClipboard(context) },
                            onConfirmPaste = terminalViewModel::confirmPaste,
                            onCancelPaste = terminalViewModel::cancelPaste,
                            onSelectionStart = terminalViewModel::startWordSelection,
                            onSelectionUpdate = terminalViewModel::updateSelectionEnd,
                            onSelectionClear = terminalViewModel::clearSelection,
                            mouseTrackingActive = { terminalViewModel.isMouseTrackingActive() },
                            onMouseEvent = terminalViewModel::submitMouseEvent,
                            terminalTitle = ttyTitle,
                        )
                    }
                }
            }
        }
    }

    if (showCamera) {
        CameraPreviewSheet(
            onDismiss = terminalViewModel::dismissCamera,
            onImageCaptured = { image ->
                terminalViewModel.handleCameraCapture(image, cameraPrompt)
            },
        )
    }

    scriptPermissionRequest?.let { request ->
        TerminalScriptPermissionDialog(
            request = request,
            onToggleCapability = terminalViewModel::toggleScriptCapability,
            onGrantAll = terminalViewModel::grantAllScriptCapabilities,
            onDenyAll = terminalViewModel::denyAllScriptCapabilities,
            onConfirm = terminalViewModel::confirmScriptPermissionRequest,
            onDismiss = terminalViewModel::dismissScriptPermissionRequest,
        )
    }

    if (showThemePicker) {
        TerminalThemePicker(
            themes = terminalViewModel.allThemes(),
            currentThemeName = currentTheme?.name,
            onSelect = terminalViewModel::applyTheme,
            onDismiss = terminalViewModel::dismissThemePicker,
        )
    }
}

/**
 * Stateless terminal content composable for testing.
 *
 * Renders the terminal scrollback buffer, input bar, pending image
 * strip, autocomplete overlay, and live agent streaming card. All
 * state is passed in as parameters for deterministic previews and
 * unit tests.
 *
 * @param state Aggregated terminal state snapshot.
 * @param streamingState Live agent session streaming state.
 * @param isDaemonRunning Whether the background daemon service is currently active.
 * @param voiceState Current voice bridge state for the voice controls.
 * @param speakRepliesEnabled Whether assistant replies are spoken aloud automatically.
 * @param onSubmit Callback to submit user input text.
 * @param onAttachImages Callback to attach images from URIs.
 * @param onRemoveImage Callback to remove a pending image by index.
 * @param onCancelAgent Callback to cancel the active agent turn.
 * @param onVoiceTap Callback when the microphone control is tapped.
 * @param onVoiceLongPress Callback when the stop voice control is tapped.
 * @param onSpeakRepliesChanged Callback when spoken replies are toggled.
 * @param onCanvasAction Callback when a canvas interactive element is activated.
 * @param peerAliases List of `@alias` strings for peer agent autocomplete.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param modifier Modifier applied to the root layout.
 */
@Suppress("OutdatedDocumentation")
@Composable
internal fun TerminalContent(
    state: TerminalState,
    streamingState: StreamingState,
    onDeviceWarmupLabel: String? = null,
    isDaemonRunning: Boolean = false,
    voiceState: VoiceState = VoiceState.Idle,
    speakRepliesEnabled: Boolean = false,
    onSubmit: (String) -> Unit,
    onAttachImages: (List<Uri>) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onCancelAgent: () -> Unit,
    onVoiceTap: () -> Unit = {},
    onVoiceLongPress: () -> Unit = {},
    onSpeakRepliesChanged: (Boolean) -> Unit = {},
    onCanvasAction: (String) -> Unit = {},
    peerAliases: List<String> = emptyList(),
    edgeMargin: Dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val isPowerSave = LocalPowerSaveMode.current

    val photoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKER_IMAGES),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                onAttachImages(uris)
            }
        }

    val isAgentActive = streamingState.phase.isActive
    val isInputDisabled = state.isLoading || isAgentActive

    val stableOnRemove: (Int) -> Unit = remember { { index -> onRemoveImage(index) } }
    val displayBlocks = remember(state.blocks) { state.blocks.asReversed() }

    val autocompletePrefix by remember {
        derivedStateOf {
            if (inputText.startsWith("/")) {
                inputText.removePrefix("/")
            } else {
                null
            }
        }
    }
    val autocompleteSuggestions by remember {
        derivedStateOf {
            val prefix = autocompletePrefix
            if (prefix != null) {
                CommandRegistry.matches(prefix)
            } else {
                emptyList()
            }
        }
    }
    val peerSuggestions by remember(peerAliases) {
        derivedStateOf {
            if (inputText.startsWith("@")) {
                val typed = inputText.lowercase()
                peerAliases.filter { it.lowercase().startsWith(typed) }
            } else {
                emptyList()
            }
        }
    }

    LaunchedEffect(state.blocks.size, streamingState.phase) {
        if (state.blocks.isNotEmpty() || isAgentActive) {
            if (isPowerSave) {
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }

    val surfaceColor = themedReplBackground()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(surfaceColor)
                    .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = edgeMargin),
                verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING_DP.dp),
            ) {
                if (isAgentActive) {
                    if (streamingState.responseText.isNotEmpty()) {
                        item(key = "streaming-response", contentType = "streaming") {
                            StreamingResponseBlock(
                                text = streamingState.responseText,
                                modifier =
                                    Modifier.padding(
                                        horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                                        vertical = SMALL_SPACING_DP.dp,
                                    ),
                            )
                        }
                    }

                    item(key = "thinking-card", contentType = "thinking") {
                        ThinkingCard(
                            thinkingText = streamingState.thinkingText,
                            visible = true,
                            onCancel = onCancelAgent,
                            activeTools = streamingState.activeTools,
                            toolResults = streamingState.toolResults,
                            phase = streamingState.phase,
                            providerRound = streamingState.providerRound,
                            toolCallCount = streamingState.toolCallCount,
                            llmDurationSecs = streamingState.llmDurationSecs,
                            modifier =
                                Modifier.padding(
                                    horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                                    vertical = SMALL_SPACING_DP.dp,
                                ),
                        )
                    }
                } else if (state.isLoading) {
                    item(key = "spinner", contentType = "spinner") {
                        BrailleSpinner(
                            label = "Thinking\u2026",
                            modifier =
                                Modifier.padding(
                                    horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                                    vertical = SMALL_SPACING_DP.dp,
                                ),
                        )
                    }
                } else if (onDeviceWarmupLabel != null) {
                    item(key = "ondevice-warmup", contentType = "warmup") {
                        BrailleSpinner(
                            label = onDeviceWarmupLabel,
                            modifier =
                                Modifier.padding(
                                    horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                                    vertical = SMALL_SPACING_DP.dp,
                                ),
                        )
                    }
                }

                items(
                    items = displayBlocks,
                    key = { it.id },
                    contentType = { block -> block::class.simpleName },
                ) { block ->
                    val onCopy: (String) -> Unit =
                        remember(block.id) {
                            { text ->
                                val copyResult = copyToClipboard(context, text)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (copyResult == ClipboardCopyResult.Redacted) {
                                            "Copied redacted content to clipboard"
                                        } else {
                                            "Copied to clipboard"
                                        },
                                    )
                                }
                                Unit
                            }
                        }
                    TerminalBlockItem(
                        block = block,
                        onCopy = onCopy,
                        onCanvasAction = onCanvasAction,
                    )
                }

                item(key = "welcome-header", contentType = "welcome") {
                    WelcomeHeader(
                        isDaemonRunning = isDaemonRunning,
                        hasConversation = displayBlocks.isNotEmpty(),
                        modifier =
                            Modifier.padding(
                                horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                                vertical = SMALL_SPACING_DP.dp,
                            ),
                    )
                }
            }

            if (state.pendingImages.isNotEmpty() || state.isProcessingImages) {
                PendingImagesStrip(
                    images = state.pendingImages,
                    isProcessing = state.isProcessingImages,
                    onRemove = stableOnRemove,
                    modifier = Modifier.padding(horizontal = edgeMargin),
                )
            }

            if (autocompleteSuggestions.isNotEmpty()) {
                AutocompletePopup(
                    suggestions = autocompleteSuggestions,
                    onSelect = { command ->
                        inputText = "/${command.name} "
                    },
                    modifier = Modifier.padding(horizontal = edgeMargin),
                )
            }

            if (peerSuggestions.isNotEmpty()) {
                PeerAutocompletePopup(
                    suggestions = peerSuggestions,
                    onSelect = { alias ->
                        inputText = "$alias "
                    },
                    modifier = Modifier.padding(horizontal = edgeMargin),
                )
            }

            VoiceControlsRow(
                voiceState = voiceState,
                speakRepliesEnabled = speakRepliesEnabled,
                onVoiceTap = onVoiceTap,
                onStopVoice = onVoiceLongPress,
                onSpeakRepliesChanged = onSpeakRepliesChanged,
                modifier =
                    Modifier.padding(
                        horizontal = edgeMargin,
                        vertical = SMALL_SPACING_DP.dp,
                    ),
            )

            TerminalInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                onSubmit = {
                    onSubmit(inputText)
                    inputText = ""
                },
                onAttach = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                isLoading = isInputDisabled,
                hasImages = state.pendingImages.isNotEmpty(),
                modifier =
                    Modifier.padding(
                        horizontal = edgeMargin,
                        vertical = INPUT_BAR_PADDING_DP.dp,
                    ),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceControlsRow(
    voiceState: VoiceState,
    speakRepliesEnabled: Boolean,
    onVoiceTap: () -> Unit,
    onStopVoice: () -> Unit,
    onSpeakRepliesChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val voiceStatus =
        when (voiceState) {
            is VoiceState.Idle -> "Voice input off"
            is VoiceState.Listening -> "Listening"
            is VoiceState.Processing -> "Processing speech"
            is VoiceState.Speaking -> "Speaking reply"
            is VoiceState.Error -> "Voice error"
        }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SMALL_SPACING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(SMALL_SPACING_DP.dp),
    ) {
        VoiceFab(
            voiceState = voiceState,
            onClick = onVoiceTap,
            onLongClick = onStopVoice,
        )
        AssistChip(
            onClick = onStopVoice,
            label = { Text(voiceStatus) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Hearing,
                    contentDescription = null,
                )
            },
            enabled = voiceState !is VoiceState.Idle,
            colors =
                AssistChipDefaults.assistChipColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        )
        FilterChip(
            selected = speakRepliesEnabled,
            onClick = { onSpeakRepliesChanged(!speakRepliesEnabled) },
            label = { Text("Speak replies") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.RecordVoiceOver,
                    contentDescription = null,
                )
            },
        )
    }
}

/**
 * Input bar with a prompt prefix, text field, attach button, and send button.
 *
 * Uses monospace typography for the terminal aesthetic. The `>` prompt
 * prefix is rendered as leading text within the outlined text field.
 *
 * @param value Current input text.
 * @param onValueChange Callback when text changes.
 * @param onSubmit Callback when the send button is tapped.
 * @param onAttach Callback when the attach button is tapped.
 * @param isLoading Whether a response is in progress (disables send).
 * @param hasImages Whether images are currently attached.
 * @param modifier Modifier applied to the input bar.
 */
@Composable
private fun TerminalInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onAttach: () -> Unit,
    isLoading: Boolean,
    hasImages: Boolean,
    modifier: Modifier = Modifier,
) {
    val canSend = (value.isNotBlank() || hasImages) && !isLoading

    val accentColor = themedRoleColor(BlockRole.INPUT_PROMPT)
    val textColor = themedRoleColor(BlockRole.INPUT_TEXT)
    val dimColor = themedRoleColor(BlockRole.SYSTEM)
    val containerColor = themedReplBackground()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onAttach,
            enabled = !isLoading,
            modifier =
                Modifier.semantics {
                    contentDescription = "Attach images"
                },
        ) {
            Icon(
                Icons.Outlined.AttachFile,
                contentDescription = null,
                tint =
                    if (!isLoading) {
                        dimColor
                    } else {
                        textColor.copy(alpha = 0.38f)
                    },
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle =
                TerminalTypography.bodyMedium.copy(
                    color = textColor,
                ),
            prefix = {
                Text(
                    text = "> ",
                    style = TerminalTypography.bodyMedium,
                    color = accentColor,
                )
            },
            placeholder = {
                Text(
                    text = "Type a command or message",
                    style = TerminalTypography.bodyMedium,
                    color = dimColor,
                )
            },
            singleLine = true,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    cursorColor = accentColor,
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = dimColor,
                    focusedContainerColor = containerColor,
                    unfocusedContainerColor = containerColor,
                ),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(SMALL_SPACING_DP.dp))
        IconButton(
            onClick = onSubmit,
            enabled = canSend,
            modifier =
                Modifier.semantics {
                    contentDescription = "Send"
                },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint =
                    if (canSend) {
                        accentColor
                    } else {
                        dimColor
                    },
            )
        }
    }
}

/**
 * Autocomplete popup showing matching slash commands above the input bar.
 *
 * Each suggestion displays the command name and its description. Tapping
 * a suggestion inserts the command text into the input field.
 *
 * @param suggestions Filtered list of matching commands.
 * @param onSelect Callback when a suggestion is tapped.
 * @param modifier Modifier applied to the popup container.
 */
@Composable
private fun AutocompletePopup(
    suggestions: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(AUTOCOMPLETE_CORNER_DP.dp),
        tonalElevation = AUTOCOMPLETE_ELEVATION_DP.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .heightIn(max = AUTOCOMPLETE_MAX_HEIGHT_DP.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            for (command in suggestions) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(command) }
                            .padding(
                                horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                                vertical = AUTOCOMPLETE_ITEM_V_PAD_DP.dp,
                            ).semantics {
                                contentDescription =
                                    "/${command.name}: ${command.description}"
                            },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "/${command.name}",
                        style = TerminalTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(INPUT_BAR_PADDING_DP.dp))
                    Text(
                        text = command.description,
                        style = TerminalTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Autocomplete popup showing matching peer agent aliases above the input bar.
 *
 * Each suggestion displays the `@alias` and a "Peer agent" label.
 * Tapping a suggestion inserts the alias text into the input field.
 *
 * @param suggestions Filtered list of matching peer aliases (including `@` prefix).
 * @param onSelect Callback when a suggestion is tapped.
 * @param modifier Modifier applied to the popup container.
 */
@Composable
private fun PeerAutocompletePopup(
    suggestions: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(AUTOCOMPLETE_CORNER_DP.dp),
        tonalElevation = AUTOCOMPLETE_ELEVATION_DP.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .heightIn(max = AUTOCOMPLETE_MAX_HEIGHT_DP.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            for (alias in suggestions) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(alias) }
                            .padding(
                                horizontal = AUTOCOMPLETE_ITEM_H_PAD_DP.dp,
                                vertical = AUTOCOMPLETE_ITEM_V_PAD_DP.dp,
                            ).semantics {
                                contentDescription = "$alias: Peer agent"
                            },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = alias,
                        style = TerminalTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(INPUT_BAR_PADDING_DP.dp))
                    Text(
                        text = "Peer agent",
                        style = TerminalTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Horizontal strip of pending image indicators in terminal aesthetic.
 *
 * Each image is shown as a text label `[filename size]` with a dismiss
 * button, matching the terminal look instead of graphical thumbnails.
 * A processing indicator appears when images are being downscaled.
 *
 * @param images Currently staged images.
 * @param isProcessing Whether images are still being processed.
 * @param onRemove Callback to remove an image by index.
 * @param modifier Modifier applied to the strip.
 */
@Composable
private fun PendingImagesStrip(
    images: List<ProcessedImage>,
    isProcessing: Boolean,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SMALL_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isProcessing) {
            LoadingIndicator(modifier = Modifier.size(PROCESSING_INDICATOR_DP.dp))
        }
        for ((index, image) in images.withIndex()) {
            val stableOnRemove = remember(index) { { onRemove(index) } }
            PendingImageChip(
                image = image,
                onRemove = stableOnRemove,
            )
        }
    }
}

/**
 * Terminal-styled chip showing an image filename with a dismiss button.
 *
 * @param image The processed image to display.
 * @param onRemove Callback when the dismiss button is tapped.
 */
@Composable
private fun PendingImageChip(
    image: ProcessedImage,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(STRIP_ITEM_CORNER_DP.dp),
                ).padding(
                    horizontal = STRIP_ITEM_H_PAD_DP.dp,
                    vertical = STRIP_ITEM_V_PAD_DP.dp,
                ),
    ) {
        Text(
            text = "[${image.displayName}]",
            style = TerminalTypography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(SMALL_SPACING_DP.dp))
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clickable(onClick = onRemove)
                    .semantics {
                        contentDescription = "Remove ${image.displayName}"
                    },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(DISMISS_BADGE_DP.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(DISMISS_ICON_DP.dp),
                )
            }
        }
    }
}

/**
 * Streaming response block that renders progressively growing text.
 *
 * Styled identically to [TerminalBlock.Response] blocks but rendered
 * inline during the streaming phase. When the turn completes, this block
 * disappears and a persisted [TerminalBlock.Response] replaces it in
 * the scrollback.
 *
 * @param text Accumulated response tokens so far.
 * @param modifier Modifier applied to the text block.
 */
@Composable
private fun StreamingResponseBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = TerminalTypography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Streaming response"
                    liveRegion = LiveRegionMode.Polite
                },
    )
}

/**
 * Copies the given text to the system clipboard.
 *
 * @param context Android context for system service access.
 * @param text The text to copy.
 */
private fun copyToClipboard(
    context: Context,
    text: String,
): ClipboardCopyResult {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val redactedText = redactClipboardSecrets(text)
    val clip = ClipData.newPlainText("Terminal output", redactedText)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras =
            android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
    }
    clipboard.setPrimaryClip(clip)
    return if (redactedText == text) {
        ClipboardCopyResult.Copied
    } else {
        ClipboardCopyResult.Redacted
    }
}

private enum class ClipboardCopyResult {
    Copied,
    Redacted,
}

/** Mascot size in the welcome header. */
private const val WELCOME_MASCOT_DP = 48

/**
 * Sticky Mini-Zero welcome header at the top of the terminal scrollback.
 *
 * Shows the mascot with a status line. When there is existing
 * conversation, collapses to just the mascot and a short label.
 *
 * @param isDaemonRunning Whether the daemon foreground service is active.
 * @param hasConversation Whether the scrollback contains user entries.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
private fun WelcomeHeader(
    isDaemonRunning: Boolean,
    hasConversation: Boolean,
    modifier: Modifier = Modifier,
) {
    val mascotState =
        if (isDaemonRunning) MiniZeroMascotState.Peek else MiniZeroMascotState.Sleeping
    val statusLabel =
        if (isDaemonRunning) "Online and ready." else "Sleeping. Ready when you need me."

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MiniZeroMascot(
            state = mascotState,
            size = WELCOME_MASCOT_DP.dp,
            contentDescription = statusLabel,
        )
        Text(
            text = statusLabel,
            style = TerminalTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasConversation) {
            Spacer(modifier = Modifier.size(SMALL_SPACING_DP.dp))
            Text(
                text =
                    if (isDaemonRunning) {
                        "Ready to help with chat, tools, and terminal commands."
                    } else {
                        "Start the daemon to chat, or type /help for commands."
                    },
                style = TerminalTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Type /help for commands or send a message to chat.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Default grid column count when no render frame is available yet. */
private const val TTY_DEFAULT_GRID_COLS = 80

/** Cursor blink toggle interval matching xterm/ghostty-web convention. */
private const val CURSOR_BLINK_INTERVAL_MS = 530L

/**
 * Full-screen TTY session composable with output display and input.
 *
 * Renders the PTY output via [TtyCanvasView] using the GPU-accelerated cell
 * grid renderer. Keyboard entry is fed to the PTY via [TtyKeyInputView] and
 * [TtyKeyRow] exposes special keys. [outputLines] is retained for
 * accessibility and fallback purposes even though it is no longer the
 * primary rendering path.
 *
 * @param session Current TTY session UI state for the status bar.
 * @param outputLines ANSI-stripped output lines retained for accessibility fallback.
 * @param frameProvider Lambda that returns the current [TtyRenderFrame], invoked
 *   inside the Canvas draw phase so that frame updates skip composition and layout.
 * @param hasFrame Whether a non-empty render frame is available (drives the
 *   Canvas-vs-LazyColumn branch at composition time).
 * @param fontSize Font size in sp used by [TtyCanvasView] for the monospace cell grid.
 * @param ctrlActive Whether the Ctrl modifier is toggled on.
 * @param altActive Whether the Alt modifier is toggled on.
 * @param onClose Callback to close the TTY session and return to REPL.
 * @param onKeyPress Callback for special key presses from [TtyKeyRow].
 * @param onTextInput Callback for printable text typed via the software keyboard, sent to the PTY.
 * @param onInputBytes Callback for raw control bytes (backspace, forward delete) from the input proxy.
 * @param onFontSizeChange Invoked with the new font size after a pinch-to-zoom gesture.
 * @param onSizeChanged Invoked with the new `(cols, rows, widthPx, heightPx)` grid dimensions
 *   and canvas pixel size when the canvas size or font metrics change.
 * @param cursorBlinking Whether the cursor is in blinking mode; drives the blink [LaunchedEffect].
 * @param cursorPosition Stable `"col-row"` string key; resets the blink phase when the cursor moves.
 * @param gridCols Current grid column count from the latest render frame, or the default.
 * @param selection Current text selection state, or `null` when no selection is active.
 * @param showPasteBar Grid cell position for a paste-only action bar when no text is selected.
 * @param pendingPaste Text awaiting paste confirmation, or `null` when no confirmation is needed.
 * @param onCopy Callback invoked when the user taps Copy in the selection action bar.
 * @param onPaste Callback invoked when the user taps Paste in the action bar.
 * @param onConfirmPaste Callback to confirm and send a pending unsafe paste.
 * @param onCancelPaste Callback to cancel a pending paste and clear selection state.
 * @param onSelectionStart Callback invoked at the start of a long-press selection gesture.
 * @param onSelectionUpdate Callback invoked as the selection drag updates.
 * @param onSelectionClear Callback invoked when the selection is dismissed.
 * @param mouseTrackingActive Lambda returning `true` when the terminal is in
 *   mouse-tracking mode. Forwarded to [TtyCanvasView] to switch gesture routing.
 * @param onMouseEvent Callback invoked with encoded mouse event parameters when
 *   [mouseTrackingActive] returns `true`. Forwarded to [TtyCanvasView].
 * @param terminalTitle Terminal title set by OSC 0/2, or `null` if unset.
 *   Displayed in [TtyStatusBar] alongside the connection status.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList")
@Composable
fun TtySessionContent(
    session: TtySessionUiState,
    outputLines: List<String>,
    frameProvider: () -> TtyRenderFrame?,
    hasFrame: Boolean,
    fontSize: Float,
    ctrlActive: Boolean,
    altActive: Boolean,
    onClose: () -> Unit,
    onKeyPress: (TtySpecialKey) -> Unit,
    onTextInput: (String) -> Unit,
    onInputBytes: (ByteArray) -> Unit = {},
    onFontSizeChange: (Float) -> Unit,
    onSizeChanged: (cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit,
    cursorBlinking: Boolean = false,
    cursorPosition: String = "",
    gridCols: Int = TTY_DEFAULT_GRID_COLS,
    selection: TtySelectionState? = null,
    showPasteBar: Pair<Int, Int>? = null,
    pendingPaste: String? = null,
    onCopy: () -> Unit = {},
    onPaste: () -> Unit = {},
    onConfirmPaste: () -> Unit = {},
    onCancelPaste: () -> Unit = {},
    onSelectionStart: (col: Int, row: Int) -> Unit = { _, _ -> },
    onSelectionUpdate: (col: Int, row: Int) -> Unit = { _, _ -> },
    onSelectionClear: () -> Unit = {},
    mouseTrackingActive: () -> Boolean = { false },
    onMouseEvent: (UByte, UByte, Float, Float, UInt) -> Unit = { _, _, _, _, _ -> },
    terminalTitle: String? = null,
) {
    val inputView = remember { mutableStateOf<TtyKeyInputView?>(null) }
    val isPowerSave = LocalPowerSaveMode.current
    val imeVisible = WindowInsets.isImeVisible
    val ttyFallbackBg = themedReplBackground()
    val ttyFallbackFg = themedRoleColor(BlockRole.RESPONSE)

    // Send focus gained/lost events to the terminal for DEC 1004
    // focus reporting. Uses ON_START/ON_STOP (not ON_RESUME/ON_PAUSE)
    // so multi-window and PiP mode work correctly.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START -> {
                        try {
                            com.zeroclaw.ffi.ttySendFocusEvent(true)
                        } catch (_: Exception) {
                        }
                    }
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        try {
                            com.zeroclaw.ffi.ttySendFocusEvent(false)
                        } catch (_: Exception) {
                        }
                    }
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Raise the soft keyboard when the surface appears, matching the prior
    // text-field behaviour.
    LaunchedEffect(inputView.value) {
        inputView.value?.showKeyboard()
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding(),
    ) {
        TtyStatusBar(
            session = session,
            onClose = onClose,
            terminalTitle = terminalTitle,
            modifier = Modifier.fillMaxWidth(),
        )

        // Paste safety dialog
        if (pendingPaste != null) {
            AlertDialog(
                onDismissRequest = onCancelPaste,
                title = { Text("Confirm Paste") },
                text = {
                    Column {
                        Text(
                            "This text contains newlines or control characters that may execute commands.",
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = pendingPaste.take(MAX_PASTE_PREVIEW_LENGTH),
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = MAX_PASTE_PREVIEW_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onConfirmPaste) { Text("Paste Anyway") }
                },
                dismissButton = {
                    TextButton(onClick = onCancelPaste) { Text("Cancel") }
                },
            )
        }

        val listState = rememberLazyListState()

        val isAtBottom by remember {
            derivedStateOf {
                val lastVisible =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: 0
                lastVisible >= listState.layoutInfo.totalItemsCount - 2
            }
        }

        LaunchedEffect(outputLines.size) {
            if (isAtBottom && outputLines.isNotEmpty()) {
                listState.scrollToItem(outputLines.size - 1)
            }
        }

        var blinkPhase by remember { mutableStateOf(true) }

        LaunchedEffect(cursorBlinking, cursorPosition) {
            blinkPhase = true
            if (cursorBlinking && !isPowerSave) {
                while (true) {
                    delay(CURSOR_BLINK_INTERVAL_MS)
                    blinkPhase = !blinkPhase
                }
            }
        }
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val gridContext = LocalContext.current

        LaunchedEffect(canvasSize, fontSize) {
            if (canvasSize.width > 0 && canvasSize.height > 0) {
                val fontSizePx = with(density) { fontSize.sp.toPx() }
                val paint =
                    android.graphics.Paint().apply {
                        typeface = ttyTypeface(gridContext)
                        textSize = fontSizePx
                    }
                val cellWidth = paint.measureText("X")
                val cellHeight = paint.fontSpacing
                val cols = (canvasSize.width / cellWidth).toInt().coerceAtLeast(1)
                val rows = (canvasSize.height / cellHeight).toInt().coerceAtLeast(1)
                onSizeChanged(cols, rows, canvasSize.width, canvasSize.height)
            }
        }

        if (hasFrame) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                val selectionHighlightColor =
                    MaterialTheme.colorScheme.primary
                        .copy(alpha = SELECTION_HIGHLIGHT_ALPHA)
                        .toArgb()

                TtyCanvasView(
                    frameProvider = frameProvider,
                    fontSize = fontSize,
                    gridCols = gridCols,
                    onFontSizeChange = onFontSizeChange,
                    onTap = { inputView.value?.showKeyboard() },
                    cursorVisible = blinkPhase,
                    selectionProvider = { selection },
                    selectionHighlightArgb = selectionHighlightColor,
                    onSelectionStart = onSelectionStart,
                    onSelectionUpdate = onSelectionUpdate,
                    onSelectionClear = onSelectionClear,
                    mouseTrackingActive = mouseTrackingActive,
                    onMouseEvent = onMouseEvent,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { canvasSize = it },
                )

                // Floating action bar for selection/paste
                if (selection != null || showPasteBar != null) {
                    TtySelectionActionBar(
                        hasSelection = selection != null,
                        hasClipboard = true,
                        onCopy = onCopy,
                        onPaste = onPaste,
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.Bottom,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(ttyFallbackBg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { inputView.value?.showKeyboard() },
            ) {
                items(
                    count = outputLines.size,
                    key = { index -> index },
                ) { index ->
                    Text(
                        text = outputLines[index],
                        color = ttyFallbackFg,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }

        // Invisible 1.dp input proxy: owns the IME and forwards every keystroke
        // to the PTY so the shell's own line editor handles cursor movement. It
        // is never in the touch path, so the canvas keeps its tap/zoom/selection.
        AndroidView(
            factory = { ctx ->
                TtyKeyInputView(ctx).apply {
                    onText = onTextInput
                    onNamedKey = onKeyPress
                    onBytes = onInputBytes
                    inputView.value = this
                }
            },
            modifier = Modifier.size(1.dp),
        )

        TtyKeyRow(
            onKeyPress = onKeyPress,
            onToggleKeyboard = {
                if (imeVisible) {
                    inputView.value?.hideKeyboard()
                } else {
                    inputView.value?.showKeyboard()
                }
            },
            ctrlActive = ctrlActive,
            altActive = altActive,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Floating action bar for terminal text selection and paste.
 *
 * Shows Copy and Paste buttons when text is selected, or only Paste
 * when triggered from a long-press on empty space.
 *
 * @param hasSelection Whether text is currently selected.
 * @param hasClipboard Whether the clipboard contains pasteable content.
 * @param onCopy Callback invoked when the Copy button is tapped.
 * @param onPaste Callback invoked when the Paste button is tapped.
 * @param modifier [Modifier] applied to the action bar surface.
 */
@Composable
private fun TtySelectionActionBar(
    hasSelection: Boolean,
    hasClipboard: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(modifier = Modifier.padding(horizontal = 4.dp)) {
            if (hasSelection) {
                TextButton(
                    onClick = onCopy,
                    modifier =
                        Modifier
                            .sizeIn(minHeight = 48.dp)
                            .semantics { contentDescription = "Copy selected text" },
                ) {
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            TextButton(
                onClick = onPaste,
                enabled = hasClipboard,
                modifier =
                    Modifier
                        .sizeIn(minHeight = 48.dp)
                        .semantics { contentDescription = "Paste from clipboard" },
            ) {
                Text(
                    text = "Paste",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
