/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("FunctionNaming")

package com.zeroclaw.android.ui.screen.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.ProviderSlot
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.validation.ValidationResult
import com.zeroclaw.android.ui.component.setup.ProviderSlotSelectionCard
import com.zeroclaw.android.ui.component.setup.ProviderSlotSetupSection

/** Standard spacing between form fields. */
private const val FIELD_SPACING_DP = 16

/** Spacing after the section description. */
private const val DESCRIPTION_SPACING_DP = 24

/** Fixed onboarding catalog of provider slots. */
private val OnboardingSlots: List<ProviderSlot> = ProviderSlotRegistry.allRouting()

/**
 * Onboarding step for selecting and configuring the first provider.
 *
 * Replaces the legacy generic provider dropdown with the same fixed-slot
 * mental model used by the Agents screen. The step now uses a guided
 * pick-then-configure layout so the setup form is not buried below the
 * provider catalog on phones.
 *
 * @param selectedSlotId Currently selected fixed slot ID.
 * @param selectedProvider Currently selected provider registry ID (may include
 *   regional variants such as `"qwen-cn"` or `"qwen-us"`).
 * @param apiKey Current API key input value.
 * @param baseUrl Current base URL input value.
 * @param selectedModel Current model name input value.
 * @param availableModels Models fetched by the coordinator from the provider's API.
 * @param isLoadingModels Whether the coordinator is currently fetching models.
 * @param validationResult Current state of the validation operation.
 * @param onSlotChanged Callback when the provider slot changes.
 * @param onApiKeyChanged Callback when API key text changes.
 * @param onBaseUrlChanged Callback when base URL text changes.
 * @param onModelChanged Callback when model text changes.
 * @param onValidate Callback to trigger credential validation.
 * @param onProviderVariantChanged Callback invoked when the user selects a regional
 *   provider variant (e.g., Qwen region picker). Receives the variant ID such as `"qwen-cn"`.
 * @param isOAuthInProgress Whether an OAuth login flow is currently running.
 * @param oauthEmail Display email or label for the connected OAuth session,
 *   or empty string when not connected.
 * @param onOAuthLogin Optional callback to initiate the OAuth login flow.
 * @param onOAuthDisconnect Optional callback to disconnect the current OAuth session.
 */
@Composable
@Suppress("LongParameterList")
fun ProviderStep(
    selectedSlotId: String,
    selectedProvider: String,
    apiKey: String,
    baseUrl: String,
    selectedModel: String,
    availableModels: List<String> = emptyList(),
    isLoadingModels: Boolean = false,
    validationResult: ValidationResult = ValidationResult.Idle,
    onSlotChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onValidate: () -> Unit = {},
    onProviderVariantChanged: (String) -> Unit = {},
    isOAuthInProgress: Boolean = false,
    oauthEmail: String = "",
    onOAuthLogin: (() -> Unit)? = null,
    onOAuthDisconnect: (() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    var showProviderPicker by
        rememberSaveable { mutableStateOf(selectedSlotId.isBlank() && selectedProvider.isBlank()) }
    val selectedSlot =
        remember(selectedSlotId, selectedProvider, oauthEmail) {
            ProviderSlotRegistry.findById(selectedSlotId)
                ?: selectedProvider
                    .takeIf { it.isNotBlank() }
                    ?.let { providerId ->
                        val canonicalId =
                            ProviderRegistry.findById(providerId)?.id ?: providerId
                        ProviderSlotRegistry
                            .resolveSlotId(
                                providerRegistryId = canonicalId,
                                isOAuth = oauthEmail.isNotBlank(),
                            )?.let(ProviderSlotRegistry::findById)
                            ?.takeIf { slot -> slot.routesModelRequests }
                    }
        }

    LaunchedEffect(selectedSlotId, selectedProvider) {
        if (selectedSlotId.isBlank() && selectedProvider.isBlank()) {
            showProviderPicker = true
        }
    }

    LaunchedEffect(showProviderPicker, selectedSlotId) {
        if (!showProviderPicker && selectedSlotId.isNotBlank()) {
            scrollState.animateScrollTo(0)
        }
    }

    Column(
        modifier =
            Modifier
                .imePadding()
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(FIELD_SPACING_DP.dp),
    ) {
        ProviderStepHeader(
            showProviderPicker = showProviderPicker || selectedSlot == null,
        )

        if (showProviderPicker || selectedSlot == null) {
            ProviderPicker(
                selectedSlotId = selectedSlot?.slotId ?: selectedSlotId,
                onSlotChanged = {
                    onSlotChanged(it)
                    showProviderPicker = false
                },
            )
        } else {
            SelectedProviderSection(
                slot = selectedSlot,
                selectedProvider = selectedProvider,
                apiKey = apiKey,
                baseUrl = baseUrl,
                selectedModel = selectedModel,
                availableModels = availableModels,
                isLoadingModels = isLoadingModels,
                validationResult = validationResult,
                onApiKeyChanged = onApiKeyChanged,
                onBaseUrlChanged = onBaseUrlChanged,
                onModelChanged = onModelChanged,
                onValidate = onValidate,
                onProviderVariantChanged = onProviderVariantChanged,
                isOAuthInProgress = isOAuthInProgress,
                oauthEmail = oauthEmail,
                onOAuthLogin = onOAuthLogin,
                onOAuthDisconnect = onOAuthDisconnect,
                onChangeProvider = { showProviderPicker = true },
            )
        }
    }
}

/**
 * Header content for the provider onboarding step.
 *
 * @param showProviderPicker Whether the user is currently choosing a provider.
 */
@Composable
private fun ProviderStepHeader(showProviderPicker: Boolean) {
    Text(
        text = "Provider",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text =
            if (showProviderPicker) {
                "Pick the provider Zero should use first. " +
                    "After you choose one, its setup appears right away."
            } else {
                "Configure your selected provider now. " +
                    "You can change it or add more providers later in the Agents tab."
            },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Provider picker stage for onboarding.
 *
 * @param selectedSlotId Currently highlighted provider slot.
 * @param onSlotChanged Callback invoked when the user picks a slot.
 */
@Composable
private fun ProviderPicker(
    selectedSlotId: String,
    onSlotChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FIELD_SPACING_DP.dp)) {
        OnboardingSlots.forEach { slot ->
            ProviderSlotSelectionCard(
                slot = slot,
                selected = slot.slotId == selectedSlotId,
                onClick = { onSlotChanged(slot.slotId) },
            )
        }
    }
}

/**
 * Selected-provider stage that keeps the chosen card visible above the form.
 *
 * @param slot Selected provider slot.
 * @param selectedProvider Effective provider ID (may include regional variant such as `"qwen-cn"`).
 * @param apiKey Current API key input value.
 * @param baseUrl Current base URL input value.
 * @param selectedModel Current model name input value.
 * @param availableModels Models fetched by the coordinator from the provider's API.
 * @param isLoadingModels Whether model loading is in progress.
 * @param validationResult Current validation state.
 * @param onApiKeyChanged Callback when API key text changes.
 * @param onBaseUrlChanged Callback when base URL text changes.
 * @param onModelChanged Callback when model text changes.
 * @param onValidate Callback to trigger credential validation.
 * @param onProviderVariantChanged Callback invoked when the user picks a regional variant.
 * @param isOAuthInProgress Whether an OAuth login flow is currently running.
 * @param oauthEmail Display email or label for the connected OAuth session.
 * @param onOAuthLogin Callback to initiate the OAuth login flow.
 * @param onOAuthDisconnect Callback to disconnect the current OAuth session.
 * @param onChangeProvider Callback to re-open the provider picker.
 */
@Composable
@Suppress("LongParameterList")
private fun SelectedProviderSection(
    slot: ProviderSlot,
    selectedProvider: String,
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
    onProviderVariantChanged: (String) -> Unit,
    isOAuthInProgress: Boolean,
    oauthEmail: String,
    onOAuthLogin: (() -> Unit)?,
    onOAuthDisconnect: (() -> Unit)?,
    onChangeProvider: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Selected provider",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProviderSlotSelectionCard(
            slot = slot,
            selected = true,
            onClick = onChangeProvider,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onChangeProvider) {
                Text("Change provider")
            }
        }
    }

    Spacer(modifier = Modifier.height((DESCRIPTION_SPACING_DP - FIELD_SPACING_DP).dp))
    ProviderSlotSetupSection(
        slot = slot,
        apiKey = apiKey,
        baseUrl = baseUrl,
        selectedModel = selectedModel,
        availableModels = availableModels,
        isLoadingModels = isLoadingModels,
        validationResult = validationResult,
        onApiKeyChanged = onApiKeyChanged,
        onBaseUrlChanged = onBaseUrlChanged,
        onModelChanged = onModelChanged,
        onValidate = onValidate,
        isOAuthInProgress = isOAuthInProgress,
        oauthEmail = oauthEmail,
        onOAuthLogin = onOAuthLogin,
        onOAuthDisconnect = onOAuthDisconnect,
        showSkipHint = true,
        selectedProviderVariant = selectedProvider,
        onProviderVariantChanged = onProviderVariantChanged,
        modifier = Modifier.fillMaxWidth(),
    )
}
