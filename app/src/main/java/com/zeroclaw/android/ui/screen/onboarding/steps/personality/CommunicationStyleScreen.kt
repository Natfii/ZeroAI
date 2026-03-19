/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding.steps.personality

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Formality options: display label to stored value. */
private val FormalityOptions =
    listOf(
        "Casual" to "casual",
        "Balanced" to "balanced",
        "Formal" to "formal",
    )

/** Verbosity options: display label to stored value. */
private val VerbosityOptions =
    listOf(
        "Terse" to "terse",
        "Normal" to "normal",
        "Verbose" to "verbose",
    )

/** Vertical spacing between sections. */
private val SectionSpacing = 16.dp

/** Spacing between a section label and its segmented button row. */
private val LabelButtonSpacing = 8.dp

/**
 * Personality sub-step 3: communication style selectors.
 *
 * Renders two [SingleChoiceSegmentedButtonRow] controls: one for formality
 * (casual / balanced / formal) and one for verbosity (terse / normal / verbose).
 *
 * @param formality Current formality value.
 * @param verbosity Current verbosity value.
 * @param onFormalityChanged Callback when formality selection changes.
 * @param onVerbosityChanged Callback when verbosity selection changes.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun CommunicationStyleScreen(
    formality: String,
    verbosity: String,
    onFormalityChanged: (String) -> Unit,
    onVerbosityChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Text(
            text = "Communication Style",
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = "How should your agent talk?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(LabelButtonSpacing))

        Text(
            text = "Formality",
            style = MaterialTheme.typography.titleMedium,
        )

        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Formality selector"
                    },
        ) {
            FormalityOptions.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    selected = formality == value,
                    onClick = { onFormalityChanged(value) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = FormalityOptions.size,
                        ),
                ) {
                    Text(label)
                }
            }
        }

        Spacer(modifier = Modifier.height(LabelButtonSpacing))

        Text(
            text = "Verbosity",
            style = MaterialTheme.typography.titleMedium,
        )

        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Verbosity selector"
                    },
        ) {
            VerbosityOptions.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    selected = verbosity == value,
                    onClick = { onVerbosityChanged(value) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = VerbosityOptions.size,
                        ),
                ) {
                    Text(label)
                }
            }
        }
    }
}
