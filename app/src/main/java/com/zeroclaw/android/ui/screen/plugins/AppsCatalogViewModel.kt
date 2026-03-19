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
import com.zeroclaw.android.data.email.EmailConfigState
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Catalog item shown in the Hub Apps tab.
 *
 * @property id Stable app identifier.
 * @property category Whether this row is a chat app.
 * @property title User-facing app name.
 * @property description Short capability summary.
 * @property statusLabel Current connection or enablement summary.
 * @property actionLabel Primary CTA label.
 * @property destination Target destination kind for taps.
 * @property destinationKey Optional destination argument such as a channel ID.
 * @property externalAction Optional action for [AppCatalogDestination.EXTERNAL] items.
 */
data class AppCatalogItem(
    val id: String,
    val category: AppCatalogCategory,
    val title: String,
    val description: String,
    val statusLabel: String,
    val actionLabel: String,
    val destination: AppCatalogDestination,
    val destinationKey: String? = null,
    val externalAction: (() -> Unit)? = null,
)

/** Visual section grouping for Hub apps. */
enum class AppCatalogCategory {
    /** Read-only web service integrations (rendered first). */
    WEB,

    /** Conversation endpoints where Zero can chat remotely. */
    CHAT_APP,
}

/** Target route group for one [AppCatalogItem]. */
enum class AppCatalogDestination {
    /** Opens a channel detail screen. */
    CHANNEL,

    /** Opens a tool-specific configuration screen. */
    TOOL_CONFIG,

    /** Opens an external app or URL (Play Store, third-party app launch). */
    EXTERNAL,
}

/**
 * ViewModel backing the Hub Apps catalog.
 *
 * Lists configured Telegram and Discord chat app connections.
 */
class AppsCatalogViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val settingsRepository = app.settingsRepository
    private val emailConfigRepository = app.emailConfigRepository

    /** Ordered app catalog for the Hub Apps tab. */
    val items: StateFlow<List<AppCatalogItem>> =
        combine(
            app.channelConfigRepository.channels,
            settingsRepository.settings,
            emailConfigRepository.observe(),
        ) { channels, settings, emailConfig ->
            buildList {
                add(twitterItem(settings))
                add(clawBoyItem())
                add(emailItem(emailConfig))
                add(googleMessagesItem())
                add(tailscaleItem(settings))
                ChannelType.entries.forEach { type ->
                    add(channelItem(type, channels))
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /** Twitter browse tool catalog item, always shown at the top of the WEB category. */
    private fun twitterItem(settings: AppSettings): AppCatalogItem {
        val hasCookie = settings.twitterBrowseCookieString.isNotBlank()
        val isEnabled = settings.twitterBrowseEnabled
        return AppCatalogItem(
            id = "twitter_browse",
            category = AppCatalogCategory.WEB,
            title = "X / Twitter",
            description = "Let your AI companion browse X/Twitter in read-only mode",
            statusLabel =
                when {
                    !hasCookie -> "Not connected yet"
                    isEnabled -> "Read-only browsing active"
                    else -> "Connected but disabled"
                },
            actionLabel = if (hasCookie) "Manage X" else "Connect X",
            destination = AppCatalogDestination.TOOL_CONFIG,
            destinationKey = "twitter_browse",
        )
    }

    /**
     * ClawBoy emulator catalog item, shown in the WEB category.
     *
     * Queries the current FFI session status to display a live label.
     */
    private fun clawBoyItem(): AppCatalogItem {
        val status =
            try {
                com.zeroclaw.ffi.clawboyGetStatus()
            } catch (_: Exception) {
                null
            }
        val statusLabel =
            when (status) {
                is com.zeroclaw.ffi.ClawBoyStatus.Playing -> "Playing now"
                is com.zeroclaw.ffi.ClawBoyStatus.Paused -> "Paused"
                is com.zeroclaw.ffi.ClawBoyStatus.Error -> "Error"
                else -> "Ready"
            }
        return AppCatalogItem(
            id = "clawboy",
            category = AppCatalogCategory.WEB,
            title = "ClawBoy",
            description = "Watch your AI agent play Pokemon Red",
            statusLabel = statusLabel,
            actionLabel = "Configure",
            destination = AppCatalogDestination.TOOL_CONFIG,
            destinationKey = "clawboy",
        )
    }

    /**
     * Email integration catalog item.
     *
     * Shows current configuration status based on whether the address
     * is configured and whether the integration is enabled.
     */
    private fun emailItem(emailConfig: EmailConfigState): AppCatalogItem {
        val hasAddress = emailConfig.address.isNotBlank()
        return AppCatalogItem(
            id = "email",
            category = AppCatalogCategory.WEB,
            title = "Email",
            description = "Let your AI agent read and send email",
            statusLabel =
                when {
                    !hasAddress -> "Not configured yet"
                    emailConfig.isEnabled -> "Active"
                    else -> "Configured but disabled"
                },
            actionLabel = if (hasAddress) "Manage Email" else "Configure Email",
            destination = AppCatalogDestination.TOOL_CONFIG,
            destinationKey = "email",
        )
    }

    /**
     * Builds the Google Messages app catalog item.
     *
     * Queries the bridge FFI for live connection status.
     */
    private fun googleMessagesItem(): AppCatalogItem {
        val status =
            try {
                com.zeroclaw.ffi.messagesBridgeGetStatus()
            } catch (_: Exception) {
                null
            }
        val (statusLabel, actionLabel) =
            when (status) {
                is com.zeroclaw.ffi.FfiBridgeStatus.Connected ->
                    "Connected — receiving messages" to "Manage"
                is com.zeroclaw.ffi.FfiBridgeStatus.Reconnecting ->
                    "Reconnecting..." to "Manage"
                is com.zeroclaw.ffi.FfiBridgeStatus.AwaitingPairing ->
                    "Waiting for QR scan..." to "Continue Setup"
                is com.zeroclaw.ffi.FfiBridgeStatus.PhoneNotResponding ->
                    "Phone not responding" to "Manage"
                else -> "Not paired yet" to "Set Up"
            }
        return AppCatalogItem(
            id = "google_messages",
            category = AppCatalogCategory.WEB,
            title = "Google Messages",
            description = "Read and summarize your RCS/SMS conversations",
            statusLabel = statusLabel,
            actionLabel = actionLabel,
            destination = AppCatalogDestination.TOOL_CONFIG,
            destinationKey = "google_messages",
        )
    }

    /**
     * Tailscale tailnet service discovery catalog item.
     *
     * Checks install state via [PackageManager] and VPN state via
     * [ConnectivityManager] to determine the card's status and action.
     *
     * @param settings Current app settings for reading cached state.
     * @return Catalog item reflecting Tailscale install, VPN, and scan state.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun tailscaleItem(settings: AppSettings): AppCatalogItem {
        val context = getApplication<ZeroAIApplication>()
        val isInstalled =
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        "com.tailscale.ipn",
                        android.content.pm.PackageManager.PackageInfoFlags
                            .of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo("com.tailscale.ipn", 0)
                }
                true
            } catch (_: Exception) {
                false
            }

        if (!isInstalled) {
            return AppCatalogItem(
                id = "tailscale",
                category = AppCatalogCategory.WEB,
                title = "Tailscale",
                description = "Discover Ollama and zeroclaw on your tailnet",
                statusLabel = "Install Tailscale to connect your devices",
                actionLabel = "Get Tailscale",
                destination = AppCatalogDestination.EXTERNAL,
                externalAction = {
                    try {
                        val intent =
                            android.content
                                .Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(
                                        "market://details?id=com.tailscale.ipn",
                                    ),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        val intent =
                            android.content
                                .Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(
                                        "https://play.google.com/store/apps/" +
                                            "details?id=com.tailscale.ipn",
                                    ),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                },
            )
        }

        val connectivityManager =
            context.getSystemService(
                android.content.Context.CONNECTIVITY_SERVICE,
            ) as android.net.ConnectivityManager
        val isVpnActive =
            connectivityManager.activeNetwork?.let { network ->
                connectivityManager
                    .getNetworkCapabilities(network)
                    ?.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_VPN,
                    )
            } ?: false

        if (!isVpnActive) {
            return AppCatalogItem(
                id = "tailscale",
                category = AppCatalogCategory.WEB,
                title = "Tailscale",
                description = "Discover Ollama and zeroclaw on your tailnet",
                statusLabel = "Tailscale installed but VPN is inactive",
                actionLabel = "Open Tailscale",
                destination = AppCatalogDestination.EXTERNAL,
                externalAction = {
                    val intent =
                        context.packageManager
                            .getLaunchIntentForPackage("com.tailscale.ipn")
                    if (intent != null) {
                        intent.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
                        )
                        context.startActivity(intent)
                    }
                },
            )
        }

        val hasCachedPeers = settings.tailscaleCachedDiscovery.isNotBlank()
        val peerSummary =
            if (hasCachedPeers) {
                try {
                    val peers =
                        kotlinx.serialization.json.Json.decodeFromString<
                            List<
                                com.zeroclaw.android.model.CachedTailscalePeer,
                            >,
                        >(settings.tailscaleCachedDiscovery)
                    val serviceCount = peers.sumOf { it.services.size }
                    val hostnames =
                        peers
                            .filter { it.services.isNotEmpty() }
                            .joinToString(", ") { it.hostname.ifEmpty { it.ip } }
                    if (serviceCount > 0) {
                        "$serviceCount service(s) on $hostnames"
                    } else {
                        "No services found on tailnet"
                    }
                } catch (_: Exception) {
                    "No services found on tailnet"
                }
            } else {
                "Connected to tailnet \u2014 tap to set up"
            }

        return AppCatalogItem(
            id = "tailscale",
            category = AppCatalogCategory.WEB,
            title = "Tailscale",
            description = "Discover Ollama and zeroclaw on your tailnet",
            statusLabel = peerSummary,
            actionLabel = if (hasCachedPeers) "Manage" else "Set Up",
            destination = AppCatalogDestination.TOOL_CONFIG,
            destinationKey = "tailscale",
        )
    }

    private fun channelItem(
        type: ChannelType,
        channels: List<ConnectedChannel>,
    ): AppCatalogItem {
        val configured = channels.firstOrNull { it.type == type }
        val statusLabel =
            when {
                configured == null -> "Not connected yet"
                configured.isEnabled -> "Ready for remote chat"
                else -> "Configured but disabled"
            }
        val actionLabel = if (configured == null) "Connect ${type.displayName}" else "Manage ${type.displayName}"
        return AppCatalogItem(
            id = type.tomlKey,
            category = AppCatalogCategory.CHAT_APP,
            title = type.displayName,
            description =
                when (type) {
                    ChannelType.TELEGRAM -> "Chat with Zero from Telegram through your bot connection."
                    ChannelType.DISCORD -> "Chat with Zero from Discord through your bot connection."
                },
            statusLabel = statusLabel,
            actionLabel = actionLabel,
            destination = AppCatalogDestination.CHANNEL,
            destinationKey = configured?.id ?: type.name,
        )
    }
}
