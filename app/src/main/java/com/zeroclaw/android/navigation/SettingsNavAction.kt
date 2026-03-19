/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.navigation

/**
 * Sealed interface representing navigation actions from the settings root screen.
 *
 * Consolidates individual navigation callbacks into a single typed action,
 * reducing the [SettingsScreen][com.zeroclaw.android.ui.screen.settings.SettingsScreen]
 * parameter count from 22 lambdas to one.
 */
sealed interface SettingsNavAction {
    /** Navigate to the service configuration screen. */
    data object ServiceConfig : SettingsNavAction

    /** Navigate to the battery settings screen. */
    data object Battery : SettingsNavAction

    /** Navigate to the API keys management screen. */
    data object ApiKeys : SettingsNavAction

    /** Navigate to the chat apps screen. */
    data object Channels : SettingsNavAction

    /** Navigate to the log viewer screen. */
    data object LogViewer : SettingsNavAction

    /** Navigate to the ZeroAI Doctor screen. */
    data object Doctor : SettingsNavAction

    /** Navigate to the about screen. */
    data object About : SettingsNavAction

    /** Navigate to the autonomy settings screen. */
    data object Autonomy : SettingsNavAction

    /** Navigate to the memory advanced settings screen. */
    data object MemoryAdvanced : SettingsNavAction

    /** Navigate to the scheduler and heartbeat screen. */
    data object Scheduler : SettingsNavAction

    /** Navigate to the scheduled tasks (cron jobs) screen. */
    data object CronJobs : SettingsNavAction

    /** Navigate to the memory browser screen. */
    data object MemoryBrowser : SettingsNavAction

    /** Navigate to the auth profiles management screen. */
    data object AuthProfiles : SettingsNavAction

    /** Navigate to the Discord archive channels management screen. */
    data object DiscordChannels : SettingsNavAction

    /**
     * Navigate to the provider login screen.
     *
     * Displays OAuth-backed provider sessions (Claude Code, ChatGPT)
     * separately from manual API keys and chat apps.
     */
    data object ProviderConnections : SettingsNavAction

    /** Opens the web dashboard (full engine config in a WebView). */
    data object WebDashboard : SettingsNavAction
}
