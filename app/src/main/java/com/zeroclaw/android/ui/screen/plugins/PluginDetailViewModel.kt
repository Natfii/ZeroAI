/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.model.Plugin
import com.zeroclaw.android.model.SearchEngineHealth
import com.zeroclaw.ffi.FfiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the plugin detail screen.
 *
 * Derives plugin state reactively from [PluginRepository.observeById] so that
 * database changes from any source (user actions, plugin sync, etc.) are
 * automatically reflected without redundant one-shot `getById` calls or
 * manual in-memory state patches.
 *
 * @param application Application context for accessing the plugin repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginDetailViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val repository = app.pluginRepository
    private val settingsRepository = app.settingsRepository
    private val daemonBridge = app.daemonBridge
    private val healthBridge = app.healthBridge

    private val pluginId = MutableStateFlow<String?>(null)

    private val _searchEngineHealth = MutableStateFlow<List<SearchEngineHealth>>(emptyList())

    /**
     * Per-engine health rows for the on-device meta search backend.
     *
     * Loaded once on entry when the observed plugin is the official web
     * search plugin; empty for every other plugin and when the native
     * call fails, in which case the UI hides the engine-status section.
     */
    val searchEngineHealth: StateFlow<List<SearchEngineHealth>> = _searchEngineHealth.asStateFlow()

    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * One-shot event emitted after an uninstall completes, signalling the
     * screen to navigate back.
     */
    val navigateBack: SharedFlow<Unit> = _navigateBack.asSharedFlow()

    /**
     * The currently observed plugin, or null if no plugin ID has been set
     * or the plugin does not exist.
     *
     * Derived from [PluginRepository.observeById] and updated automatically
     * whenever the underlying database row changes.
     */
    val plugin: StateFlow<Plugin?> =
        pluginId
            .flatMapLatest { id ->
                if (id != null) repository.observeById(id) else flowOf(null)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * Sets the plugin ID to observe.
     *
     * Triggers the reactive [plugin] Flow to start emitting updates for
     * the specified plugin. Subsequent calls with the same ID are no-ops
     * since [MutableStateFlow] deduplicates identical values.
     *
     * @param pluginId Unique identifier of the plugin to observe.
     */
    fun loadPlugin(pluginId: String) {
        this.pluginId.value = pluginId
        if (pluginId == OfficialPlugins.WEB_SEARCH) {
            loadSearchEngineHealth()
        }
    }

    /**
     * Loads meta search engine health rows into [searchEngineHealth].
     *
     * Failures clear the rows so the detail screen hides the section
     * instead of surfacing an error state.
     */
    private fun loadSearchEngineHealth() {
        viewModelScope.launch {
            _searchEngineHealth.value =
                try {
                    healthBridge.getSearchEngineHealth()
                } catch (_: FfiException) {
                    emptyList()
                }
        }
    }

    /**
     * Installs the plugin with the given [pluginId].
     *
     * The UI updates automatically via the reactive [plugin] Flow after the
     * database write completes.
     *
     * @param pluginId Unique identifier of the plugin.
     */
    fun install(pluginId: String) {
        viewModelScope.launch {
            repository.install(pluginId)
        }
    }

    /**
     * Uninstalls the plugin with the given [pluginId].
     *
     * The UI updates automatically via the reactive [plugin] Flow after the
     * database write completes.
     *
     * @param pluginId Unique identifier of the plugin.
     */
    fun uninstall(pluginId: String) {
        viewModelScope.launch {
            repository.uninstall(pluginId)
            _navigateBack.tryEmit(Unit)
        }
    }

    /**
     * Toggles the enabled state of the plugin with the given [pluginId].
     *
     * The UI updates automatically via the reactive [plugin] Flow after the
     * database write completes.
     *
     * @param pluginId Unique identifier of the plugin.
     */
    fun toggleEnabled(pluginId: String) {
        viewModelScope.launch {
            repository.toggleEnabled(pluginId)
            syncOfficialPluginSetting(pluginId)
            daemonBridge.markRestartRequired()
        }
    }

    /**
     * Syncs the enabled state of an official plugin to [AppSettings][com.zeroclaw.android.model.AppSettings].
     *
     * The Room `isEnabled` flag drives the Plugins UI, but the daemon
     * config is generated from [AppSettings][com.zeroclaw.android.model.AppSettings].
     * Without this sync, toggling an official plugin in the detail screen
     * has no effect on the TOML config that the daemon reads at startup.
     */
    private suspend fun syncOfficialPluginSetting(pluginId: String) {
        val newState = repository.getById(pluginId)?.isEnabled ?: return
        OfficialPluginSettingsSync.syncPluginEnabledState(settingsRepository, pluginId, newState)
    }

    /**
     * Updates a configuration value for the plugin.
     *
     * The UI updates automatically via the reactive [plugin] Flow after the
     * database write completes.
     *
     * @param pluginId Unique plugin identifier.
     * @param key Configuration field key.
     * @param value New value for the field.
     */
    fun updateConfig(
        pluginId: String,
        key: String,
        value: String,
    ) {
        viewModelScope.launch {
            repository.updateConfig(pluginId, key, value)
        }
    }

    /** Constants for [PluginDetailViewModel]. */
    companion object {
        /** Upstream subscription timeout for [SharingStarted.WhileSubscribed]. */
        private const val STOP_TIMEOUT_MS = 5000L
    }
}
