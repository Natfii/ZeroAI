/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding.steps.personality

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Ordered list of interest topics displayed as filter chips. */
private val Topics =
    listOf(
        "Programming",
        "Science",
        "Math",
        "Writing",
        "Music",
        "Art & Design",
        "Gaming",
        "Finance",
        "Fitness & Health",
        "Cooking",
        "Memes & Internet Culture",
        "Philosophy",
        "Space & Astronomy",
        "History",
        "Anime & Manga",
        "Movies & TV",
        "Nature & Animals",
        "True Crime",
        "Sports",
        "D&D & Tabletop",
    )

/** Ideal minimum number of selected interests. */
private const val MIN_IDEAL_INTERESTS = 3

/** Ideal maximum number of selected interests. */
private const val MAX_IDEAL_INTERESTS = 5

/** Vertical spacing between sections. */
private val SectionSpacing = 16.dp

/** Spacing between chips. */
private val ChipSpacing = 8.dp

/**
 * Personality sub-step 5: interest topic selection.
 *
 * Renders a [FlowRow] of 20 [FilterChip] items representing topics the
 * agent should be knowledgeable about. A counter label changes color to
 * indicate whether the selection is within the ideal 3-5 range.
 *
 * @param selectedInterests Set of currently selected topic strings.
 * @param onInterestToggled Callback with the topic string to toggle.
 * @param modifier Modifier applied to the root layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterestsScreen(
    selectedInterests: Set<String>,
    onInterestToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = selectedInterests.size
    val counterColor =
        when {
            count in MIN_IDEAL_INTERESTS..MAX_IDEAL_INTERESTS ->
                MaterialTheme.colorScheme.primary
            count > MAX_IDEAL_INTERESTS -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Text(
            text = "Interests",
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = "Pick 3\u20135 topics your agent cares about",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "$count selected",
            style = MaterialTheme.typography.labelLarge,
            color = counterColor,
            modifier =
                Modifier.semantics {
                    contentDescription = "$count interests selected"
                },
        )

        Spacer(modifier = Modifier.height(ChipSpacing / 2))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
            verticalArrangement = Arrangement.spacedBy(ChipSpacing),
        ) {
            Topics.forEach { topic ->
                val isSelected = topic in selectedInterests
                FilterChip(
                    selected = isSelected,
                    onClick = { onInterestToggled(topic) },
                    label = { Text(topic) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor =
                                MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor =
                                MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                if (isSelected) {
                                    "$topic, selected"
                                } else {
                                    "$topic, not selected"
                                }
                        },
                )
            }
        }
    }
}
