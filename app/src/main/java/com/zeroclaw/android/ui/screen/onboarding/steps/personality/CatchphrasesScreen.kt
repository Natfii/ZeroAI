/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding.steps.personality

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Maximum number of catchphrase fields. */
private const val MAX_CATCHPHRASES = 2

/** Vertical spacing between sections. */
private val SectionSpacing = 16.dp

/** Minimum button height for touch targets. */
private val MinButtonHeight = 48.dp

/**
 * Personality sub-step 4: optional catchphrases and forbidden words.
 *
 * Renders up to [MAX_CATCHPHRASES] text fields for signature phrases and
 * one text field for comma-separated words the agent must avoid.
 *
 * @param catchphrases Current list of catchphrases.
 * @param forbiddenWords Current list of forbidden words.
 * @param onCatchphrasesChanged Callback with the updated catchphrase list.
 * @param onForbiddenWordsChanged Callback with the updated forbidden words list.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun CatchphrasesScreen(
    catchphrases: List<String>,
    forbiddenWords: List<String>,
    onCatchphrasesChanged: (List<String>) -> Unit,
    onForbiddenWordsChanged: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleCount =
        when {
            catchphrases.size >= MAX_CATCHPHRASES -> MAX_CATCHPHRASES
            catchphrases.isNotEmpty() &&
                catchphrases.first().isNotEmpty() -> catchphrases.size + 1
            else -> 1
        }.coerceAtMost(MAX_CATCHPHRASES)

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Text(
            text = "Catchphrases & Flavor",
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = "Optional \u2014 give your agent some personality",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        for (i in 0 until visibleCount) {
            val current = catchphrases.getOrElse(i) { "" }
            OutlinedTextField(
                value = current,
                onValueChange = { newValue ->
                    val updated = catchphrases.toMutableList()
                    while (updated.size <= i) updated.add("")
                    updated[i] = newValue
                    onCatchphrasesChanged(updated.filter { it.isNotEmpty() || updated.indexOf(it) < visibleCount })
                },
                label = {
                    Text(
                        if (i == 0) "Catchphrase" else "Catchphrase ${i + 1}",
                    )
                },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Catchphrase ${i + 1} input"
                        },
            )
        }

        val canAddMore =
            catchphrases.size < MAX_CATCHPHRASES &&
                catchphrases.isNotEmpty() &&
                catchphrases.first().isNotEmpty()
        if (canAddMore) {
            OutlinedButton(
                onClick = {
                    onCatchphrasesChanged(catchphrases + "")
                },
                modifier = Modifier.defaultMinSize(minHeight = MinButtonHeight),
            ) {
                Text("Add another")
            }
        }

        Spacer(modifier = Modifier.height(SectionSpacing / 2))

        OutlinedTextField(
            value = forbiddenWords.joinToString(", "),
            onValueChange = { raw ->
                val parsed =
                    raw
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                onForbiddenWordsChanged(parsed)
            },
            label = { Text("Words to avoid") },
            placeholder = { Text("Comma-separated") },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Forbidden words input"
                    },
        )
    }
}
