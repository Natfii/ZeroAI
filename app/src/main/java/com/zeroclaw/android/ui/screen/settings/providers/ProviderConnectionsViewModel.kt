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
 * Manages the list of OAuth-capable provider sessions surfaced here, which is
 * currently OpenAI/ChatGPT only (the provider list is driven by the
 * coordinator's allow-list). Anthropic OAuth is offered from the per-agent
 * provider-slot surface, not this screen. Reads existing profiles from the
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
