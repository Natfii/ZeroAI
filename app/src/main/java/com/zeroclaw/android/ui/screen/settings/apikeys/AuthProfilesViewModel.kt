/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings.apikeys

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.util.ErrorSanitizer
import com.zeroclaw.ffi.FfiAuthProfile
import com.zeroclaw.ffi.listAuthProfiles
import com.zeroclaw.ffi.removeAuthProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presentation model for a single auth profile displayed in the list.
 *
 * Maps from the raw FFI [FfiAuthProfile] record to a UI-friendly
 * representation with pre-formatted timestamp strings.
 *
 * @property id Profile ID in "provider:profile_name" format.
 * @property provider Provider name (e.g. "openai-codex", "gemini").
 * @property profileName Human-readable profile display name.
 * @property kind Profile kind label: "OAuth" or "Token".
 * @property isActive Whether this is the active profile for its provider.
 * @property expiryLabel Formatted expiry string, or null if no expiry.
 * @property createdLabel Formatted creation date string.
 * @property updatedLabel Formatted last-update date string.
 */
data class AuthProfileItem(
    val id: String,
    val provider: String,
    val profileName: String,
    val kind: String,
    val isActive: Boolean,
    val expiryLabel: String?,
    val createdLabel: String,
    val updatedLabel: String,
)

/**
 * UI state for the auth profiles screen.
 *
 * @param T The type of content data.
 */
sealed interface AuthProfilesUiState<out T> {
    /** Data is being loaded from the FFI layer. */
    data object Loading : AuthProfilesUiState<Nothing>

    /**
     * Loading or mutation failed.
     *
     * @property detail Human-readable error message.
     */
    data class Error(
        val detail: String,
    ) : AuthProfilesUiState<Nothing>

    /**
     * Data loaded successfully.
     *
     * @param T Content data type.
     * @property data The loaded content.
     */
    data class Content<T>(
        val data: T,
    ) : AuthProfilesUiState<T>
}

/**
 * ViewModel for the auth profiles management screen.
 *
 * Loads OAuth and token profiles from the FFI layer and exposes
 * list and delete operations. Profiles are mapped to [AuthProfileItem]
 * with formatted timestamps for display.
 *
 * @param application Application context used by [AndroidViewModel].
 */
class AuthProfilesViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState =
        MutableStateFlow<AuthProfilesUiState<List<AuthProfileItem>>>(
            AuthProfilesUiState.Loading,
        )

    /** Observable UI state for the auth profiles list. */
    val uiState: StateFlow<AuthProfilesUiState<List<AuthProfileItem>>> =
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
        loadProfiles()
    }

    /** Reloads the auth profiles list from the native layer. */
    fun loadProfiles() {
        _uiState.value = AuthProfilesUiState.Loading
        viewModelScope.launch {
            loadProfilesInternal()
        }
    }

    /**
     * Removes an auth profile by provider and profile name.
     *
     * After successful removal, refreshes the profile list and
     * shows a snackbar confirmation.
     *
     * @param provider Provider name of the profile to remove.
     * @param profileName Display name of the profile to remove.
     */
    fun removeProfile(
        provider: String,
        profileName: String,
    ) {
        viewModelScope.launch {
            runMutation("Profile removed") {
                withContext(Dispatchers.IO) {
                    removeAuthProfile(provider, profileName)
                }
            }
        }
    }

    /** Clears the current snackbar message. */
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadProfilesInternal() {
        try {
            val ffiProfiles =
                withContext(Dispatchers.IO) {
                    listAuthProfiles()
                }
            val items = ffiProfiles.map { it.toItem() }
            _uiState.value = AuthProfilesUiState.Content(items)
        } catch (e: Exception) {
            _uiState.value =
                AuthProfilesUiState.Error(
                    ErrorSanitizer.sanitizeForUi(e),
                )
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
            loadProfilesInternal()
        } catch (e: Exception) {
            _snackbarMessage.value = ErrorSanitizer.sanitizeForUi(e)
        }
    }

    /** Utility functions for mapping FFI profiles to presentation models. */
    companion object {
        /** Date format pattern for displaying profile timestamps. */
        private const val DATE_FORMAT_PATTERN = "MMM d, yyyy HH:mm"

        /**
         * Formats an epoch-millisecond timestamp as a short date string.
         *
         * @param epochMs Epoch milliseconds to format.
         * @return Formatted date string.
         */
        internal fun formatTimestamp(epochMs: Long): String {
            val formatter = SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.getDefault())
            return formatter.format(Date(epochMs))
        }

        /**
         * Maps an [FfiAuthProfile] to an [AuthProfileItem] for display.
         *
         * @receiver The raw FFI auth profile record.
         * @return Presentation model with formatted timestamps.
         */
        internal fun FfiAuthProfile.toItem(): AuthProfileItem =
            AuthProfileItem(
                id = id,
                provider = provider,
                profileName = profileName,
                kind =
                    when (kind.lowercase()) {
                        "oauth" -> "OAuth"
                        "token" -> "Token"
                        else -> kind
                    },
                isActive = isActive,
                expiryLabel = expiresAtMs?.let { formatTimestamp(it) },
                createdLabel = formatTimestamp(createdAtMs),
                updatedLabel = formatTimestamp(updatedAtMs),
            )
    }
}
