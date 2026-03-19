/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.discord

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.data.local.discord.DiscordChannelConfigEntity
import com.zeroclaw.android.util.ExternalAppLauncher

/** Standard padding values. */
private const val EDGE_PADDING_DP = 16
private const val ITEM_SPACING_DP = 8
private const val SECTION_SPACING_DP = 24
private const val MIN_TOUCH_TARGET_DP = 48

/**
 * Self-contained Discord setup and management composable.
 *
 * Shows a bot token entry form when unconfigured. Once validated and saved,
 * transitions to the full DM link and guild channel management UI.
 * Reusable in both Hub and onboarding.
 *
 * @param onChannelClick Callback when a channel card is tapped, or null
 *   to disable drill-down (e.g., during onboarding).
 * @param flagRestartRequired When true, flags daemon restart after changes.
 * @param modifier Modifier applied to the root layout.
 * @param viewModel The [DiscordChannelsViewModel] for state management.
 */
@Composable
fun DiscordSetupContent(
    onChannelClick: ((String) -> Unit)?,
    flagRestartRequired: Boolean,
    modifier: Modifier = Modifier,
    viewModel: DiscordChannelsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val guildChannels by viewModel.guildChannels.collectAsStateWithLifecycle()
    val botGuilds by viewModel.botGuilds.collectAsStateWithLifecycle()
    val tokenState by viewModel.tokenValidation.collectAsStateWithLifecycle()

    var botToken by remember { mutableStateOf<String?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }

    var showDmLinkDialog by remember { mutableStateOf(false) }
    var showChannelPicker by remember { mutableStateOf(false) }
    var showBackfillPicker by remember { mutableStateOf(false) }
    var channelToDelete by remember {
        mutableStateOf<DiscordChannelConfigEntity?>(null)
    }
    var selectedPickerChannel by remember {
        mutableStateOf<GuildChannel?>(null)
    }
    var showGuildPicker by remember { mutableStateOf(false) }
    var selectedGuildId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        botToken = viewModel.resolveDiscordBotToken()
    }

    val isConfigured = botToken != null && !isEditing

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = EDGE_PADDING_DP.dp),
    ) {
        AnimatedVisibility(visible = uiState.isLoading) {
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = ITEM_SPACING_DP.dp),
            )
        }

        Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

        if (!isConfigured) {
            TokenEntrySection(
                tokenInput = tokenInput,
                onTokenChange = { tokenInput = it },
                tokenState = tokenState,
                onValidate = { viewModel.validateBotToken(tokenInput) },
                onSave = {
                    viewModel.saveBotToken(tokenInput, flagRestartRequired)
                    botToken = tokenInput
                    isEditing = false
                },
                onCancel =
                    if (botToken != null) {
                        { isEditing = false }
                    } else {
                        null
                    },
            )
        } else {
            TokenBanner(
                botName =
                    (tokenState as? TokenValidationState.Success)
                        ?.botName ?: "Discord Bot",
                onEdit = {
                    tokenInput = ""
                    isEditing = true
                },
            )

            Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))

            DmLinkSection(
                dmUser = uiState.dmUser,
                onLinkClick = { showDmLinkDialog = true },
                onUnlinkClick = { viewModel.unlinkDmUser() },
            )

            Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))

            Button(
                onClick = {
                    val token = botToken
                    if (token != null) {
                        viewModel.fetchBotGuilds(token)
                        showGuildPicker = true
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                        .semantics {
                            contentDescription = "Add archive channel"
                        },
            ) {
                Text("Add Channel")
            }

            Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))

            GuildChannelsSection(
                channels = uiState.channels,
                onChannelClick = { channelId ->
                    onChannelClick?.invoke(channelId)
                },
                onDeleteRequest = { channelToDelete = it },
            )
        }
    }

    if (showDmLinkDialog) {
        DmLinkDialog(
            currentUser = uiState.dmUser,
            onLink = { userId ->
                botToken?.let { viewModel.linkDmUser(it, userId) }
                showDmLinkDialog = false
            },
            onDismiss = { showDmLinkDialog = false },
        )
    }

    if (showGuildPicker) {
        GuildPickerSheet(
            guilds = botGuilds,
            isLoading = uiState.isLoading,
            onGuildSelected = { guild ->
                selectedGuildId = guild.id
                showGuildPicker = false
                botToken?.let {
                    viewModel.fetchGuildChannels(it, guild.id)
                }
                showChannelPicker = true
            },
            onDismiss = { showGuildPicker = false },
        )
    }

    if (showChannelPicker) {
        ChannelPickerSheet(
            channels = guildChannels,
            isLoading = uiState.isLoading,
            existingChannelIds =
                uiState.channels
                    .map { it.channelId }
                    .toSet(),
            onChannelSelected = { channel ->
                selectedPickerChannel = channel
                showChannelPicker = false
                showBackfillPicker = true
            },
            onDismiss = { showChannelPicker = false },
        )
    }

    if (showBackfillPicker && selectedPickerChannel != null) {
        BackfillDepthDialog(
            channelName = selectedPickerChannel!!.name,
            onDepthSelected = { depth ->
                val channel = selectedPickerChannel!!
                viewModel.addChannel(
                    channelId = channel.id,
                    guildId = selectedGuildId.orEmpty(),
                    name = channel.name,
                    backfillDepth = depth.value,
                    flagRestart = flagRestartRequired,
                )
                selectedPickerChannel = null
                showBackfillPicker = false
            },
            onDismiss = {
                selectedPickerChannel = null
                showBackfillPicker = false
            },
        )
    }

    channelToDelete?.let { channel ->
        ConfirmDeleteDialog(
            channelName = channel.channelName,
            onConfirm = {
                viewModel.removeChannel(
                    channel.channelId,
                    flagRestartRequired,
                )
                channelToDelete = null
            },
            onDismiss = { channelToDelete = null },
        )
    }
}

/**
 * Token entry form with validation button and inline error/success display.
 *
 * @param tokenInput Current text in the token field.
 * @param onTokenChange Callback when the text changes.
 * @param tokenState Current [TokenValidationState].
 * @param onValidate Callback when the Validate button is tapped.
 * @param onSave Callback when the Save & Continue button is tapped.
 * @param onCancel Callback when Cancel is tapped, or null to hide Cancel.
 */
@Composable
private fun TokenEntrySection(
    tokenInput: String,
    onTokenChange: (String) -> Unit,
    tokenState: TokenValidationState,
    onValidate: () -> Unit,
    onSave: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    Text(
        text = "Connect Discord Bot",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))
    Text(
        text = "Enter your Discord bot token to get started.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            ExternalAppLauncher.launch(
                context,
                ExternalAppLauncher.DISCORD_DEV_PORTAL,
            )
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                .semantics {
                    contentDescription = "Open Discord Developer Portal"
                },
    ) {
        Text("Open Developer Portal")
    }
    Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

    OutlinedTextField(
        value = tokenInput,
        onValueChange = onTokenChange,
        label = { Text("Bot Token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Discord bot token input"
                },
    )

    Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

    when (tokenState) {
        is TokenValidationState.Validating -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.spacedBy(
                        ITEM_SPACING_DP.dp,
                    ),
            ) {
                CircularProgressIndicator(
                    modifier =
                        Modifier.defaultMinSize(
                            minWidth = 20.dp,
                            minHeight = 20.dp,
                        ),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Validating...",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is TokenValidationState.Success -> {
            Text(
                text = "Verified: ${tokenState.botName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is TokenValidationState.Error -> {
            Text(
                text = tokenState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is TokenValidationState.Idle -> { /* no indicator */ }
    }

    Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(
                ITEM_SPACING_DP.dp,
            ),
    ) {
        if (onCancel != null) {
            TextButton(
                onClick = onCancel,
                modifier =
                    Modifier.defaultMinSize(
                        minHeight = MIN_TOUCH_TARGET_DP.dp,
                    ),
            ) {
                Text("Cancel")
            }
        }
        if (tokenState is TokenValidationState.Success) {
            Button(
                onClick = onSave,
                modifier =
                    Modifier
                        .weight(1f)
                        .defaultMinSize(
                            minHeight = MIN_TOUCH_TARGET_DP.dp,
                        ),
            ) {
                Text("Save & Continue")
            }
        } else {
            Button(
                onClick = onValidate,
                enabled =
                    tokenInput.isNotBlank() &&
                        tokenState !is TokenValidationState.Validating,
                modifier =
                    Modifier
                        .weight(1f)
                        .defaultMinSize(
                            minHeight = MIN_TOUCH_TARGET_DP.dp,
                        ),
            ) {
                Text("Validate")
            }
        }
    }
}

/**
 * Connected bot banner with edit button.
 *
 * @param botName Display name of the connected bot.
 * @param onEdit Callback when the edit button is tapped.
 */
@Composable
private fun TokenBanner(
    botName: String,
    onEdit: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(EDGE_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = ITEM_SPACING_DP.dp),
            ) {
                Text(
                    text = "Connected as $botName",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            IconButton(
                onClick = onEdit,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Edit bot token"
                    },
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                )
            }
        }
    }
}
