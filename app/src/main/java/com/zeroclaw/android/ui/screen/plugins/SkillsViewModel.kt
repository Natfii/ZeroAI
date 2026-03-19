/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.plugins

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.model.Skill
import com.zeroclaw.android.service.SkillsBridge
import com.zeroclaw.android.util.ErrorSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the skills tab.
 *
 * @param T The type of content data.
 */
sealed interface SkillsUiState<out T> {
    /** Data is being loaded from the bridge. */
    data object Loading : SkillsUiState<Nothing>

    /**
     * Loading or mutation failed.
     *
     * @property detail Human-readable error message.
     */
    data class Error(
        val detail: String,
    ) : SkillsUiState<Nothing>

    /**
     * Data loaded successfully.
     *
     * @param T Content data type.
     * @property data The loaded content.
     */
    data class Content<T>(
        val data: T,
    ) : SkillsUiState<T>
}

/**
 * ViewModel for the skills tab inside the Plugins and Skills screen.
 *
 * Loads skills from [SkillsBridge] and exposes install/remove operations.
 * Also manages the one-time install warning dialog via [SettingsRepository].
 *
 * @param application Application context for accessing [ZeroAIApplication.skillsBridge]
 *     and [ZeroAIApplication.settingsRepository].
 */
class SkillsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val skillsBridge: SkillsBridge =
        (application as ZeroAIApplication).skillsBridge

    private val settingsRepository: SettingsRepository =
        (application as ZeroAIApplication).settingsRepository

    private val _uiState =
        MutableStateFlow<SkillsUiState<List<Skill>>>(SkillsUiState.Loading)

    /** Observable UI state for the skills list. */
    val uiState: StateFlow<SkillsUiState<List<Skill>>> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    /** Current search query text. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)

    /**
     * One-shot snackbar message shown after a successful mutation.
     *
     * Collect with `collectAsStateWithLifecycle` and call [clearSnackbar]
     * after displaying.
     */
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    /**
     * Whether the one-time skill install warning dialog has been acknowledged.
     *
     * Defaults to `true` to prevent a flash of the dialog before the
     * DataStore value loads. The FAB click handler checks this before
     * navigating: when `false`, the warning dialog is shown first.
     */
    val skillInstallWarningSeen: StateFlow<Boolean> =
        settingsRepository
            .getSkillInstallWarningSeen()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = true,
            )

    /**
     * Pre-filtered UI state combining the raw skills list with the
     * current search query.
     *
     * Filtering runs in the ViewModel so the composable receives
     * an already-filtered list without recomputing on every recomposition.
     */
    val filteredUiState: StateFlow<SkillsUiState<List<Skill>>> =
        combine(_uiState, _searchQuery) { state, query ->
            if (state is SkillsUiState.Content) {
                SkillsUiState.Content(filterSkills(state.data, query))
            } else {
                state
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = SkillsUiState.Loading,
        )

    init {
        loadSkills()
    }

    /** Reloads the skills list from the native layer. */
    fun loadSkills() {
        _uiState.value = SkillsUiState.Loading
        viewModelScope.launch {
            loadSkillsInternal()
        }
    }

    /**
     * Silently refreshes the skills list without showing a loading state.
     *
     * Use this when returning from another screen (e.g. the skill builder)
     * to pick up newly saved skills without flashing a loading indicator.
     */
    fun refreshSkills() {
        viewModelScope.launch {
            loadSkillsInternal()
        }
    }

    /**
     * Updates the search query for filtering skills.
     *
     * @param query New search text.
     */
    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    /**
     * Installs a skill from the given source URL or path.
     *
     * @param source URL or local filesystem path to the skill.
     */
    fun installSkill(source: String) {
        viewModelScope.launch {
            runMutation("Skill installed") {
                skillsBridge.installSkill(source)
            }
        }
    }

    /**
     * Removes an installed skill by name.
     *
     * @param name The skill name to remove.
     */
    fun removeSkill(name: String) {
        viewModelScope.launch {
            runMutation("Skill removed") {
                skillsBridge.removeSkill(name)
            }
        }
    }

    /**
     * Toggles a community skill between enabled and disabled.
     *
     * @param name Skill name to toggle.
     * @param enabled Target enabled state.
     */
    fun toggleSkill(
        name: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            runMutation(if (enabled) "Skill enabled" else "Skill disabled") {
                skillsBridge.toggleCommunitySkill(name, enabled)
            }
        }
    }

    /** Clears the current snackbar message. */
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    /**
     * Persists that the skill install warning dialog has been acknowledged.
     *
     * Safe to call from the main thread; delegates to a coroutine on
     * [viewModelScope]. Cancellation exceptions are propagated normally.
     */
    fun markSkillInstallWarningSeen() {
        viewModelScope.launch {
            settingsRepository.setSkillInstallWarningSeen(true)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadSkillsInternal() {
        try {
            val skills = skillsBridge.listSkills()
            _uiState.value = SkillsUiState.Content(skills)
        } catch (e: Exception) {
            _uiState.value =
                SkillsUiState.Error(
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
            loadSkillsInternal()
        } catch (e: Exception) {
            _snackbarMessage.value = ErrorSanitizer.sanitizeForUi(e)
        }
    }

    /** Utility functions for skills filtering. */
    companion object {
        /**
         * Filters skills by search query against name and description.
         *
         * @param skills All skills from the bridge.
         * @param query Search query text.
         * @return Filtered list of skills.
         */
        private fun filterSkills(
            skills: List<Skill>,
            query: String,
        ): List<Skill> {
            val community = skills.filter { it.isCommunity }
            if (query.isBlank()) return community
            return community.filter { skill ->
                skill.name.contains(query, ignoreCase = true) ||
                    skill.description.contains(query, ignoreCase = true)
            }
        }
    }
}
