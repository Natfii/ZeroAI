/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.SlotCredentialType
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.data.remote.ModelFetcher
import com.zeroclaw.android.data.validation.ProviderValidator
import com.zeroclaw.android.data.validation.ValidationResult
import com.zeroclaw.android.model.ModelListFormat
import com.zeroclaw.android.ui.screen.onboarding.state.ProviderStepState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Debounce delay before fetching models after a provider, key, or slot change.
 */
private const val MODEL_FETCH_DEBOUNCE_MS = 500L

/**
 * Owns the provider-step setters (provider id, slot, variant, API key,
 * base URL, model) plus the debounced live-model-fetch and credential
 * validation. Extracted from [OnboardingCoordinator] so the coordinator
 * no longer carries ~200 lines of provider plumbing.
 *
 * Maintains a single internal [Job] for the debounced fetch so rapid
 * keystrokes coalesce into one network call.
 */
internal class OnboardingProviderHandler(
    private val providerState: MutableStateFlow<ProviderStepState>,
    private val scope: CoroutineScope,
    private val application: ZeroAIApplication,
) {
    private var modelFetchJob: Job? = null
    private var _userChangedProvider: Boolean = false

    /**
     * `true` once the user has explicitly chosen a provider via
     * [setProvider] or [setProviderSlot] in this wizard session. The
     * coordinator's settings-prefill skips overwriting the provider
     * choice when this flag is set, so a mid-wizard refresh does not
     * clobber the user's selection.
     */
    val userChangedProvider: Boolean get() = _userChangedProvider

    /**
     * Sets the selected provider, auto-populates the base URL from the
     * registry, and triggers a debounced model fetch. The model field
     * starts empty and is chosen from live-fetched suggestions.
     *
     * @param id Canonical provider ID from the registry.
     */
    fun setProvider(id: String) {
        val canonicalId = ProviderRegistry.findById(id)?.id ?: id.lowercase()
        ProviderSlotRegistry.resolveSlotId(canonicalId, isOAuth = false)?.let { slotId ->
            setProviderSlot(slotId)
            return
        }
        _userChangedProvider = true
        val info = ProviderRegistry.findById(id)
        providerState.value =
            providerState.value.copy(
                slotId = "",
                providerId = id,
                baseUrl = info?.defaultBaseUrl.orEmpty(),
                model = "",
                validationResult = ValidationResult.Idle,
                availableModels = emptyList(),
            )
        scheduleFetchModels()
    }

    /**
     * Sets the selected fixed provider slot for onboarding. Restores any
     * existing OAuth profile metadata so the user sees their connected
     * email without re-running the flow.
     *
     * @param slotId Stable provider-slot identifier from [ProviderSlotRegistry].
     */
    fun setProviderSlot(slotId: String) {
        val slot = ProviderSlotRegistry.findById(slotId) ?: return
        val info = ProviderRegistry.findById(slot.providerRegistryId)
        val profile =
            if (slot.credentialType == SlotCredentialType.OAUTH) {
                AuthProfileStore.findStandaloneProfile(application, slot.providerRegistryId)
            } else {
                null
            }
        _userChangedProvider = true
        providerState.value =
            providerState.value.copy(
                slotId = slot.slotId,
                providerId = slot.providerRegistryId,
                apiKey = "",
                baseUrl =
                    info
                        ?.defaultBaseUrl
                        .orEmpty()
                        .takeIf { slot.credentialType == SlotCredentialType.URL_KEY }
                        .orEmpty(),
                model = "",
                validationResult = ValidationResult.Idle,
                availableModels = emptyList(),
                oauthEmail = profile?.accountId.orEmpty(),
                oauthExpiresAt = profile?.expiresAtMs ?: 0L,
            )
        scheduleFetchModels()
    }

    /**
     * Updates the provider ID to a regional variant without resetting
     * the API key, model, or slot ID.
     *
     * @param variantId Regional provider variant ID (e.g. `"qwen-cn"`).
     */
    fun setProviderVariant(variantId: String) {
        providerState.value =
            providerState.value.copy(
                providerId = variantId,
                validationResult = ValidationResult.Idle,
            )
    }

    /** Updates the API key value and triggers a debounced model fetch. */
    fun setApiKey(key: String) {
        providerState.value =
            providerState.value.copy(
                apiKey = key,
                validationResult = ValidationResult.Idle,
            )
        scheduleFetchModels()
    }

    /** Updates the base URL value. */
    fun setBaseUrl(url: String) {
        providerState.value = providerState.value.copy(baseUrl = url)
    }

    /** Updates the selected model name. */
    fun setModel(model: String) {
        providerState.value = providerState.value.copy(model = model)
    }

    /**
     * Validates the current provider credentials by probing the model
     * listing endpoint. Sets [ValidationResult.Loading] immediately, then
     * launches a coroutine that runs [ProviderValidator.validate] and
     * updates the result to a terminal state.
     */
    fun validateProvider() {
        val state = providerState.value
        providerState.value = state.copy(validationResult = ValidationResult.Loading)
        scope.launch {
            val result =
                ProviderValidator.validate(
                    providerId = state.providerId,
                    apiKey = state.apiKey,
                    baseUrl = state.baseUrl,
                )
            providerState.value = providerState.value.copy(validationResult = result)
        }
    }

    private fun scheduleFetchModels() {
        modelFetchJob?.cancel()
        modelFetchJob =
            scope.launch {
                delay(MODEL_FETCH_DEBOUNCE_MS)
                fetchModels()
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchModels() {
        val state = providerState.value
        if (state.providerId.isBlank()) return
        val info = ProviderRegistry.findById(state.providerId) ?: return
        if (info.modelListFormat == ModelListFormat.NONE) return
        if (info.modelListRequiresKey && state.apiKey.isBlank()) return

        providerState.value = state.copy(isLoadingModels = true)
        try {
            val result = ModelFetcher.fetchModels(info, state.apiKey, state.baseUrl)
            result.onSuccess { models ->
                providerState.value =
                    providerState.value.copy(
                        availableModels = models,
                        isLoadingModels = false,
                    )
            }
            result.onFailure {
                providerState.value = providerState.value.copy(isLoadingModels = false)
            }
        } catch (_: Exception) {
            providerState.value = providerState.value.copy(isLoadingModels = false)
        }
    }
}
