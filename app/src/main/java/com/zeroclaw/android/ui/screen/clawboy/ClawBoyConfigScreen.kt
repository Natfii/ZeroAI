/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.clawboy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private const val MIN_INTERVAL = 0.5f
private const val MAX_INTERVAL = 10.0f
private const val INTERVAL_STEPS = 19

/**
 * Configuration screen for the ClawBoy Game Boy emulator.
 *
 * Allows the user to load a Pokemon Red ROM, adjust the decision
 * interval, view the WebSocket viewer URL, and stop the session.
 * Games are started via chat triggers, not from this screen.
 *
 * @param onNavigateBack Called when the user navigates back.
 * @param viewModel The [ClawBoyConfigViewModel] managing screen state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClawBoyConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: ClawBoyConfigViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val romPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let { viewModel.onRomSelected(it) }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ClawBoy") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            is ClawBoyConfigViewModel.UiState.Loading -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is ClawBoyConfigViewModel.UiState.Empty -> {
                ClawBoyEmptyContent(
                    onBrowseRom = {
                        romPickerLauncher.launch(
                            arrayOf("application/octet-stream"),
                        )
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            is ClawBoyConfigViewModel.UiState.Error -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = state.retry) { Text("Retry") }
                    }
                }
            }
            is ClawBoyConfigViewModel.UiState.Content -> {
                ClawBoyConfigContent(
                    state = state,
                    onStop = viewModel::stopGame,
                    onIntervalChange =
                        viewModel::updateDecisionInterval,
                    onRemoveRom = viewModel::removeRom,
                    onBrowseRom = {
                        romPickerLauncher.launch(
                            arrayOf("application/octet-stream"),
                        )
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

/**
 * Empty state prompting the user to select a ROM file.
 *
 * @param onBrowseRom Callback to open the ROM file picker.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
private fun ClawBoyEmptyContent(
    onBrowseRom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Watch your AI agent play Pokemon Red",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Load a Pokemon Red (USA/Europe) ROM to get started. " +
                "Then say \u2018play pokemon\u2019 or \u2018start clawboy\u2019 in any chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBrowseRom,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics {
                        contentDescription = "Browse for ROM file"
                    },
        ) {
            Text("Select ROM File")
        }
    }
}

/**
 * Main content for the ClawBoy config screen when a ROM is loaded.
 *
 * @param state Current content state snapshot.
 * @param onStop Callback to stop the emulator session.
 * @param onIntervalChange Callback when the decision interval slider changes.
 * @param onRemoveRom Callback to delete the loaded ROM.
 * @param onBrowseRom Callback to open the ROM file picker for replacement.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
private fun ClawBoyConfigContent(
    state: ClawBoyConfigViewModel.UiState.Content,
    onStop: () -> Unit,
    onIntervalChange: (Float) -> Unit,
    onRemoveRom: () -> Unit,
    onBrowseRom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var sliderValue by remember(state.decisionIntervalSecs) {
        mutableFloatStateOf(state.decisionIntervalSecs)
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "ROM",
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.romFileName != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "ROM verified",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = state.romFileName,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            Button(
                onClick = onBrowseRom,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics {
                            contentDescription = "Browse for ROM file"
                        },
            ) {
                Text("Select ROM File")
            }
        }

        Text(
            "Decision Interval",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "How often the AI makes a decision: " +
                "${"%.1f".format(sliderValue)}s",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onIntervalChange(sliderValue) },
            enabled = !state.isPaused,
            valueRange = MIN_INTERVAL..MAX_INTERVAL,
            steps = INTERVAL_STEPS,
            modifier =
                Modifier.semantics {
                    contentDescription =
                        "Decision interval slider, " +
                        "${"%.1f".format(sliderValue)} seconds"
                },
        )

        if (state.viewerUrl != null) {
            Text(
                "Viewer",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.viewerUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(
                                Context.CLIPBOARD_SERVICE,
                            ) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "ClawBoy Viewer URL",
                                state.viewerUrl,
                            ),
                        )
                    },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Copy viewer URL"
                        },
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                    )
                }
            }
        }

        if (state.isPlaying) {
            val minutes = state.playTimeSeconds / SECONDS_PER_MINUTE
            val seconds = state.playTimeSeconds % SECONDS_PER_MINUTE
            Text(
                "Play time: ${minutes}m ${seconds}s",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (state.isPlaying || state.isPaused) {
            if (state.isPaused) {
                Text(
                    "Paused \u2014 battery saver active",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            OutlinedButton(
                onClick = onStop,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics {
                            contentDescription = "Stop ClawBoy session"
                        },
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Stop Game")
            }
        } else if (state.romFileName != null) {
            OutlinedButton(
                onClick = onRemoveRom,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics {
                            contentDescription = "Remove loaded ROM file"
                        },
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Remove ROM")
            }
        }
    }
}

private const val SECONDS_PER_MINUTE = 60
