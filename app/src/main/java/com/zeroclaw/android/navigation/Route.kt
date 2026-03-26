/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe route definitions for the application navigation graph.
 *
 * Each route is a [Serializable] object or data class that the Navigation
 * Compose library uses for type-safe argument passing between destinations.
 *
 * Dashboard home screen showing daemon status overview.
 */
@Serializable
data object DashboardRoute

/** Agent list and management screen. */
@Serializable
data object AgentsRoute

/** Fixed provider-slot detail screen. */
@Serializable
data class ProviderSlotDetailRoute(
    /** Stable provider-slot identifier to display. */
    val slotId: String,
)

/** Plugin list and management screen. */
@Serializable
data object PluginsRoute

/** Plugin detail screen. */
@Serializable
data class PluginDetailRoute(
    /** Unique identifier of the plugin to display. */
    val pluginId: String,
)

/**
 * Skill builder screen for creating or editing community skills.
 *
 * @property skillName Name of the skill to edit, or null for creating a new skill.
 */
@Serializable
data class SkillBuilderRoute(
    val skillName: String? = null,
)

/** Root settings screen. */
@Serializable
data object SettingsRoute

/** Service configuration sub-screen. */
@Serializable
data object ServiceConfigRoute

/** Battery settings sub-screen. */
@Serializable
data object BatterySettingsRoute

/** About information sub-screen. */
@Serializable
data object AboutRoute

/** API key management sub-screen. */
@Serializable
data object ApiKeysRoute

/**
 * API key detail sub-screen.
 *
 * @property keyId Identifier of the key to edit, or null for adding a new key.
 * @property providerId Optional provider ID to preselect when creating a new key.
 */
@Serializable
data class ApiKeyDetailRoute(
    val keyId: String? = null,
    val providerId: String? = null,
)

/** Log viewer sub-screen. */
@Serializable
data object LogViewerRoute

/** Connected channels management sub-screen. */
@Serializable
data object ConnectedChannelsRoute

/**
 * Channel detail sub-screen.
 *
 * @property channelId Identifier of the channel to edit, or null for adding a new channel.
 * @property channelType Channel type name for new channel creation (used when channelId is null).
 */
@Serializable
data class ChannelDetailRoute(
    val channelId: String? = null,
    val channelType: String? = null,
)

/** Interactive terminal REPL screen. */
@Serializable
data object TerminalRoute

/** ZeroAI Doctor diagnostics screen. */
@Serializable
data object DoctorRoute

/** Autonomy level and security policy screen. */
@Serializable
data object AutonomyRoute

/** Memory advanced configuration screen. */
@Serializable
data object MemoryAdvancedRoute

/** Scheduler and heartbeat configuration screen. */
@Serializable
data object SchedulerRoute

/** QR code scanner screen for gateway pairing. */
@Serializable
data object QrScannerRoute

/** Cost tracking detail screen. */
@Serializable
data object CostDetailRoute

/** Scheduled cron jobs management screen. */
@Serializable
data object CronJobsRoute

/** Memory entries browser screen. */
@Serializable
data object MemoryBrowserRoute

/** First-run onboarding wizard. */
@Serializable
data object OnboardingRoute

/** Auth profiles management sub-screen. */
@Serializable
data object AuthProfilesRoute

/** Discord archive channels management sub-screen. */
@Serializable
data object DiscordChannelsRoute

/**
 * Discord archive channel detail sub-screen.
 *
 * @property channelId Discord channel snowflake ID to display.
 */
@Serializable
data class DiscordChannelDetailRoute(
    val channelId: String,
)

/** Provider connections management sub-screen (OAuth and CLI-backed sessions). */
@Serializable
data object ProviderConnectionsRoute

/** Web dashboard sub-screen (WebView pointing at the gateway SPA). */
@Serializable
data object WebDashboardRoute

/** Configuration screen for the Twitter/X browse tool. */
@Serializable
data object TwitterConfigRoute

/** ClawBoy Game Boy emulator configuration screen. */
@Serializable
data object ClawBoyConfigRoute

/** Email integration configuration screen. */
@Serializable
data object EmailConfigRoute

/** Post-onboarding daemon setup and channel initialization screen. */
@Serializable
data object SetupRoute

/** Google Messages pairing and allowlist configuration screen. */
@Serializable
data object GoogleMessagesRoute

/** Tailscale tailnet service discovery configuration screen. */
@Serializable
data object TailscaleConfigRoute

/** Skill permissions (capability grants) management sub-screen. */
@Serializable
data object SkillPermissionsRoute

/** SSH key management sub-screen. */
@Serializable
data object SshKeysRoute
