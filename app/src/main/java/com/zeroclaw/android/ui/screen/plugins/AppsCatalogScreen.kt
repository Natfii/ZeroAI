/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.R
import com.zeroclaw.android.ui.component.AppServiceIcon
import com.zeroclaw.android.ui.component.ContentPane
import com.zeroclaw.android.ui.component.SectionHeader

/**
 * Hub Apps tab showing the full app catalog, including disabled integrations.
 *
 * @param onNavigateToChannelDetail Callback for Telegram/Discord setup screens.
 * @param onNavigateToDiscordHub Callback when Discord is tapped in Apps tab; routes to setup or channel management.
 * @param onNavigateToProviderSlotDetail Callback for provider-slot detail screens.
 * @param onNavigateToTwitterConfig Callback to navigate to the Twitter/X tool config screen.
 * @param onNavigateToClawBoyConfig Callback to navigate to the ClawBoy config screen.
 * @param onNavigateToEmailConfig Callback to navigate to the email config screen.
 * @param onNavigateToGoogleMessages Callback to navigate to the Google Messages setup screen.
 * @param onNavigateToTailscaleConfig Callback to navigate to the Tailscale config screen.
 * @param edgeMargin Horizontal padding for the content pane.
 * @param viewModel The [AppsCatalogViewModel] backing the catalog.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun AppsCatalogScreen(
    onNavigateToChannelDetail: (channelId: String?, channelType: String?) -> Unit,
    onNavigateToDiscordHub: () -> Unit,
    @Suppress("UnusedParameter") onNavigateToProviderSlotDetail: (String) -> Unit,
    onNavigateToTwitterConfig: () -> Unit,
    onNavigateToClawBoyConfig: () -> Unit,
    onNavigateToEmailConfig: () -> Unit,
    onNavigateToGoogleMessages: () -> Unit,
    onNavigateToTailscaleConfig: () -> Unit,
    edgeMargin: Dp,
    viewModel: AppsCatalogViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val catalogItems by viewModel.items.collectAsStateWithLifecycle()

    ContentPane(modifier = modifier.padding(horizontal = edgeMargin)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("apps-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Apps",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text =
                            "Chat Apps are Telegram and Discord. Provider APIs live in Agents.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            AppCatalogCategory.entries.forEach { category ->
                val sectionItems = catalogItems.filter { it.category == category }
                if (sectionItems.isNotEmpty()) {
                    item("section-${category.name}") {
                        SectionHeader(title = category.title)
                    }
                    items(
                        items = sectionItems,
                        key = { item -> item.id },
                        contentType = { "app_catalog_card" },
                    ) { item ->
                        val onOpen =
                            remember(item) {
                                {
                                    when (item.destination) {
                                        AppCatalogDestination.CHANNEL -> {
                                            if (item.id == "discord") {
                                                onNavigateToDiscordHub()
                                            } else {
                                                val channelType =
                                                    if (item.id == "telegram") {
                                                        "TELEGRAM"
                                                    } else {
                                                        "DISCORD"
                                                    }
                                                onNavigateToChannelDetail(
                                                    item.destinationKey?.takeIf { it != channelType },
                                                    item.destinationKey?.takeIf { it == channelType }
                                                        ?: channelType,
                                                )
                                            }
                                        }
                                        AppCatalogDestination.TOOL_CONFIG -> {
                                            when (item.destinationKey) {
                                                "clawboy" ->
                                                    onNavigateToClawBoyConfig()
                                                "email" ->
                                                    onNavigateToEmailConfig()
                                                "google_messages" ->
                                                    onNavigateToGoogleMessages()
                                                "tailscale" ->
                                                    onNavigateToTailscaleConfig()
                                                else ->
                                                    onNavigateToTwitterConfig()
                                            }
                                        }
                                        AppCatalogDestination.EXTERNAL -> {
                                            item.externalAction?.invoke()
                                            Unit
                                        }
                                    }
                                }
                            }
                        AppCatalogCard(item = item, onOpen = onOpen)
                    }
                }
            }
        }
    }
}

/**
 * Single card in the Hub Apps catalog.
 *
 * @param item Immutable app catalog item.
 * @param onOpen Callback to open the relevant detail screen.
 */
@Composable
private fun AppCatalogCard(
    item: AppCatalogItem,
    onOpen: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppServiceIcon(
                    label = item.title,
                    iconUrl = appCatalogIconUrl(item),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = item.statusLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(item.actionLabel)
            }
        }
    }
}

private fun appCatalogIconUrl(item: AppCatalogItem): String =
    when (item.id) {
        "google_messages" ->
            "android.resource://com.zeroclaw.android/${R.drawable.ic_google_messages}"
        else ->
            faviconUrl(
                when (item.id) {
                    "telegram" -> "https://telegram.org"
                    "discord" -> "https://discord.com"
                    "twitter_browse" -> "https://x.com"
                    "clawboy" -> "https://zero.example"
                    "email" -> "https://mail.google.com"
                    "tailscale" -> "https://tailscale.com"
                    else -> "https://zero.example"
                },
            )
    }

private fun faviconUrl(url: String): String =
    "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON" +
        "&fallback_opts=TYPE,SIZE,URL&url=$url&size=128"

private val AppCatalogCategory.title: String
    get() =
        when (this) {
            AppCatalogCategory.WEB -> "Web"
            AppCatalogCategory.CHAT_APP -> "Chat Apps"
        }
