/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.discord

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.data.local.discord.DiscordChannelConfigEntity
import com.zeroclaw.android.ui.component.EmptyState

/** Minimum touch target size in dp. */
private const val MIN_TOUCH_TARGET_DP = 48

/** Standard row padding in dp. */
private const val ROW_PADDING_DP = 16

/** Icon-to-text spacing in dp. */
private const val ICON_TEXT_SPACING_DP = 12

/** Item vertical spacing in dp. */
private const val ITEM_SPACING_DP = 8

/** Section heading spacing in dp. */
private const val SECTION_SPACING_DP = 24

/** Inner card padding in dp. */
private const val CARD_PADDING_DP = 16

/** Maximum number of guild archive channels allowed. */
private const val MAX_GUILD_CHANNELS = 3

/** Small icon size in dp. */
private const val SMALL_ICON_DP = 16

/**
 * Discord setup and archive channel management screen.
 *
 * Wraps [DiscordSetupContent] in a Scaffold with a top bar and back
 * navigation. All setup state, dialogs, and sheets are managed internally
 * by [DiscordSetupContent].
 *
 * @param onChannelClick Callback when a channel card is tapped, passing the channel ID.
 * @param onBack Callback when the back button is pressed.
 * @param modifier Modifier applied to the root layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordChannelsScreen(
    onChannelClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discord") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Navigate back"
                            },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        DiscordSetupContent(
            onChannelClick = onChannelClick,
            flagRestartRequired = true,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * DM user link section showing the linked user or a link button.
 *
 * @param dmUser Currently linked DM username, or null if not linked.
 * @param onLinkClick Callback when the link/change button is tapped.
 * @param onUnlinkClick Callback when the linked user is removed.
 */
@Composable
internal fun DmLinkSection(
    dmUser: String?,
    onLinkClick: () -> Unit,
    onUnlinkClick: () -> Unit,
) {
    Text(
        text = "DM User",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CARD_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (dmUser != null) Icons.Filled.Person else Icons.Filled.PersonAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(ICON_TEXT_SPACING_DP.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (dmUser != null) {
                    Text(
                        text = dmUser,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "DM messages sync to private memory",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "No DM user linked",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Link a user to archive DM conversations",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dmUser != null) {
                    TextButton(
                        onClick = onUnlinkClick,
                        modifier =
                            Modifier
                                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                                .semantics {
                                    contentDescription = "Unlink DM user"
                                },
                    ) {
                        Text("Unlink")
                    }
                    Spacer(modifier = Modifier.width(ITEM_SPACING_DP.dp))
                }
                OutlinedButton(
                    onClick = onLinkClick,
                    modifier =
                        Modifier
                            .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                            .semantics {
                                contentDescription =
                                    if (dmUser != null) {
                                        "Change linked DM user"
                                    } else {
                                        "Link DM user"
                                    }
                            },
                ) {
                    Text(if (dmUser != null) "Change" else "Link")
                }
            }
        }
    }
}

/**
 * Guild archive channels section with count indicator and channel list.
 *
 * @param channels List of configured archive channels.
 * @param onChannelClick Callback when a channel card is tapped.
 * @param onDeleteRequest Callback when delete is requested for a channel.
 */
@Composable
internal fun GuildChannelsSection(
    channels: List<DiscordChannelConfigEntity>,
    onChannelClick: (String) -> Unit,
    onDeleteRequest: (DiscordChannelConfigEntity) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Guild Channels",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${channels.size}/$MAX_GUILD_CHANNELS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.semantics {
                    contentDescription =
                        "${channels.size} of $MAX_GUILD_CHANNELS channels configured"
                },
        )
    }

    Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

    if (channels.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Tag,
            message = "No archive channels configured",
        )
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP.dp),
        ) {
            channels.forEach { channel ->
                ChannelListItem(
                    channel = channel,
                    onClick = { onChannelClick(channel.channelId) },
                    onDelete = { onDeleteRequest(channel) },
                )
            }
            Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
        }
    }
}

/**
 * Single archive channel card showing name, sync status, and delete button.
 *
 * @param channel The channel configuration entity.
 * @param onClick Callback when the card is tapped.
 * @param onDelete Callback when the delete button is tapped.
 */
@Composable
internal fun ChannelListItem(
    channel: DiscordChannelConfigEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val isEnabled = channel.enabled == 1

    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
    ) {
        Row(
            modifier = Modifier.padding(ROW_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Tag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(ICON_TEXT_SPACING_DP.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "#${channel.channelName}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(SMALL_ICON_DP.dp)
                                .padding(end = 4.dp),
                    ) {
                        Icon(
                            imageVector =
                                if (isEnabled) {
                                    Icons.Filled.Tag
                                } else {
                                    Icons.Filled.LinkOff
                                },
                            contentDescription = null,
                            tint =
                                if (isEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            modifier = Modifier.size(SMALL_ICON_DP.dp),
                        )
                    }
                    Text(
                        text =
                            if (isEnabled) {
                                "Archiving \u00B7 ${channel.backfillDepth}"
                            } else {
                                "Paused"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Delete #${channel.channelName}"
                    },
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Dialog for linking a DM user by entering their Discord user ID.
 *
 * @param currentUser Currently linked username, or null.
 * @param onLink Callback with the entered user ID when submitted.
 * @param onDismiss Callback when the dialog is dismissed.
 */
@Composable
internal fun DmLinkDialog(
    currentUser: String?,
    onLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var userId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (currentUser != null) "Change DM User" else "Link DM User",
            )
        },
        text = {
            Column {
                if (currentUser != null) {
                    Text(
                        text = "Currently linked: $currentUser",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))
                }
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Discord User ID") },
                    supportingText = {
                        Text("Right-click your profile in Discord and copy your user ID")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLink(userId) },
                enabled = userId.isNotBlank(),
            ) {
                Text("Link")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Bottom sheet for selecting a guild text channel from the fetched list.
 *
 * @param channels Available guild channels from the Discord API.
 * @param isLoading Whether channels are still being fetched.
 * @param existingChannelIds Set of channel IDs already configured.
 * @param onChannelSelected Callback when a channel is selected.
 * @param onDismiss Callback when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelPickerSheet(
    channels: List<GuildChannel>,
    isLoading: Boolean,
    existingChannelIds: Set<String>,
    onChannelSelected: (GuildChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var filterText by remember { mutableStateOf("") }

    val textChannels =
        remember(channels) {
            channels.filter { it.type == 0 }
        }
    val filteredChannels =
        remember(textChannels, filterText) {
            if (filterText.isBlank()) {
                textChannels
            } else {
                textChannels.filter {
                    it.name.contains(filterText, ignoreCase = true)
                }
            }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ROW_PADDING_DP.dp)
                    .padding(bottom = SECTION_SPACING_DP.dp)
                    .semantics { paneTitle = "Select Discord channel" },
        ) {
            Text(
                text = "Select Channel",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                label = { Text("Filter channels") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

            if (isLoading) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(MIN_TOUCH_TARGET_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredChannels.isEmpty()) {
                Text(
                    text =
                        if (textChannels.isEmpty()) {
                            "No text channels found in guild"
                        } else {
                            "No channels match filter"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = ROW_PADDING_DP.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = filteredChannels,
                        key = { it.id },
                        contentType = { "guild_channel_picker" },
                    ) { channel ->
                        val alreadyAdded = channel.id in existingChannelIds
                        Card(
                            onClick = {
                                if (!alreadyAdded) {
                                    onChannelSelected(channel)
                                }
                            },
                            enabled = !alreadyAdded,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                                    .semantics {
                                        contentDescription =
                                            if (alreadyAdded) {
                                                "#${channel.name} already configured"
                                            } else {
                                                "Select #${channel.name}"
                                            }
                                    },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (alreadyAdded) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                ),
                        ) {
                            Row(
                                modifier =
                                    Modifier.padding(
                                        horizontal = ROW_PADDING_DP.dp,
                                        vertical = ITEM_SPACING_DP.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tag,
                                    contentDescription = null,
                                    tint =
                                        if (alreadyAdded) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                )
                                Spacer(modifier = Modifier.width(ICON_TEXT_SPACING_DP.dp))
                                Text(
                                    text = channel.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color =
                                        if (alreadyAdded) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                )
                                if (alreadyAdded) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "Added",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom sheet for selecting a guild (server) the bot is a member of.
 *
 * @param guilds Available guilds fetched from the Discord API.
 * @param isLoading Whether guilds are still being fetched.
 * @param onGuildSelected Callback when a guild is selected.
 * @param onDismiss Callback when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuildPickerSheet(
    guilds: List<DiscordGuild>,
    isLoading: Boolean,
    onGuildSelected: (DiscordGuild) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ROW_PADDING_DP.dp)
                    .padding(bottom = SECTION_SPACING_DP.dp)
                    .semantics { paneTitle = "Select Discord server" },
        ) {
            Text(
                text = "Select Server",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

            if (isLoading) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(MIN_TOUCH_TARGET_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (guilds.isEmpty()) {
                Text(
                    text = "No servers found. Make sure the bot has been added to at least one server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = ROW_PADDING_DP.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = guilds,
                        key = { it.id },
                        contentType = { "guild_picker" },
                    ) { guild ->
                        Card(
                            onClick = { onGuildSelected(guild) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                                    .semantics {
                                        contentDescription = "Select ${guild.name}"
                                    },
                        ) {
                            Row(
                                modifier =
                                    Modifier.padding(
                                        horizontal = ROW_PADDING_DP.dp,
                                        vertical = ITEM_SPACING_DP.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tag,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(ICON_TEXT_SPACING_DP.dp))
                                Text(
                                    text = guild.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for selecting the backfill depth when adding a new archive channel.
 *
 * @param channelName Name of the channel being added.
 * @param onDepthSelected Callback with the selected backfill depth.
 * @param onDismiss Callback when the dialog is dismissed.
 */
@Composable
internal fun BackfillDepthDialog(
    channelName: String,
    onDepthSelected: (BackfillDepth) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backfill #$channelName") },
        text = {
            Column {
                Text(
                    text = "How much message history should be backfilled?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(ROW_PADDING_DP.dp))
                BackfillDepth.entries.forEach { depth ->
                    TextButton(
                        onClick = { onDepthSelected(depth) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                                .semantics {
                                    contentDescription = "Backfill ${depth.label}"
                                },
                    ) {
                        Text(
                            text = depth.label,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Confirmation dialog before deleting an archive channel.
 *
 * @param channelName Display name of the channel to delete.
 * @param onConfirm Called when the user confirms deletion.
 * @param onDismiss Called when the user cancels.
 */
@Composable
internal fun ConfirmDeleteDialog(
    channelName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove #$channelName?") },
        text = {
            Text(
                "This will stop archiving and delete all archived messages " +
                    "for this channel.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
