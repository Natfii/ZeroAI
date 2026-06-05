/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ui.component.NumberSettingField
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.ui.component.SettingsToggleRow

/** Number of slider steps for weight sliders. */
private const val WEIGHT_STEPS = 10

/**
 * Advanced memory configuration screen for hygiene, embedding, and recall tuning.
 *
 * Maps to upstream `[memory]` TOML section extended fields: hygiene
 * (archive/purge thresholds), embedding provider/model, and
 * vector/keyword weights for recall scoring.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param settingsViewModel The shared [SettingsViewModel].
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun MemoryAdvancedScreen(
    edgeMargin: Dp,
    settingsViewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = edgeMargin)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        SectionHeader(title = "Memory Hygiene")

        SettingsToggleRow(
            title = "Enable hygiene",
            subtitle = "Automatically archive and purge old memory entries",
            checked = settings.memoryHygieneEnabled,
            onCheckedChange = { settingsViewModel.updateMemoryHygieneEnabled(it) },
            contentDescription = "Enable memory hygiene",
        )

        NumberSettingField(
            value = settings.memoryArchiveAfterDays.toString(),
            onValueChange = { v ->
                v
                    .toIntOrNull()
                    ?.coerceAtLeast(0)
                    ?.let { settingsViewModel.updateMemoryArchiveAfterDays(it) }
            },
            label = "Archive after (days)",
            enabled = settings.memoryHygieneEnabled,
        )

        NumberSettingField(
            value = settings.memoryPurgeAfterDays.toString(),
            onValueChange = { v ->
                v
                    .toIntOrNull()
                    ?.coerceAtLeast(0)
                    ?.let { settingsViewModel.updateMemoryPurgeAfterDays(it) }
            },
            label = "Purge after (days)",
            enabled = settings.memoryHygieneEnabled,
        )

        SectionHeader(title = "Embedding")

        OutlinedTextField(
            value = settings.memoryEmbeddingProvider,
            onValueChange = { settingsViewModel.updateMemoryEmbeddingProvider(it) },
            label = { Text("Embedding provider") },
            supportingText = { Text("\"none\", \"openai\", or \"custom:URL\"") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.memoryEmbeddingModel,
            onValueChange = { settingsViewModel.updateMemoryEmbeddingModel(it) },
            label = { Text("Embedding model") },
            singleLine = true,
            enabled = settings.memoryEmbeddingProvider != "none",
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(title = "Recall Weights")

        Text(
            text = "Vector weight: ${"%.1f".format(settings.memoryVectorWeight)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = settings.memoryVectorWeight,
            onValueChange = { settingsViewModel.updateMemoryVectorWeight(it) },
            valueRange = 0f..1f,
            steps = WEIGHT_STEPS - 1,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Vector weight slider" },
        )

        Text(
            text = "Keyword weight: ${"%.1f".format(settings.memoryKeywordWeight)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = settings.memoryKeywordWeight,
            onValueChange = { settingsViewModel.updateMemoryKeywordWeight(it) },
            valueRange = 0f..1f,
            steps = WEIGHT_STEPS - 1,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Keyword weight slider" },
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
