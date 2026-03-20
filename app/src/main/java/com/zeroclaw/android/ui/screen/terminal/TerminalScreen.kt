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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.ProcessedImage
import com.zeroclaw.android.model.VoiceState
import com.zeroclaw.android.ui.component.CameraPreviewSheet
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.MiniZeroMascot
import com.zeroclaw.android.ui.component.MiniZeroMascotState
import com.zeroclaw.android.ui.component.VoiceFab
import com.zeroclaw.android.ui.theme.TerminalTypography
import com.zeroclaw.android.util.LocalPowerSaveMode
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

/** Maximum number of visible characters preserved around a redacted secret. */
private const val REDACTION_VISIBLE_CHARS = 4

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

    TerminalContent(
        state = state,
        streamingState = streamingState,
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
        modifier = modifier,
    )

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
@Composable
internal fun TerminalContent(
    state: TerminalState,
    streamingState: StreamingState,
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
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
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle =
                TerminalTypography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            prefix = {
                Text(
                    text = "> ",
                    style = TerminalTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            placeholder = {
                Text(
                    text = "Type a command or message",
                    style = TerminalTypography.bodyMedium,
                )
            },
            singleLine = true,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
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
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
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

private val CLIPBOARD_SECRET_PATTERNS: List<Regex> =
    listOf(
        Regex("""(?i)(authorization:\s*bearer\s+)([A-Za-z0-9._\-+/=]{12,})"""),
        Regex("""(?i)(refresh[_ -]?token["'=:\s]+)([A-Za-z0-9._\-+/=]{12,})"""),
        Regex("""(?i)(access[_ -]?token["'=:\s]+)([A-Za-z0-9._\-+/=]{12,})"""),
        Regex("""(?i)(api[_ -]?key["'=:\s]+)([A-Za-z0-9._\-+/=]{12,})"""),
        Regex("""\bsk-[A-Za-z0-9_-]{12,}\b"""),
        Regex("""\bAIza[0-9A-Za-z\-_]{20,}\b"""),
        Regex("""\bya29\.[0-9A-Za-z._\-]+\b"""),
        Regex("""(?i)\b(cookie|set-cookie):\s*([^\r\n]+)"""),
    )

private fun redactClipboardSecrets(text: String): String {
    var redacted = text
    CLIPBOARD_SECRET_PATTERNS.forEach { pattern ->
        redacted =
            pattern.replace(redacted) { match ->
                if (match.groupValues.size >= 3) {
                    match.groupValues[1] + preserveTokenShape(match.groupValues[2])
                } else {
                    preserveTokenShape(match.value)
                }
            }
    }
    return redacted
}

private fun preserveTokenShape(token: String): String {
    val trimmed = token.trim()
    if (trimmed.length <= REDACTION_VISIBLE_CHARS * 2) {
        return "[REDACTED]"
    }
    return buildString {
        append(trimmed.take(REDACTION_VISIBLE_CHARS))
        append("…")
        append(trimmed.takeLast(REDACTION_VISIBLE_CHARS))
        append(" [REDACTED]")
    }
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
