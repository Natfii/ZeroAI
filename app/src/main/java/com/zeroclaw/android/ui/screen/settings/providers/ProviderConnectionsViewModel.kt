/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.providers

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.oauth.ProviderConnectionCoordinator
import com.zeroclaw.android.data.oauth.ProviderConnectionSnapshot
import com.zeroclaw.android.util.ErrorSanitizer
import com.zeroclaw.ffi.FfiAuthProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Summary of a connected OAuth profile shown within a [ProviderConnectionItem].
 *
 * @property kind Profile kind label: "OAuth" or "Token".
 * @property accountLabel Account email or identifier when available.
 * @property detailLabel Capability or connection summary shown under the account label.
 * @property expiryLabel Formatted expiry string, or null if the token does not expire.
 */
data class ConnectedProfileInfo(
    val kind: String,
    val accountLabel: String?,
    val detailLabel: String?,
    val expiryLabel: String?,
)

/**
 * Connection status for a single OAuth-capable provider.
 *
 * @property providerId Canonical provider ID (e.g. `"anthropic"`).
 * @property displayName Human-readable provider name (e.g. `"Anthropic"`).
 * @property authProfileProvider Provider name stored in the Rust auth-profile store.
 * @property connectedProfile Active profile info, or null when not connected.
 * @property oauthInProgress True while an OAuth flow is running for this provider.
 */
data class ProviderConnectionItem(
    val providerId: String,
    val displayName: String,
    val authProfileProvider: String,
    val connectedProfile: ConnectedProfileInfo?,
    val oauthInProgress: Boolean,
)

/** UI state for the provider logins screen. */
sealed interface ProviderConnectionsUiState {
    /** Provider list is being loaded from the standalone Rust auth-profile store. */
    data object Loading : ProviderConnectionsUiState

    /**
     * Loading failed.
     *
     * @property detail Human-readable error message.
     */
    data class Error(
        val detail: String,
    ) : ProviderConnectionsUiState

    /**
     * Provider list loaded successfully.
     *
     * @property providers All OAuth-capable providers with their connection state.
     */
    data class Content(
        val providers: List<ProviderConnectionItem>,
    ) : ProviderConnectionsUiState
}

/**
 * ViewModel for the provider logins screen.
 *
 * Manages the list of OAuth-capable provider sessions (Anthropic, OpenAI, and
 * the shared Google account connection). Reads existing profiles from the
 * Rust-owned encrypted store without requiring the daemon to be running.
 *
 * @param application Application context used by [AndroidViewModel].
 */
class ProviderConnectionsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val coordinator = ProviderConnectionCoordinator(application as ZeroAIApplication)

    private val _uiState =
        MutableStateFlow<ProviderConnectionsUiState>(ProviderConnectionsUiState.Loading)

    /** Observable UI state for the provider logins list. */
    val uiState: StateFlow<ProviderConnectionsUiState> = _uiState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)

    /**
     * One-shot snackbar message shown after a successful or failed action.
     *
     * Collect with `collectAsStateWithLifecycle` and call [clearSnackbar] after displaying.
     */
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    @Volatile
    private var cachedSnapshots: List<ProviderConnectionSnapshot> = emptyList()
    private val oauthInProgressIds = MutableStateFlow<Set<String>>(emptySet())

    private val _anthropicSheetVisible = MutableStateFlow(false)

    /** Whether the Anthropic code paste-back sheet is visible. */
    val anthropicSheetVisible: StateFlow<Boolean> = _anthropicSheetVisible.asStateFlow()

    private val _anthropicSheetLoading = MutableStateFlow(false)

    /** Whether the Anthropic code exchange is in progress. */
    val anthropicSheetLoading: StateFlow<Boolean> = _anthropicSheetLoading.asStateFlow()

    private val _anthropicSheetError = MutableStateFlow<String?>(null)

    /** Error message to display in the Anthropic paste-back sheet. */
    val anthropicSheetError: StateFlow<String?> = _anthropicSheetError.asStateFlow()

    /** Stored PKCE state for the in-flight Anthropic OAuth flow. */
    private var anthropicPkce: com.zeroclaw.android.data.oauth.PkceState? = null

    init {
        loadConnections()
    }

    /** Reloads provider connection status from the native layer. */
    fun loadConnections() {
        _uiState.value = ProviderConnectionsUiState.Loading
        viewModelScope.launch { loadConnectionsInternal() }
    }

    /**
     * Launches the OAuth login flow for the given provider.
     *
     * @param context Activity context used to launch the Chrome Custom Tab or helper activity.
     * @param providerId Canonical provider ID of the provider to connect.
     */
    fun connectProvider(
        context: Context,
        providerId: String,
    ) {
        viewModelScope.launch { startOAuthForProvider(context, providerId) }
    }

    /**
     * Removes the stored auth profile for the given provider.
     *
     * @param providerId Canonical provider ID of the provider to disconnect.
     */
    fun disconnectProvider(providerId: String) {
        viewModelScope.launch { runDisconnect(providerId) }
    }

    /** Clears the current snackbar message. */
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadConnectionsInternal() {
        try {
            cachedSnapshots = coordinator.loadSnapshots(oauthInProgressIds.value)
            _uiState.value = buildContent()
        } catch (e: Exception) {
            _uiState.value =
                ProviderConnectionsUiState.Error(
                    ErrorSanitizer.sanitizeForUi(e),
                )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runDisconnect(providerId: String) {
        try {
            coordinator.disconnectProvider(providerId)
            _snackbarMessage.value = "Disconnected"
            loadConnectionsInternal()
        } catch (e: Exception) {
            _snackbarMessage.value = ErrorSanitizer.sanitizeForUi(e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startOAuthForProvider(
        context: Context,
        providerId: String,
    ) {
        if (providerId == ANTHROPIC_ID) {
            setOAuthInProgress(providerId, true)
            anthropicPkce = coordinator.startAnthropicFlow(context)
            _anthropicSheetVisible.value = true
            return
        }
        setOAuthInProgress(providerId, true)
        try {
            coordinator.connectProvider(context, providerId)
            _snackbarMessage.value = "Connected"
            loadConnectionsInternal()
        } catch (e: Exception) {
            _snackbarMessage.value = ErrorSanitizer.sanitizeForUi(e)
        } finally {
            setOAuthInProgress(providerId, false)
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
                _snackbarMessage.value = "Claude Code connected"
                dismissAnthropicSheet()
                loadConnectionsInternal()
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

    /**
     * Dismisses the Anthropic paste-back sheet and clears related state.
     */
    fun dismissAnthropicSheet() {
        _anthropicSheetVisible.value = false
        _anthropicSheetLoading.value = false
        _anthropicSheetError.value = null
        anthropicPkce = null
        setOAuthInProgress(ANTHROPIC_ID, false)
    }

    private fun setOAuthInProgress(
        providerId: String,
        inProgress: Boolean,
    ) {
        oauthInProgressIds.value =
            if (inProgress) {
                oauthInProgressIds.value + providerId
            } else {
                oauthInProgressIds.value - providerId
            }
        cachedSnapshots =
            cachedSnapshots.map { snapshot ->
                if (snapshot.providerId == providerId) {
                    snapshot.copy(oauthInProgress = inProgress)
                } else {
                    snapshot
                }
            }
        if (_uiState.value is ProviderConnectionsUiState.Content) {
            _uiState.value = buildContent()
        }
    }

    private fun buildContent(): ProviderConnectionsUiState.Content =
        ProviderConnectionsUiState.Content(
            providers =
                cachedSnapshots.map { snapshot ->
                    ProviderConnectionItem(
                        providerId = snapshot.providerId,
                        displayName = snapshot.displayName,
                        authProfileProvider = snapshot.authProfileProvider,
                        connectedProfile = snapshot.profile?.toConnectedInfo(),
                        oauthInProgress = snapshot.oauthInProgress,
                    )
                },
        )

    /** Shared utilities for mapping FFI types to presentation models. */
    companion object {
        private const val ANTHROPIC_ID = "anthropic"

        private fun FfiAuthProfile.toConnectedInfo(): ConnectedProfileInfo =
            ConnectedProfileInfo(
                kind =
                    when (kind.lowercase()) {
                        "oauth" -> "OAuth"
                        "token" -> "Token"
                        else -> kind
                    },
                accountLabel = accountId?.takeIf { it.isNotBlank() },
                detailLabel = null,
                expiryLabel = expiresAtMs?.let { formatEpochMs(it) },
            )

        private fun formatEpochMs(epochMs: Long): String {
            val formatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
            return formatter.format(Date(epochMs))
        }
    }
}
