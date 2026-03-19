/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ui.component.AnthropicCodeSheet
import com.zeroclaw.android.ui.component.ContentPane
import com.zeroclaw.android.ui.component.ErrorCard
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.ProviderIcon
import com.zeroclaw.android.ui.component.ReasoningEffortDropdown
import com.zeroclaw.android.ui.component.setup.ProviderSlotSetupSection

/**
 * Fixed provider-slot detail screen.
 *
 * @param slotId Stable slot ID to display.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param viewModel The [ProviderSlotDetailViewModel] for slot state.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ProviderSlotDetailScreen(
    slotId: String,
    edgeMargin: Dp,
    viewModel: ProviderSlotDetailViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val anthropicSheetVisible by viewModel.anthropicSheetVisible.collectAsStateWithLifecycle()
    val anthropicSheetLoading by viewModel.anthropicSheetLoading.collectAsStateWithLifecycle()
    val anthropicSheetError by viewModel.anthropicSheetError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(slotId) {
        viewModel.load(slotId)
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbar()
        }
    }

    AnthropicCodeSheet(
        visible = anthropicSheetVisible,
        onSubmit = viewModel::submitAnthropicCode,
        onDismiss = viewModel::dismissAnthropicSheet,
        isLoading = anthropicSheetLoading,
        errorMessage = anthropicSheetError,
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        ContentPane(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(horizontal = edgeMargin),
        ) {
            when (val state = uiState) {
                is ProviderSlotDetailUiState.Loading ->
                    LoadingIndicator(modifier = Modifier.fillMaxWidth())
                is ProviderSlotDetailUiState.Error ->
                    ErrorCard(message = state.message, onRetry = { viewModel.load(slotId) })
                is ProviderSlotDetailUiState.Content ->
                    ProviderSlotDetailContent(
                        state = state.detail,
                        onSave = viewModel::save,
                        onModelChanged = viewModel::setModel,
                        onApiKeyChanged = viewModel::setApiKey,
                        onBaseUrlChanged = viewModel::setBaseUrl,
                        onValidate = viewModel::validate,
                        onConnect = { viewModel.connect(context) },
                        onReasoningEffortChanged = viewModel::updateReasoningEffort,
                        onDisconnect = viewModel::disconnect,
                    )
            }
        }
    }
}

/**
 * Stateless content for a fixed provider-slot detail screen.
 *
 * @param state Loaded slot detail state.
 * @param onSave Callback to persist model/enabled changes.
 * @param onModelChanged Callback when the model field changes.
 * @param onApiKeyChanged Callback when the API key field changes.
 * @param onBaseUrlChanged Callback when the base URL field changes.
 * @param onValidate Callback to validate current provider credentials.
 * @param onConnect Callback to launch OAuth connection.
 * @param onReasoningEffortChanged Callback to update the global reasoning-effort override.
 * @param onDisconnect Callback to disconnect OAuth state.
 */
@Composable
private fun ProviderSlotDetailContent(
    state: ProviderSlotDetailState,
    onSave: (String, Boolean) -> Unit,
    onModelChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onValidate: () -> Unit,
    onConnect: () -> Unit,
    onReasoningEffortChanged: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var isEnabled by remember(state.agent.id, state.agent.isEnabled) {
        mutableStateOf(state.agent.isEnabled)
    }

    Column(
        modifier =
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderIcon(provider = state.slot.providerRegistryId)
            Text(
                text = state.slot.displayName,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Text(
            text = state.connectionSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        managedLoginRoutingNotice(state.slot.slotId)?.let { notice ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Heads up",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProviderSlotSetupSection(
                    slot = state.slot,
                    apiKey = state.apiKeyInput,
                    baseUrl = state.baseUrlInput,
                    selectedModel = state.modelInput,
                    availableModels = state.availableModels,
                    isLoadingModels = state.isLoadingModels,
                    validationResult = state.validationResult,
                    onApiKeyChanged = onApiKeyChanged,
                    onBaseUrlChanged = onBaseUrlChanged,
                    onModelChanged = onModelChanged,
                    onValidate = onValidate,
                    isOAuthInProgress = state.isOAuthInProgress,
                    oauthEmail = if (state.authProfile != null) state.oauthDisplayLabel else "",
                    onOAuthLogin = onConnect,
                    onOAuthDisconnect = onDisconnect,
                    showSkipHint = false,
                )
                if (state.slot.providerRegistryId == OPENAI_PROVIDER_ID && state.slot.routesModelRequests) {
                    ReasoningEffortDropdown(
                        selectedEffort = state.reasoningEffort,
                        onEffortSelected = onReasoningEffortChanged,
                        label = "Thinking level",
                        supportingText =
                            AnnotatedString(
                                "Global setting for OpenAI reasoning models. " +
                                    "Auto uses each model's default level.",
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.slot.routesModelRequests) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enabled",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Controls whether this slot participates in daemon routing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { isEnabled = it },
                        )
                    }
                    Button(
                        onClick = { onSave(state.modelInput, isEnabled) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save Slot")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(96.dp))
    }
}

private const val OPENAI_PROVIDER_ID = "openai"

private fun managedLoginRoutingNotice(slotId: String): String? =
    when (slotId) {
        "chatgpt" ->
            "ChatGPT login is connected separately from OpenAI API keys. " +
                "Live daemon routing still uses the OpenAI API slot today."
        "claude-code" ->
            "Claude login is connected separately from Anthropic API keys. " +
                "Live daemon routing still uses the Anthropic API slot today."
        else -> null
    }
