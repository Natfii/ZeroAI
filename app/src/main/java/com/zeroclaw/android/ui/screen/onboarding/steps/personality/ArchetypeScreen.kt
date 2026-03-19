/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding.steps.personality

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityArchetype

/** Display metadata for each archetype card. */
private data class ArchetypeInfo(
    val archetype: PersonalityArchetype,
    val displayName: String,
    val description: String,
)

/** Ordered list of archetype card data. */
private val Archetypes =
    listOf(
        ArchetypeInfo(
            PersonalityArchetype.CHILL_COMPANION,
            "Chill Companion",
            "Relaxed, supportive, go-with-the-flow",
        ),
        ArchetypeInfo(
            PersonalityArchetype.SNARKY_SIDEKICK,
            "Snarky Sidekick",
            "Witty, teasing, loyal underneath",
        ),
        ArchetypeInfo(
            PersonalityArchetype.WISE_MENTOR,
            "Wise Mentor",
            "Patient, thoughtful, guiding",
        ),
        ArchetypeInfo(
            PersonalityArchetype.NAVI,
            "Navi",
            "Wise owl + fox: thoughtful, witty, playful",
        ),
        ArchetypeInfo(
            PersonalityArchetype.STOIC_OPERATOR,
            "Stoic Operator",
            "No-nonsense, efficient, direct",
        ),
        ArchetypeInfo(
            PersonalityArchetype.HYPE_BEAST,
            "Hype Beast",
            "Enthusiastic about everything",
        ),
        ArchetypeInfo(
            PersonalityArchetype.DARK_ACADEMIC,
            "Dark Academic",
            "Brooding intellectual, dramatic flair",
        ),
        ArchetypeInfo(
            PersonalityArchetype.COZY_CARETAKER,
            "Cozy Caretaker",
            "Nurturing, gentle, worries about you",
        ),
    )

/** Minimum height for each archetype card to ensure comfortable tap targets. */
private val CardMinHeight = 80.dp

/** Internal padding of each archetype card. */
private val CardPadding = 12.dp

/** Spacing between grid items. */
private val GridSpacing = 12.dp

/** Header spacing below the subtitle. */
private val HeaderSpacing = 16.dp

/** Border width for the selected card. */
private val SelectedBorderWidth = 1.dp

/**
 * Personality sub-step 2: archetype selection grid.
 *
 * Renders a 2-column [LazyVerticalGrid] of [ElevatedCard] items, one for
 * each [PersonalityArchetype]. The selected card is highlighted with
 * [primaryContainer] background and a [primary] border.
 *
 * @param selectedArchetype Currently selected archetype, or null.
 * @param onArchetypeSelected Callback when the user taps an archetype card.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ArchetypeScreen(
    selectedArchetype: PersonalityArchetype?,
    onArchetypeSelected: (PersonalityArchetype) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Choose a Personality",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(HeaderSpacing / 2))

        Text(
            text = "Pick the vibe that fits your agent",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(HeaderSpacing))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(GridSpacing),
        ) {
            items(
                items = Archetypes,
                key = { it.archetype.name },
                contentType = { "archetype_card" },
            ) { info ->
                val isSelected = info.archetype == selectedArchetype
                ArchetypeCard(
                    info = info,
                    isSelected = isSelected,
                    onClick = { onArchetypeSelected(info.archetype) },
                )
            }
        }
    }
}

/**
 * Single archetype selection card.
 *
 * @param info Display metadata for the archetype.
 * @param isSelected Whether this card is the current selection.
 * @param onClick Callback when the card is tapped.
 */
@Composable
private fun ArchetypeCard(
    info: ArchetypeInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val borderModifier =
        if (isSelected) {
            Modifier.border(
                SelectedBorderWidth,
                MaterialTheme.colorScheme.primary,
                CardDefaults.elevatedShape,
            )
        } else {
            Modifier
        }

    ElevatedCard(
        onClick = onClick,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = containerColor,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = CardMinHeight)
                .then(borderModifier)
                .semantics {
                    contentDescription =
                        "${info.displayName}: ${info.description}"
                    role = Role.RadioButton
                    selected = isSelected
                },
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text(
                text = info.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = info.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
