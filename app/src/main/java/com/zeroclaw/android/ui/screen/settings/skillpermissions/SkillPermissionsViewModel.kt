// Copyright 2026 @Natfii, MIT License

package com.zeroclaw.android.ui.screen.settings.skillpermissions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.service.CapabilityGrantsBridge
import com.zeroclaw.android.util.ErrorSanitizer
import com.zeroclaw.ffi.CapabilityGrantInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the skill permissions screen.
 *
 * @param T The type of content data.
 */
sealed interface SkillPermissionsUiState<out T> {
    /** Data is being loaded from the bridge. */
    data object Loading : SkillPermissionsUiState<Nothing>

    /**
     * Loading or mutation failed.
     *
     * @property detail Human-readable error message.
     */
    data class Error(
        val detail: String,
    ) : SkillPermissionsUiState<Nothing>

    /**
     * Data loaded successfully.
     *
     * @param T Content data type.
     * @property data The loaded content.
     */
    data class Content<T>(
        val data: T,
    ) : SkillPermissionsUiState<T>
}

/**
 * ViewModel for the skill permissions settings screen.
 *
 * Loads all persisted capability grants from [CapabilityGrantsBridge] and
 * exposes a revoke operation that refreshes the list upon completion.
 *
 * @param application Application context for accessing [ZeroAIApplication.capabilityGrantsBridge].
 */
class SkillPermissionsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val capabilityGrantsBridge: CapabilityGrantsBridge =
        (application as ZeroAIApplication).capabilityGrantsBridge

    private val _uiState =
        MutableStateFlow<SkillPermissionsUiState<List<CapabilityGrantInfo>>>(
            SkillPermissionsUiState.Loading,
        )

    /** Observable UI state for the capability grants list. */
    val uiState: StateFlow<SkillPermissionsUiState<List<CapabilityGrantInfo>>> =
        _uiState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)

    /**
     * One-shot snackbar message shown after a successful mutation.
     *
     * Collect with `collectAsStateWithLifecycle` and call [clearSnackbar]
     * after displaying.
     */
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        loadGrants()
    }

    /** Reloads the capability grants list from the native layer. */
    fun loadGrants() {
        _uiState.value = SkillPermissionsUiState.Loading
        viewModelScope.launch {
            loadGrantsInternal()
        }
    }

    /**
     * Revokes a single persisted capability grant for the given skill.
     *
     * Triggers a list reload after the revocation completes regardless of
     * whether the operation succeeded, so the UI always reflects current state.
     *
     * @param skillName Name of the skill whose grant is being revoked.
     * @param capability The capability string to revoke (e.g. `"tools.call"`).
     */
    fun revokeGrant(
        skillName: String,
        capability: String,
    ) {
        viewModelScope.launch {
            runMutation("Grant revoked") {
                capabilityGrantsBridge.revokeGrant(skillName, capability)
            }
        }
    }

    /** Clears the current snackbar message. */
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadGrantsInternal() {
        try {
            val grants = capabilityGrantsBridge.listGrants()
            _uiState.value = SkillPermissionsUiState.Content(grants)
        } catch (e: Exception) {
            _uiState.value =
                SkillPermissionsUiState.Error(ErrorSanitizer.sanitizeForUi(e))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runMutation(
        successMessage: String,
        block: suspend () -> Any?,
    ) {
        try {
            block()
            _snackbarMessage.value = successMessage
            loadGrantsInternal()
        } catch (e: Exception) {
            _snackbarMessage.value = ErrorSanitizer.sanitizeForUi(e)
        }
    }
}
