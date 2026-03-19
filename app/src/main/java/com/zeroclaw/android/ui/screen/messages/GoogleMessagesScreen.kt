/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.zeroclaw.android.ui.screen.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.ffi.FfiBridgedConversation

/**
 * Google Messages pairing and allowlist configuration screen.
 *
 * Displays a three-phase flow:
 * 1. **Warning** — informs the user that pairing will disconnect any existing
 *    Messages for Web session.
 * 2. **Pairing** — shows instructions and a copyable URL for the local QR
 *    code page, with a polling status indicator.
 * 3. **Allowlist** — lists all bridged conversations with per-conversation
 *    toggle switches and time window controls.
 *
 * @param onBack Callback invoked when the user navigates back.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param viewModel The [GoogleMessagesViewModel] managing screen state.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun GoogleMessagesScreen(
    onBack: () -> Unit,
    edgeMargin: Dp,
    viewModel: GoogleMessagesViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            GoogleMessagesTopBar(
                uiState = uiState,
                onBack = onBack,
                onDisconnect = viewModel::disconnect,
                onDisconnectAndClear = viewModel::disconnectAndClear,
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = edgeMargin),
        ) {
            when (val state = uiState) {
                is GoogleMessagesViewModel.UiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                is GoogleMessagesViewModel.UiState.Warning -> {
                    WarningContent(
                        onContinue = viewModel::startPairing,
                        onCancel = onBack,
                    )
                }
                is GoogleMessagesViewModel.UiState.Pairing -> {
                    PairingContent(
                        qrPageUrl = state.qrPageUrl,
                        onCancel = viewModel::disconnect,
                    )
                }
                is GoogleMessagesViewModel.UiState.Allowlist -> {
                    AllowlistContent(
                        conversations = state.conversations,
                        onSetAllowed = viewModel::setAllowed,
                    )
                }
                is GoogleMessagesViewModel.UiState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = viewModel::refreshConversations,
                    )
                }
            }
        }
    }
}

/**
 * Top app bar with title, back navigation, and overflow menu.
 *
 * The overflow menu is only visible in the [GoogleMessagesViewModel.UiState.Allowlist]
 * state, providing disconnect and clear data actions with confirmation dialogs.
 *
 * @param uiState Current screen state to determine overflow menu visibility.
 * @param onBack Callback when the back button is pressed.
 * @param onDisconnect Callback to disconnect the bridge.
 * @param onDisconnectAndClear Callback to disconnect and wipe all data.
 */
@Composable
private fun GoogleMessagesTopBar(
    uiState: GoogleMessagesViewModel.UiState,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onDisconnectAndClear: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("Google Messages") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Navigate back",
                )
            }
        },
        actions = {
            if (uiState is GoogleMessagesViewModel.UiState.Allowlist) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "More options"
                        },
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = null,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Disconnect") },
                        onClick = {
                            showMenu = false
                            showDisconnectDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Disconnect & Clear Data",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            showClearDialog = true
                        },
                    )
                }
            }
        },
    )

    if (showDisconnectDialog) {
        DisconnectConfirmDialog(
            title = "Disconnect?",
            message =
                "This will stop receiving messages from Google Messages. " +
                    "Your conversation data will be preserved.",
            onConfirm = {
                showDisconnectDialog = false
                onDisconnect()
            },
            onDismiss = { showDisconnectDialog = false },
        )
    }

    if (showClearDialog) {
        DisconnectConfirmDialog(
            title = "Disconnect & Clear Data?",
            message =
                "This will stop receiving messages and permanently delete " +
                    "all stored conversation data. This action cannot be undone.",
            onConfirm = {
                showClearDialog = false
                onDisconnectAndClear()
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

/**
 * Warning disclosure shown before pairing begins.
 *
 * Informs the user that pairing ZeroAI as a Messages for Web device
 * will disconnect any existing web session.
 *
 * @param onContinue Callback to proceed with pairing.
 * @param onCancel Callback to navigate back.
 */
@Composable
private fun WarningContent(
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Web session warning",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text =
                        "This will pair ZeroAI as a Messages for Web device. " +
                            "If you currently use messages.google.com on a computer, " +
                            "that session will be disconnected. Only one web session " +
                            "can be active at a time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(48.dp),
            ) {
                Text("Cancel")
            }
            Button(
                onClick = onContinue,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(48.dp),
            ) {
                Text("Continue")
            }
        }
    }
}

/**
 * Pairing instructions and status indicator.
 *
 * Displays numbered steps for completing the QR code pairing flow
 * and a copyable URL field for the local pairing page.
 *
 * @param qrPageUrl Local HTTP URL serving the QR code page.
 */
@Composable
private fun PairingContent(
    qrPageUrl: String,
    onCancel: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Pair with Google Messages",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        PairingStep(
            number = "1",
            text = "Open this URL on another computer:",
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = qrPageUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(qrPageUrl))
                    },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Copy URL to clipboard"
                        },
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                    )
                }
            }
        }
        PairingStep(
            number = "2",
            text = "Open Google Messages on this phone",
        )
        PairingStep(
            number = "3",
            text = "Tap your profile icon \u2192 Device pairing \u2192 QR code scanner",
        )
        PairingStep(
            number = "4",
            text = "Point your phone at the QR code on your computer screen",
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier =
                    Modifier.semantics {
                        contentDescription = "Waiting for QR code scan"
                    },
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Waiting for scan\u2026",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
        ) {
            Text("Cancel Pairing")
        }
    }
}

/**
 * Single numbered instruction step in the pairing flow.
 *
 * @param number Step number displayed in bold.
 * @param text Instruction text for this step.
 */
@Composable
private fun PairingStep(
    number: String,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Conversation allowlist management view.
 *
 * Displays a scrollable list of bridged conversations with toggle switches
 * to grant or revoke AI agent access. Toggling on opens the
 * [ConversationAllowlistSheet] for time window selection.
 *
 * @param conversations List of bridged conversations from the message store.
 * @param onSetAllowed Callback to update a conversation's allowed status.
 */
@Composable
private fun AllowlistContent(
    conversations: List<FfiBridgedConversation>,
    onSetAllowed: (conversationId: String, allowed: Boolean, windowStartMs: Long?) -> Unit,
) {
    var sheetConversation by remember { mutableStateOf<FfiBridgedConversation?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Conversations",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            text = "Toggle which conversations your AI agent can read.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (conversations.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        "No conversations synced yet. " +
                            "Messages will appear as they arrive.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(
                    items = conversations,
                    key = { it.id },
                    contentType = { "conversation" },
                ) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        onToggle = { enabled ->
                            if (enabled) {
                                sheetConversation = conversation
                            } else {
                                onSetAllowed(conversation.id, false, null)
                            }
                        },
                    )
                }
            }
        }
    }

    sheetConversation?.let { conversation ->
        ConversationAllowlistSheet(
            conversationName = conversation.displayName,
            onConfirm = { windowStartMs ->
                onSetAllowed(conversation.id, true, windowStartMs)
                sheetConversation = null
            },
            onDismiss = { sheetConversation = null },
        )
    }
}

/**
 * Single conversation row in the allowlist.
 *
 * Shows the contact or group name, last message preview, and a toggle
 * switch for agent access.
 *
 * @param conversation The bridged conversation data.
 * @param onToggle Callback when the toggle state changes.
 */
@Composable
private fun ConversationRow(
    conversation: FfiBridgedConversation,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (conversation.lastMessagePreview.isNotBlank()) {
                    Text(
                        text = conversation.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            val toggleDesc = if (conversation.agentAllowed) "enabled" else "disabled"
            Switch(
                checked = conversation.agentAllowed,
                onCheckedChange = onToggle,
                modifier =
                    Modifier.semantics {
                        contentDescription = "${conversation.displayName} agent access"
                        stateDescription = toggleDesc
                    },
            )
        }
    }
}

/**
 * Error state content with retry button.
 *
 * @param message Human-readable error description.
 * @param onRetry Callback to retry the failed operation.
 */
@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

/**
 * Confirmation dialog for disconnect actions.
 *
 * @param title Dialog title text.
 * @param message Dialog body text explaining the action.
 * @param onConfirm Callback when the user confirms.
 * @param onDismiss Callback when the dialog is dismissed.
 */
@Composable
private fun DisconnectConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "Confirm",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
