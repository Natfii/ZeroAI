/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ui.component.AnthropicCodeSheet
import com.zeroclaw.android.ui.component.ContentPane
import com.zeroclaw.android.ui.component.ErrorCard
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.MiniZeroMascot
import com.zeroclaw.android.ui.component.MiniZeroMascotState
import com.zeroclaw.android.ui.component.ProviderIcon

/** Size of the mascot shown in the provider logins header. */
private val HeaderMascotSize = 48.dp

/** Vertical spacing below the provider logins header. */
private val HeaderBottomSpacing = 16.dp

/**
 * Screen for managing OAuth and CLI-backed provider login sessions.
 *
 * Displays connection status for each OAuth-capable provider (Anthropic, OpenAI,
 * Google Account) with actions to sign in or disconnect. This surface is distinct
 * from the API Keys screen, which handles only manually entered provider API keys.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param providerConnectionsViewModel ViewModel providing connection state and actions.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ProviderConnectionsScreen(
    edgeMargin: Dp,
    providerConnectionsViewModel: ProviderConnectionsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by providerConnectionsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarMessage by
        providerConnectionsViewModel.snackbarMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var disconnectTarget by remember { mutableStateOf<ProviderConnectionItem?>(null) }

    val anthropicSheetVisible by
        providerConnectionsViewModel.anthropicSheetVisible.collectAsStateWithLifecycle()
    val anthropicSheetLoading by
        providerConnectionsViewModel.anthropicSheetLoading.collectAsStateWithLifecycle()
    val anthropicSheetError by
        providerConnectionsViewModel.anthropicSheetError.collectAsStateWithLifecycle()

    AnthropicCodeSheet(
        visible = anthropicSheetVisible,
        onSubmit = providerConnectionsViewModel::submitAnthropicCode,
        onDismiss = providerConnectionsViewModel::dismissAnthropicSheet,
        isLoading = anthropicSheetLoading,
        errorMessage = anthropicSheetError,
    )

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            providerConnectionsViewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        ContentPane(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(horizontal = edgeMargin),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(8.dp))

                when (val state = uiState) {
                    is ProviderConnectionsUiState.Loading -> {
                        LoadingIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    is ProviderConnectionsUiState.Error -> {
                        ErrorCard(
                            message = state.detail,
                            onRetry = { providerConnectionsViewModel.loadConnections() },
                        )
                    }
                    is ProviderConnectionsUiState.Content -> {
                        ProviderConnectionsHeader()
                        Spacer(modifier = Modifier.height(HeaderBottomSpacing))

                        if (state.providers.isEmpty()) {
                            ProviderConnectionsEmptyState()
                        } else {
                            ProviderConnectionsList(
                                providers = state.providers,
                                onConnect = { provider ->
                                    providerConnectionsViewModel.connectProvider(
                                        context,
                                        provider.providerId,
                                    )
                                },
                                onDisconnect = { provider -> disconnectTarget = provider },
                            )
                        }
                    }
                }
            }
        }
    }

    disconnectTarget?.let { provider ->
        DisconnectProviderDialog(
            provider = provider,
            onConfirm = {
                providerConnectionsViewModel.disconnectProvider(provider.providerId)
                disconnectTarget = null
            },
            onDismiss = { disconnectTarget = null },
        )
    }
}

/**
 * Friendly header introducing provider login management.
 *
 * Keeps the vector mascot visible on the settings surface so provider login work shares the same
 * visual system as onboarding and the terminal.
 */
@Composable
private fun ProviderConnectionsHeader() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = "Provider logins"
                },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniZeroMascot(
            state = MiniZeroMascotState.Peek,
            size = HeaderMascotSize,
            contentDescription = null,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = "Provider Logins",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Manage Google account, ChatGPT, and Claude Code logins separately from manual API keys. Provider routes live in Agents, and Google apps live in Hub > Apps or Google Account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Empty state shown when no provider login cards are currently available.
 */
@Composable
private fun ProviderConnectionsEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MiniZeroMascot(
            state = MiniZeroMascotState.Idle,
            size = 96.dp,
            contentDescription = "Mini Zero mascot",
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No provider login cards are available right now.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Use the cards below when OAuth-capable provider logins are available on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Lazy column of provider login cards.
 *
 * @param providers List of provider connection items to render.
 * @param onConnect Called when the user taps "Sign In" for a disconnected provider.
 * @param onDisconnect Called when the user taps "Disconnect" for a connected provider.
 */
@Composable
private fun ProviderConnectionsList(
    providers: List<ProviderConnectionItem>,
    onConnect: (ProviderConnectionItem) -> Unit,
    onDisconnect: (ProviderConnectionItem) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = providers,
            key = { it.providerId },
            contentType = { "provider_connection" },
        ) { provider ->
            val onConnectItem = remember(provider.providerId) { { onConnect(provider) } }
            val onDisconnectItem = remember(provider.providerId) { { onDisconnect(provider) } }
            ProviderConnectionCard(
                provider = provider,
                onConnect = onConnectItem,
                onDisconnect = onDisconnectItem,
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * Card showing connection status for a single provider login.
 *
 * Displays the provider name, its connection status, and either a "Sign In" button
 * or "Disconnect" button depending on whether the provider is currently connected.
 * A [CircularProgressIndicator] replaces the action button while an OAuth flow is
 * in progress for this provider.
 *
 * @param provider The provider connection item to render.
 * @param onConnect Called when the user taps the "Sign In" button.
 * @param onDisconnect Called when the user taps the "Disconnect" button.
 * @param modifier Modifier applied to the card.
 */
@Composable
private fun ProviderConnectionCard(
    provider: ProviderConnectionItem,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = provider.connectedProfile != null
    val statusText = if (connected) "Connected" else "Not connected"
    val statusColor =
        if (connected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        "${provider.displayName}, " +
                        if (connected) "connected" else "not connected"
                },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProviderIcon(provider = providerIconId(provider))
                    Column {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                        )
                    }
                }

                when {
                    provider.oauthInProgress -> {
                        CircularProgressIndicator(
                            modifier =
                                Modifier
                                    .size(24.dp)
                                    .semantics { contentDescription = "Sign-in in progress" },
                            strokeWidth = 2.dp,
                        )
                    }
                    connected -> {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Text("Disconnect")
                        }
                    }
                    else -> {
                        Button(
                            onClick = onConnect,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Text("Sign In")
                        }
                    }
                }
            }

            provider.connectedProfile?.let { profile ->
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = profile.kind,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    profile.accountLabel?.let { accountLabel ->
                        Text(
                            text = accountLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    profile.detailLabel?.let { detailLabel ->
                        Text(
                            text = detailLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    profile.expiryLabel?.let { expiry ->
                        Text(
                            text = "Expires: $expiry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Confirmation dialog shown before disconnecting a provider login.
 *
 * Displays the provider name so the user can verify which session will be removed.
 *
 * @param provider The provider targeted for disconnection.
 * @param onConfirm Called when the user confirms the disconnect.
 * @param onDismiss Called when the user cancels.
 */
@Composable
private fun DisconnectProviderDialog(
    provider: ProviderConnectionItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect ${provider.displayName}?") },
        text = {
            Text(
                "Remove the OAuth session for ${provider.displayName}? " +
                    "You can sign in again at any time.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Disconnect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun providerIconId(provider: ProviderConnectionItem): String =
    when (provider.providerId) {
        "openai" -> "openai"
        "anthropic" -> "anthropic"
        "google-gemini" -> "google-gemini"
        "gemini" -> "google-gemini"
        "chatgpt" -> "openai"
        "claude-code" -> "anthropic"
        else -> provider.authProfileProvider
    }
