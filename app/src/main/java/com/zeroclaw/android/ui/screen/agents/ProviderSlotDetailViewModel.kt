/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.ProviderSlot
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.SlotCredentialType
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.data.oauth.PkceState
import com.zeroclaw.android.data.oauth.ProviderConnectionCoordinator
import com.zeroclaw.android.data.oauth.canonicalManagedProvider
import com.zeroclaw.android.data.remote.ModelFetcher
import com.zeroclaw.android.data.validation.ProviderValidator
import com.zeroclaw.android.data.validation.ValidationResult
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.ModelListFormat
import com.zeroclaw.android.service.SlotAwareAgentConfig
import com.zeroclaw.ffi.FfiAuthProfile
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val MODEL_FETCH_DEBOUNCE_MS = 500L

/**
 * Detail state for one fixed provider slot.
 *
 * @property slot Fixed provider-slot definition.
 * @property agent Persisted slot row backing the daemon config.
 * @property apiKey Matching API-key repository record when present.
 * @property authProfile Matching Rust auth-profile record when present.
 * @property connectionSummary Human-readable connection summary.
 * @property modelInput Current editable model value.
 * @property apiKeyInput Current editable API key value.
 * @property baseUrlInput Current editable base URL value.
 * @property reasoningEffort Current global reasoning-effort override.
 * @property validationResult Current live validation state.
 * @property availableModels Live models fetched from the provider API.
 * @property isLoadingModels Whether a model fetch is currently running.
 * @property isOAuthInProgress Whether an OAuth/session login flow is running.
 * @property oauthDisplayLabel Display label for a connected OAuth/session login.
 */
data class ProviderSlotDetailState(
    val slot: ProviderSlot,
    val agent: Agent,
    val apiKey: ApiKey?,
    val authProfile: FfiAuthProfile?,
    val connectionSummary: String,
    val modelInput: String,
    val apiKeyInput: String,
    val baseUrlInput: String,
    val reasoningEffort: String,
    val validationResult: ValidationResult,
    val availableModels: List<String>,
    val isLoadingModels: Boolean,
    val isOAuthInProgress: Boolean,
    val oauthDisplayLabel: String,
)

/** UI state for the provider-slot detail screen. */
sealed interface ProviderSlotDetailUiState {
    /** Slot detail is loading. */
    data object Loading : ProviderSlotDetailUiState

    /**
     * Slot detail failed to load.
     *
     * @property message Human-readable error message.
     */
    data class Error(
        val message: String,
    ) : ProviderSlotDetailUiState

    /**
     * Slot detail loaded successfully.
     *
     * @property detail Current slot detail snapshot.
     */
    data class Content(
        val detail: ProviderSlotDetailState,
    ) : ProviderSlotDetailUiState
}

/**
 * ViewModel for the fixed provider-slot detail flow.
 *
 * @param application Application context for repository access.
 */
class ProviderSlotDetailViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val repository = app.agentRepository
    private val apiKeyRepository = app.apiKeyRepository
    private val settingsRepository = app.settingsRepository
    private val daemonBridge = app.daemonBridge
    private val coordinator = ProviderConnectionCoordinator(app)

    private val _uiState =
        MutableStateFlow<ProviderSlotDetailUiState>(ProviderSlotDetailUiState.Loading)
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    private val _anthropicSheetVisible = MutableStateFlow(false)
    private val _anthropicSheetLoading = MutableStateFlow(false)
    private val _anthropicSheetError = MutableStateFlow<String?>(null)

    private var currentSlotId: String? = null
    private var modelFetchJob: Job? = null
    private var anthropicPkce: PkceState? = null

    /** Current UI state for the slot detail screen. */
    val uiState: StateFlow<ProviderSlotDetailUiState> = _uiState.asStateFlow()

    /** One-shot snackbar message for the slot detail screen. */
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    /** Whether the Anthropic paste-back sheet is visible. */
    val anthropicSheetVisible: StateFlow<Boolean> = _anthropicSheetVisible.asStateFlow()

    /** Whether the Anthropic sheet is exchanging a token. */
    val anthropicSheetLoading: StateFlow<Boolean> = _anthropicSheetLoading.asStateFlow()

    /** Error message for the Anthropic paste-back sheet. */
    val anthropicSheetError: StateFlow<String?> = _anthropicSheetError.asStateFlow()

    /**
     * Loads the fixed slot with [slotId].
     *
     * @param slotId Stable slot ID from [ProviderSlotRegistry].
     */
    fun load(slotId: String) {
        if (slotId == currentSlotId && _uiState.value is ProviderSlotDetailUiState.Content) {
            return
        }
        currentSlotId = slotId
        _uiState.value = ProviderSlotDetailUiState.Loading
        viewModelScope.launch { refresh(slotId) }
    }

    /**
     * Saves model and enabled state changes for the current slot.
     *
     * @param modelName Model name to persist.
     * @param isEnabled Whether the slot row should be enabled.
     */
    fun save(
        modelName: String,
        isEnabled: Boolean,
    ) {
        val detail =
            (_uiState.value as? ProviderSlotDetailUiState.Content)?.detail
                ?: return
        if (!detail.slot.routesModelRequests) {
            _snackbarMessage.value = "This connection does not have daemon routing settings"
            return
        }
        if (
            isEnabled &&
            detail.slot.credentialType == SlotCredentialType.OAUTH &&
            !SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
                provider = detail.slot.providerRegistryId,
                apiKey = detail.apiKey,
                authProfiles = detail.authProfile?.let(::listOf).orEmpty(),
            )
        ) {
            _snackbarMessage.value =
                when (detail.slot.slotId) {
                    "chatgpt" ->
                        "ChatGPT login is connected, but live routing still needs the OpenAI API slot."
                    "claude-code" ->
                        "Claude login is connected, but live routing still needs the Anthropic API slot."
                    else -> "This connected login is not ready for live daemon routing yet."
                }
            return
        }
        viewModelScope.launch {
            saveSlotCredentials(detail)
            repository.save(
                detail.agent.copy(
                    id = detail.slot.slotId,
                    slotId = detail.slot.slotId,
                    name = detail.slot.displayName,
                    provider = detail.slot.providerRegistryId,
                    modelName = modelName.trim(),
                    isEnabled = isEnabled,
                ),
            )
            daemonBridge.markRestartRequired()
            _snackbarMessage.value = "Slot updated"
            refresh(detail.slot.slotId)
        }
    }

    /**
     * Starts the OAuth connection flow for the current slot when applicable.
     *
     * @param context Activity context used to launch the flow.
     */
    fun connect(context: Context) {
        val detail =
            (_uiState.value as? ProviderSlotDetailUiState.Content)?.detail
                ?: return
        if (detail.slot.credentialType != SlotCredentialType.OAUTH) return
        if (detail.slot.providerRegistryId == ANTHROPIC_PROVIDER_ID) {
            updateContent { copy(isOAuthInProgress = true) }
            anthropicPkce = coordinator.startAnthropicFlow(context)
            _anthropicSheetVisible.value = true
            return
        }
        viewModelScope.launch {
            updateContent { copy(isOAuthInProgress = true) }
            runCatching {
                coordinator.connectProvider(
                    context = context,
                    providerId = detail.slot.providerRegistryId,
                )
                daemonBridge.markRestartRequired()
                _snackbarMessage.value = "Connected"
            }.onFailure { error ->
                _snackbarMessage.value = error.message ?: "Connection failed"
            }.also {
                updateContent { copy(isOAuthInProgress = false) }
            }
            refresh(detail.slot.slotId)
        }
    }

    /**
     * Submits a pasted Anthropic authorization code for token exchange.
     *
     * @param code Cleaned authorization code from the paste-back sheet.
     */
    @Suppress("TooGenericExceptionCaught")
    fun submitAnthropicCode(code: String) {
        val pkce = anthropicPkce ?: return
        _anthropicSheetLoading.value = true
        _anthropicSheetError.value = null
        viewModelScope.launch {
            try {
                coordinator.completeAnthropicFlow(code, pkce)
                daemonBridge.markRestartRequired()
                _snackbarMessage.value = "Claude Code connected"
                dismissAnthropicSheet()
                currentSlotId?.let { refresh(it) }
            } catch (e: com.zeroclaw.android.data.oauth.OAuthExchangeException) {
                _anthropicSheetError.value =
                    "Invalid or expired code \u2014 please try again (HTTP ${e.httpStatusCode})"
            } catch (e: Exception) {
                _anthropicSheetError.value =
                    "Connection failed \u2014 ${e.message ?: "unknown error"}"
            } finally {
                _anthropicSheetLoading.value = false
            }
        }
    }

    /** Dismisses the Anthropic paste-back sheet and clears related state. */
    fun dismissAnthropicSheet() {
        _anthropicSheetVisible.value = false
        _anthropicSheetLoading.value = false
        _anthropicSheetError.value = null
        anthropicPkce = null
        updateContent { copy(isOAuthInProgress = false) }
    }

    /** Disconnects the current OAuth slot when applicable. */
    fun disconnect() {
        val detail =
            (_uiState.value as? ProviderSlotDetailUiState.Content)?.detail
                ?: return
        if (detail.slot.credentialType != SlotCredentialType.OAUTH) return
        viewModelScope.launch {
            updateContent { copy(isOAuthInProgress = true) }
            runCatching {
                coordinator.disconnectProvider(detail.slot.providerRegistryId)
                daemonBridge.markRestartRequired()
                _snackbarMessage.value = "Disconnected"
            }.onFailure { error ->
                _snackbarMessage.value = error.message ?: "Disconnect failed"
            }.also {
                updateContent { copy(isOAuthInProgress = false) }
            }
            refresh(detail.slot.slotId)
        }
    }

    /** Updates the editable API key and schedules model reloading when relevant. */
    fun setApiKey(value: String) {
        updateContent {
            copy(
                apiKeyInput = value,
                validationResult = ValidationResult.Idle,
            )
        }
        scheduleFetchModels()
    }

    /** Updates the editable base URL and schedules model reloading when relevant. */
    fun setBaseUrl(value: String) {
        updateContent {
            copy(
                baseUrlInput = value,
                validationResult = ValidationResult.Idle,
            )
        }
        scheduleFetchModels()
    }

    /** Updates the editable model input. */
    fun setModel(value: String) {
        updateContent { copy(modelInput = value) }
    }

    /** Validates the current non-OAuth provider credentials. */
    fun validate() {
        val detail =
            (_uiState.value as? ProviderSlotDetailUiState.Content)?.detail
                ?: return
        if (detail.slot.credentialType == SlotCredentialType.OAUTH) return
        viewModelScope.launch {
            updateContent { copy(validationResult = ValidationResult.Loading) }
            val result =
                ProviderValidator.validate(
                    providerId = detail.slot.providerRegistryId,
                    apiKey = detail.apiKeyInput,
                    baseUrl = detail.baseUrlInput,
                )
            updateContent { copy(validationResult = result) }
        }
    }

    /** Clears the current snackbar message. */
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    /**
     * Updates the global reasoning-effort override for OpenAI reasoning models.
     *
     * @param effort New reasoning-effort value.
     */
    fun updateReasoningEffort(effort: String) {
        viewModelScope.launch {
            settingsRepository.setReasoningEffort(effort)
            daemonBridge.markRestartRequired()
            updateContent { copy(reasoningEffort = effort) }
            _snackbarMessage.value = "Thinking level updated"
        }
    }

    private suspend fun refresh(slotId: String) {
        runCatching {
            val slot = requireNotNull(ProviderSlotRegistry.findById(slotId)) { "Unknown slot" }
            val agents = repository.agents.first()
            val apiKeys = apiKeyRepository.keys.first()
            val profiles = AuthProfileStore.listStandalone(app)
            val settings = settingsRepository.settings.first()
            buildState(
                slot = slot,
                agents = agents,
                apiKeys = apiKeys,
                profiles = profiles,
                reasoningEffort = settings.reasoningEffort,
            )
        }.onSuccess { detail ->
            _uiState.value = ProviderSlotDetailUiState.Content(detail)
            if (detail.slot.credentialType != SlotCredentialType.OAUTH &&
                (detail.apiKeyInput.isNotBlank() || detail.baseUrlInput.isNotBlank())
            ) {
                scheduleFetchModels()
            }
        }.onFailure { error ->
            _uiState.value =
                ProviderSlotDetailUiState.Error(error.message ?: "Failed to load slot")
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun buildState(
        slot: ProviderSlot,
        agents: List<Agent>,
        apiKeys: List<ApiKey>,
        profiles: List<FfiAuthProfile>,
        reasoningEffort: String,
    ): ProviderSlotDetailState {
        val agent =
            agents.firstOrNull { it.slotId == slot.slotId || it.id == slot.slotId }
                ?: Agent(
                    id = slot.slotId,
                    name = slot.displayName,
                    provider = slot.providerRegistryId,
                    modelName = "",
                    isEnabled = false,
                    slotId = slot.slotId,
                )
        val apiKey = slot.matchingApiKey(apiKeys)
        val authProfile = slot.matchingAuthProfile(profiles)
        val connectionSummary =
            when (slot.credentialType) {
                SlotCredentialType.OAUTH ->
                    if (authProfile == null) {
                        "Not connected"
                    } else {
                        "Connected"
                    }
                SlotCredentialType.API_KEY ->
                    if (apiKey?.key?.isNotBlank() == true) {
                        "API key saved"
                    } else {
                        "No API key"
                    }
                SlotCredentialType.URL_KEY ->
                    when {
                        apiKey?.baseUrl?.isNotBlank() == true -> apiKey.baseUrl
                        apiKey?.key?.isNotBlank() == true -> "Configured"
                        else -> "Not configured"
                    }
            }
        return ProviderSlotDetailState(
            slot = slot,
            agent = agent,
            apiKey = apiKey,
            authProfile = authProfile,
            connectionSummary = connectionSummary,
            modelInput = agent.modelName,
            apiKeyInput = apiKey?.key.orEmpty(),
            baseUrlInput = apiKey?.baseUrl.orEmpty(),
            reasoningEffort = reasoningEffort,
            validationResult = ValidationResult.Idle,
            availableModels = emptyList(),
            isLoadingModels = false,
            isOAuthInProgress = false,
            oauthDisplayLabel = authProfile?.accountId?.takeIf { it.isNotBlank() } ?: oauthFallbackLabel(slot),
        )
    }

    private fun scheduleFetchModels() {
        val detail =
            (_uiState.value as? ProviderSlotDetailUiState.Content)?.detail
                ?: return
        if (detail.slot.credentialType == SlotCredentialType.OAUTH) return
        modelFetchJob?.cancel()
        updateContent { copy(availableModels = emptyList()) }
        modelFetchJob =
            viewModelScope.launch {
                delay(MODEL_FETCH_DEBOUNCE_MS)
                fetchModels()
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchModels() {
        val detail =
            (_uiState.value as? ProviderSlotDetailUiState.Content)?.detail
                ?: return
        val slot = detail.slot
        if (slot.credentialType == SlotCredentialType.OAUTH) return
        val info = ProviderRegistry.findById(slot.providerRegistryId) ?: return
        if (info.modelListFormat == ModelListFormat.NONE) return
        if (detail.apiKeyInput.isBlank() && detail.baseUrlInput.isBlank()) return

        updateContent { copy(isLoadingModels = true) }
        try {
            val result = ModelFetcher.fetchModels(info, detail.apiKeyInput, detail.baseUrlInput)
            result
                .onSuccess { models ->
                    updateContent {
                        copy(
                            availableModels = models,
                            isLoadingModels = false,
                        )
                    }
                }.onFailure {
                    updateContent { copy(isLoadingModels = false) }
                }
        } catch (_: Exception) {
            updateContent { copy(isLoadingModels = false) }
        }
    }

    private suspend fun saveSlotCredentials(detail: ProviderSlotDetailState) {
        if (detail.slot.credentialType == SlotCredentialType.OAUTH) return

        val trimmedKey = detail.apiKeyInput.trim()
        val trimmedUrl = detail.baseUrlInput.trim()
        val existingKey = detail.apiKey

        val shouldDelete =
            when (detail.slot.credentialType) {
                SlotCredentialType.API_KEY -> trimmedKey.isBlank()
                SlotCredentialType.URL_KEY -> trimmedKey.isBlank() && trimmedUrl.isBlank()
                SlotCredentialType.OAUTH -> false
            }

        if (shouldDelete) {
            existingKey?.let { apiKeyRepository.delete(it.id) }
            return
        }

        apiKeyRepository.save(
            ApiKey(
                id = existingKey?.id ?: UUID.randomUUID().toString(),
                provider = detail.slot.providerRegistryId,
                key = trimmedKey,
                baseUrl = trimmedUrl,
                createdAt = existingKey?.createdAt ?: System.currentTimeMillis(),
                status = existingKey?.status ?: detail.apiKey?.status ?: com.zeroclaw.android.model.KeyStatus.ACTIVE,
                refreshToken = existingKey?.refreshToken.orEmpty(),
                expiresAt = existingKey?.expiresAt ?: 0L,
            ),
        )
    }

    private fun updateContent(transform: ProviderSlotDetailState.() -> ProviderSlotDetailState) {
        val current = _uiState.value as? ProviderSlotDetailUiState.Content ?: return
        _uiState.value = ProviderSlotDetailUiState.Content(current.detail.transform())
    }

    private fun ProviderSlot.matchingApiKey(apiKeys: List<ApiKey>): ApiKey? =
        when (credentialType) {
            SlotCredentialType.OAUTH -> {
                val managedProvider = canonicalManagedProvider(providerRegistryId) ?: return null
                apiKeys.firstOrNull { canonicalManagedProvider(it.provider) == managedProvider }
            }
            else ->
                apiKeys.firstOrNull { key ->
                    val keyProvider =
                        ProviderRegistry.findById(key.provider)?.id ?: key.provider.lowercase()
                    keyProvider == providerRegistryId.lowercase()
                }
        }

    private fun ProviderSlot.matchingAuthProfile(profiles: List<FfiAuthProfile>): FfiAuthProfile? =
        authProfileProvider?.let { provider ->
            profiles.firstOrNull { it.provider == provider }
        }

    private fun oauthFallbackLabel(slot: ProviderSlot): String =
        when (slot.slotId) {
            "chatgpt" -> "ChatGPT Connected"
            "claude-code" -> "Claude Code Connected"
            else -> "${slot.displayName} Connected"
        }

    private companion object {
        private const val ANTHROPIC_PROVIDER_ID = "anthropic"
    }
}
