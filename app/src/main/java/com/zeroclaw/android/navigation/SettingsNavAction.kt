/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.navigation

/**
 * Sealed interface representing navigation actions from the settings root screen.
 *
 * Consolidates the per-setting navigation callbacks into a single typed
 * [SettingsScreen][com.zeroclaw.android.ui.screen.settings.SettingsScreen]
 * `onNavigate` action.
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

    /** Navigate to the scheduled tasks (cron jobs) screen, including scheduler and heartbeat config. */
    data object CronJobs : SettingsNavAction

    /** Navigate to the memory browser screen. */
    data object MemoryBrowser : SettingsNavAction

    /**
     * Navigate to the provider login screen.
     *
     * Displays OAuth-backed provider sessions (Claude Code, ChatGPT)
     * separately from manual API keys and chat apps. Also surfaces the raw
     * stored auth profiles, so the standalone advanced view is no longer needed.
     */
    data object ProviderConnections : SettingsNavAction

    /** Navigate to the skill permissions (capability grants) screen. */
    data object SkillPermissions : SettingsNavAction

    /** Navigate to the SSH key management screen. */
    data object SshKeys : SettingsNavAction

    /** Navigate to the unified appearance screen (app theme + terminal/shell palette). */
    data object Appearance : SettingsNavAction
}
