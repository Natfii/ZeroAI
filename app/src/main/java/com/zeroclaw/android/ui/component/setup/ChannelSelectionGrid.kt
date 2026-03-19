/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component.setup

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.ui.component.AppServiceIcon
import com.zeroclaw.android.ui.theme.ZeroAITheme

/** Number of columns in the channel selection grid. */
private const val GRID_COLUMNS = 2

/** Spacing between grid cells. */
private val GridSpacing = 8.dp

/** Internal padding for each channel card. */
private val CardPadding = 12.dp

/** Border width for selected or configured channel cards. */
private val SelectedBorderWidth = 2.dp

/** Size of the checkmark icon for configured channels. */
private val CheckIconSize = 20.dp

/** Spacing after the title. */
private val TitleSpacing = 8.dp

/** Spacing between the description and the grid. */
private val DescriptionSpacing = 16.dp

/** Spacing before the skip hint text. */
private val HintSpacing = 8.dp

/**
 * Multi-select grid for choosing which chat apps to configure.
 *
 * Displays all [ChannelType] entries in a two-column grid.
 * Each card shows the channel's display name and a visual indicator for its
 * current state: a checkmark icon for channels already configured, a filled
 * primary border for channels currently selected, and a default appearance
 * for unselected channels.
 *
 * Tapping a card toggles its selection, adding or removing the channel type
 * from the [selectedTypes] set.
 *
 * @param selectedTypes Set of channel types currently selected for configuration.
 * @param configuredTypes Set of channel types that have already been configured.
 * @param onSelectionChanged Callback invoked with the updated set of selected types.
 * @param showSkipHint Whether to display a hint that chat apps can be added later.
 * @param title Section title shown above the grid.
 * @param description Supporting copy shown under [title].
 * @param skipHintText Text rendered when [showSkipHint] is true.
 * @param modifier Modifier applied to the root [Column].
 */
@Composable
fun ChannelSelectionGrid(
    selectedTypes: Set<ChannelType>,
    configuredTypes: Set<ChannelType> = emptySet(),
    onSelectionChanged: (Set<ChannelType>) -> Unit,
    showSkipHint: Boolean = false,
    title: String = "Chat Apps",
    description: String =
        "Choose where you want to chat with Zero outside the app. " +
            "You can add more later.",
    skipHintText: String =
        "You can skip this and add chat apps later in Settings or Hub > Apps.",
    modifier: Modifier = Modifier,
) {
    val channelTypes = ChannelType.entries

    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(TitleSpacing))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(DescriptionSpacing))

        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(GridSpacing),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = channelTypes,
                key = { it.name },
                contentType = { "channel_card" },
            ) { type ->
                val isSelected = type in selectedTypes
                val isConfigured = type in configuredTypes

                ChannelCard(
                    type = type,
                    isSelected = isSelected,
                    isConfigured = isConfigured,
                    onToggle = {
                        val updated =
                            if (isSelected) {
                                selectedTypes - type
                            } else {
                                selectedTypes + type
                            }
                        onSelectionChanged(updated)
                    },
                )
            }
        }

        if (showSkipHint) {
            Spacer(modifier = Modifier.height(HintSpacing))
            Text(
                text = skipHintText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Individual channel card within the selection grid.
 *
 * Visual states:
 * - **Configured**: displays a check-circle icon in primary colour and uses
 *   a primary border.
 * - **Selected** (but not yet configured): uses a primary border with
 *   primary-container background.
 * - **Default**: standard card appearance with no border.
 *
 * Accessibility semantics include the channel name, selection state, and
 * a checkbox role for screen readers.
 *
 * @param type The channel type this card represents.
 * @param isSelected Whether the channel is in the current selection set.
 * @param isConfigured Whether the channel has already been configured.
 * @param onToggle Callback invoked when the card is tapped.
 */
@Composable
private fun ChannelCard(
    type: ChannelType,
    isSelected: Boolean,
    isConfigured: Boolean,
    onToggle: () -> Unit,
) {
    val hasVisualEmphasis = isSelected || isConfigured
    val cardStateDescription =
        when {
            isConfigured -> "configured"
            isSelected -> "selected"
            else -> "not selected"
        }

    Card(
        onClick = onToggle,
        colors =
            if (isSelected && !isConfigured) {
                CardDefaults.cardColors(
                    containerColor =
                        MaterialTheme.colorScheme.primaryContainer,
                )
            } else {
                CardDefaults.cardColors()
            },
        border =
            if (hasVisualEmphasis) {
                BorderStroke(
                    SelectedBorderWidth,
                    MaterialTheme.colorScheme.primary,
                )
            } else {
                null
            },
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = type.displayName
                    role = Role.Checkbox
                    stateDescription = cardStateDescription
                },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CardPadding),
        ) {
            AppServiceIcon(
                label = type.displayName,
                iconUrl = channelIconUrl(type),
            )

            Spacer(modifier = Modifier.size(8.dp))

            Text(
                text = type.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )

            if (isConfigured) {
                Spacer(modifier = Modifier.size(8.dp))
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Already configured",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(CheckIconSize),
                )
            }
        }
    }
}

private fun channelIconUrl(type: ChannelType): String =
    faviconUrl(
        when (type) {
            ChannelType.TELEGRAM -> "https://telegram.org"
            ChannelType.DISCORD -> "https://discord.com"
        },
    )

private fun faviconUrl(url: String): String =
    "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON" +
        "&fallback_opts=TYPE,SIZE,URL&url=$url&size=128"

@Preview(name = "Channel Selection - Empty")
@Composable
private fun PreviewEmpty() {
    ZeroAITheme {
        Surface {
            ChannelSelectionGrid(
                selectedTypes = emptySet(),
                onSelectionChanged = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(name = "Channel Selection - With Selections")
@Composable
private fun PreviewWithSelections() {
    ZeroAITheme {
        Surface {
            ChannelSelectionGrid(
                selectedTypes =
                    setOf(
                        ChannelType.TELEGRAM,
                        ChannelType.DISCORD,
                    ),
                configuredTypes = setOf(ChannelType.TELEGRAM),
                onSelectionChanged = {},
            )
        }
    }
}

@Preview(
    name = "Channel Selection - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PreviewDark() {
    ZeroAITheme {
        Surface {
            ChannelSelectionGrid(
                selectedTypes = setOf(ChannelType.DISCORD),
                configuredTypes =
                    setOf(
                        ChannelType.TELEGRAM,
                    ),
                onSelectionChanged = {},
                showSkipHint = true,
            )
        }
    }
}
