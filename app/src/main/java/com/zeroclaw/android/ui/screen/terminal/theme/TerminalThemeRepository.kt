/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zeroclaw.android.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Extension property providing the singleton [DataStore] for terminal preferences. */
private val Context.terminalDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "terminal_settings",
)

/**
 * Loads bundled Ghostty-format theme files from Android raw resources and
 * persists the user's selected theme name in DataStore.
 *
 * Themes are parsed on first access and cached for the lifetime of the
 * repository instance. If a theme file fails to parse it is silently
 * omitted from the available list.
 *
 * @param context Application context used to open raw resources and
 *   initialize the [DataStore].
 */
class TerminalThemeRepository(
    private val context: Context,
) {
    private val themeResources: List<Pair<String, Int>> =
        listOf(
            "Default" to R.raw.theme_default,
            "Catppuccin Mocha" to R.raw.theme_catppuccin_mocha,
            "Catppuccin Latte" to R.raw.theme_catppuccin_latte,
            "Dracula" to R.raw.theme_dracula,
            "Tokyo Night" to R.raw.theme_tokyo_night,
            "Tokyo Night Light" to R.raw.theme_tokyo_night_light,
            "Solarized Dark" to R.raw.theme_solarized_dark,
            "Solarized Light" to R.raw.theme_solarized_light,
            "Gruvbox Dark" to R.raw.theme_gruvbox_dark,
            "Gruvbox Light" to R.raw.theme_gruvbox_light,
            "Nord" to R.raw.theme_nord,
            "One Dark" to R.raw.theme_one_dark,
            "Rosé Pine" to R.raw.theme_rose_pine,
            "Rosé Pine Dawn" to R.raw.theme_rose_pine_dawn,
            "Kanagawa" to R.raw.theme_kanagawa,
            "Monokai Pro" to R.raw.theme_monokai_pro,
            "Everforest Dark" to R.raw.theme_everforest_dark,
            "Everforest Light" to R.raw.theme_everforest_light,
        )

    private val cachedThemes: List<TerminalTheme> by lazy {
        themeResources.mapNotNull { (name, resId) ->
            val content =
                context.resources
                    .openRawResource(resId)
                    .bufferedReader()
                    .use { it.readText() }
            TerminalThemeParser.parse(name, content)
        }
    }

    /**
     * Returns all available terminal themes parsed from bundled raw resources.
     *
     * The list is computed once and cached. Themes that fail to parse are
     * excluded. The list is guaranteed to be non-empty because the Default
     * theme is always valid.
     */
    fun allThemes(): List<TerminalTheme> = cachedThemes

    /**
     * Looks up a [TerminalTheme] by [name], falling back to the first
     * available theme when no match is found.
     *
     * @param name Display name of the desired theme.
     * @return Matching theme, or the first theme in the list as a fallback.
     */
    fun themeByName(name: String): TerminalTheme =
        cachedThemes.firstOrNull { it.name == name }
            ?: cachedThemes.first()

    /**
     * Flow emitting the currently selected theme name, defaulting to
     * [DEFAULT_THEME_NAME] when no selection has been persisted.
     */
    val selectedThemeName: Flow<String> =
        context.terminalDataStore.data.map { prefs ->
            prefs[KEY_TERMINAL_THEME] ?: DEFAULT_THEME_NAME
        }

    /**
     * Persists [name] as the user's selected terminal theme.
     *
     * Safe to call from any coroutine context; DataStore handles
     * serialization internally.
     *
     * @param name Display name of the theme to persist.
     */
    suspend fun setSelectedTheme(name: String) {
        context.terminalDataStore.edit { prefs ->
            prefs[KEY_TERMINAL_THEME] = name
        }
    }

    /** Preference keys and default values for terminal settings. */
    companion object {
        private val KEY_TERMINAL_THEME = stringPreferencesKey("terminal_theme")

        /** Default theme name used when no selection has been persisted. */
        const val DEFAULT_THEME_NAME = "Default"
    }
}
