/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.data.ProviderSlot
import com.zeroclaw.android.data.SlotCredentialType
import com.zeroclaw.android.data.validation.ValidationResult
import com.zeroclaw.android.ui.component.ProviderIcon

private val SlotCardCornerRadius = 12.dp
private val SlotCardPadding = 16.dp
private val SlotBadgeCornerRadius = 8.dp

/**
 * Reusable selectable provider-slot card used by onboarding and provider setup flows.
 *
 * @param slot Fixed slot definition to display.
 * @param selected Whether this slot is currently selected.
 * @param onClick Callback invoked when the card is tapped.
 * @param modifier Modifier applied to the card.
 */
@Composable
fun ProviderSlotSelectionCard(
    slot: ProviderSlot,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        border =
            BorderStroke(
                width = 1.dp,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
            ),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        shape = RoundedCornerShape(SlotCardCornerRadius),
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        buildString {
                            append(slot.displayName)
                            append(", ")
                            append(if (selected) "selected" else "not selected")
                        }
                },
    ) {
        Column(
            modifier = Modifier.padding(SlotCardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProviderIcon(provider = slot.providerRegistryId)
                    Text(
                        text = slot.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(SlotBadgeCornerRadius),
                ) {
                    Text(
                        text = providerSlotCredentialLabel(slot.credentialType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                text = providerSlotCardDescription(slot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shared provider-slot setup section used by onboarding and the Agents detail screen.
 *
 * @param slot Slot currently being configured.
 * @param apiKey Current API key input value.
 * @param baseUrl Current base URL input value.
 * @param selectedModel Current model name input value.
 * @param availableModels Models fetched from the provider API.
 * @param isLoadingModels Whether a model fetch is in progress.
 * @param validationResult Current validation state.
 * @param onApiKeyChanged Callback when API key text changes.
 * @param onBaseUrlChanged Callback when base URL text changes.
 * @param onModelChanged Callback when model text changes.
 * @param onValidate Callback to trigger validation.
 * @param isOAuthInProgress Whether an OAuth/session login flow is running.
 * @param oauthEmail Connected OAuth account label, or blank when disconnected.
 * @param onOAuthLogin Callback to start OAuth/session login.
 * @param onOAuthDisconnect Callback to disconnect the OAuth/session login.
 * @param showSkipHint Whether to show the onboarding skip hint.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ProviderSlotSetupSection(
    slot: ProviderSlot,
    apiKey: String,
    baseUrl: String,
    selectedModel: String,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    validationResult: ValidationResult,
    onApiKeyChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onValidate: () -> Unit,
    isOAuthInProgress: Boolean,
    oauthEmail: String,
    onOAuthLogin: (() -> Unit)?,
    onOAuthDisconnect: (() -> Unit)?,
    showSkipHint: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Configure ${slot.displayName}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = providerSlotSetupDescription(slot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProviderSetupFlow(
            selectedProvider = slot.providerRegistryId,
            apiKey = apiKey,
            baseUrl = baseUrl,
            selectedModel = selectedModel,
            availableModels = availableModels,
            validationResult = validationResult,
            onProviderChanged = {},
            onApiKeyChanged = onApiKeyChanged,
            onBaseUrlChanged = onBaseUrlChanged,
            onModelChanged = onModelChanged,
            onValidate = onValidate,
            showSkipHint = showSkipHint,
            isLoadingModels = isLoadingModels,
            isLiveModelData = availableModels.isNotEmpty(),
            isOAuthInProgress = isOAuthInProgress,
            oauthEmail = oauthEmail,
            onOAuthLogin = onOAuthLogin,
            onOAuthDisconnect = onOAuthDisconnect,
            showProviderPicker = false,
            scrollable = false,
            credentialTypeOverride = slot.credentialType,
            oauthButtonLabelOverride = providerSlotOauthButtonLabel(slot),
            showModelPicker = slot.routesModelRequests,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Returns the user-facing credential label for a provider slot. */
fun providerSlotCredentialLabel(credentialType: SlotCredentialType): String =
    when (credentialType) {
        SlotCredentialType.API_KEY -> "API Key"
        SlotCredentialType.OAUTH -> "Login"
        SlotCredentialType.URL_KEY -> "Local URL"
    }

/** Returns the short description shown on a slot selection card. */
fun providerSlotCardDescription(slot: ProviderSlot): String =
    when (slot.slotId) {
        "gemini-api" -> "Use a Gemini API key from AI Studio."
        "openai-api" -> "Use a direct OpenAI API key."
        "chatgpt" ->
            "Connect your ChatGPT account. Live daemon routing still uses the OpenAI API slot today."
        "anthropic-api" -> "Use a direct Anthropic API key."
        "claude-code" ->
            "Connect your Claude account. Live daemon routing still uses the Anthropic API slot today."
        "ollama" -> "Point Zero at your local Ollama server and choose a model."
        else -> slot.displayName
    }

/** Returns the explanatory setup blurb for a provider slot. */
fun providerSlotSetupDescription(slot: ProviderSlot): String =
    when (slot.slotId) {
        "chatgpt" ->
            "ChatGPT login is stored separately from OpenAI API keys. " +
                "Use the OpenAI API slot for live daemon routing today."
        "claude-code" ->
            "Claude login is stored separately from Anthropic API keys. " +
                "Use the Anthropic API slot for live daemon routing today."
        else ->
            when (slot.credentialType) {
                SlotCredentialType.API_KEY ->
                    "Enter the key and choose a default model for this provider."
                SlotCredentialType.OAUTH ->
                    "Sign in once and Zero will keep using that connected session for this provider slot."
                SlotCredentialType.URL_KEY ->
                    "Set the local server URL, then choose which model Zero should call."
            }
    }

/** Returns the provider-branded OAuth/session button label for a provider slot. */
fun providerSlotOauthButtonLabel(slot: ProviderSlot): String =
    when (slot.slotId) {
        "chatgpt" -> "Connect ChatGPT"
        "claude-code" -> "Connect Claude Code"
        else -> "Connect ${slot.displayName}"
    }
