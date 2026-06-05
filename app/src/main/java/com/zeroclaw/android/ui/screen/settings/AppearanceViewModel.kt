/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.LogSeverity
import com.zeroclaw.android.model.ThemeMode
import com.zeroclaw.android.ui.screen.terminal.theme.TerminalTheme
import com.zeroclaw.android.ui.screen.terminal.theme.TerminalThemeRepository
import com.zeroclaw.ffi.ttySetPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Subscription keep-alive window for collected flows. */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * ViewModel backing the unified appearance screen.
 *
 * Bridges two independent, pre-existing stores without introducing a new one:
 * the app [ThemeMode] (light/dark/system) in the main settings DataStore, and
 * the terminal/shell color palette in the terminal-settings DataStore via
 * [TerminalThemeRepository]. The single terminal palette drives both the GPU
 * terminal surface and the in-app REPL/shell colors.
 *
 * @param application Application context for resolving the settings and terminal repositories.
 */
class AppearanceViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val settingsRepository = (application as ZeroAIApplication).settingsRepository
    private val logRepository = (application as ZeroAIApplication).logRepository
    private val themeRepository = TerminalThemeRepository(application)

    /** Current app [ThemeMode], collected as state. */
    val theme: StateFlow<ThemeMode> =
        settingsRepository.settings
            .map { it.theme }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = ThemeMode.SYSTEM,
            )

    /** Name of the currently selected terminal/shell palette, collected as state. */
    val terminalThemeName: StateFlow<String> =
        themeRepository.selectedThemeName
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = TerminalThemeRepository.DEFAULT_THEME_NAME,
            )

    /**
     * Returns all bundled terminal/shell palettes in display order.
     *
     * @return Non-empty list of available [TerminalTheme] values.
     */
    fun terminalThemes(): List<TerminalTheme> = themeRepository.allThemes()

    /**
     * Persists the chosen app [ThemeMode].
     *
     * Safe to call from the main thread; the write is dispatched on [viewModelScope].
     *
     * @param mode The theme mode to apply.
     */
    fun updateTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setTheme(mode) }
    }

    /**
     * Applies and persists the chosen terminal/shell palette.
     *
     * Repaints any live terminal session immediately via [ttySetPalette] and
     * persists the selection so the terminal tab and this screen stay in sync
     * through the shared terminal-settings DataStore.
     *
     * @param theme The palette to apply.
     */
    @Suppress("TooGenericExceptionCaught")
    fun selectTerminalTheme(theme: TerminalTheme) {
        try {
            ttySetPalette(
                theme.bgArgb,
                theme.fgArgb,
                theme.cursorArgb ?: theme.fgArgb,
                theme.palette,
            )
        } catch (e: Exception) {
            logRepository.append(
                LogSeverity.WARN,
                TAG,
                "Failed to apply terminal palette '${theme.name}': ${e.message}",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            themeRepository.setSelectedTheme(theme.name)
        }
    }

    /** Logging tag for appearance changes. */
    private companion object {
        private const val TAG = "Appearance"
    }
}
