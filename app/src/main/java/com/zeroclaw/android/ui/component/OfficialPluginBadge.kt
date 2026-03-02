/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Badge indicating a plugin is an official ZeroClaw built-in.
 *
 * Uses [MaterialTheme.colorScheme.tertiaryContainer] to distinguish from
 * the category badge.
 *
 * @param modifier Modifier applied to the chip.
 */
@Composable
fun OfficialPluginBadge(modifier: Modifier = Modifier) {
    SuggestionChip(
        onClick = {},
        label = {
            Text(
                text = "Official",
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = modifier
            .semantics { contentDescription = "Official ZeroClaw plugin" },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        border = null,
    )
}
