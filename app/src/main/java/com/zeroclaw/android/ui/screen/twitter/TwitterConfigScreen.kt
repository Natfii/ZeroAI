/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.twitter

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.AppSettings
import kotlin.math.roundToInt

private const val MIN_MAX_ITEMS = 1
private const val MAX_MAX_ITEMS = 50
private const val MIN_TIMEOUT = 1
private const val MAX_TIMEOUT = 60

/**
 * Configuration screen for the Twitter/X read tool.
 *
 * Read-only by design: the tool hits X's public syndication endpoint
 * ([TwitterReadProfileTool] in `zeroclaw-ffi/src/twitter_browse_tool.rs`)
 * which does not require auth. No sign-in flow.
 *
 * @param onNavigateBack Called when the user navigates back.
 * @param viewModel The [TwitterConfigViewModel] managing screen state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwitterConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: TwitterConfigViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("X / Twitter") },
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
        val state = settings
        if (state == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            TwitterConfigContent(
                settings = state,
                onSetEnabled = viewModel::setEnabled,
                onSetMaxItems = viewModel::setMaxItems,
                onSetTimeoutSecs = viewModel::setTimeoutSecs,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * Light "what this is / what it can't do" card shown at the top of the
 * X / Twitter config screen. Read-only by design so the user isn't
 * surprised when search / DMs / posting aren't here.
 */
@Composable
private fun ReadOnlyExplainerCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Read-only profile timelines",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "When enabled, your AI can pull the most recent public " +
                    "tweets from any X account you ask about. Useful for " +
                    "summaries, mentions, or following someone's posts " +
                    "without leaving chat.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Limits:",
                style = MaterialTheme.typography.labelLarge,
            )
            BulletLine("Public profiles only — protected accounts return nothing.")
            BulletLine("About 20 most-recent tweets per request; no backfill.")
            BulletLine("No search, no DMs, no posting, no follow graph.")
            BulletLine(
                "Uses X's syndication endpoint (the one that powers " +
                    "embedded tweets). It's undocumented — X can rate-limit " +
                    "or break it without notice.",
            )
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "•  ",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TwitterConfigContent(
    settings: AppSettings,
    onSetEnabled: (Boolean) -> Unit,
    onSetMaxItems: (Int) -> Unit,
    onSetTimeoutSecs: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReadOnlyExplainerCard()

        val enabled = settings.twitterBrowseEnabled
        val enabledDesc = if (enabled) "enabled" else "disabled"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Enable X reading",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (enabled) {
                        "Tool is active. Ask me about any X handle."
                    } else {
                        "Off. Turn on to let me read X profiles."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onSetEnabled,
                modifier =
                    Modifier.semantics {
                        contentDescription = "X reading"
                        stateDescription = enabledDesc
                    },
            )
        }

        val maxItems = settings.twitterBrowseMaxItems.toInt()
        Text(
            "Max tweets per request: $maxItems",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = maxItems.toFloat(),
            onValueChange = { onSetMaxItems(it.roundToInt()) },
            valueRange = MIN_MAX_ITEMS.toFloat()..MAX_MAX_ITEMS.toFloat(),
            steps = MAX_MAX_ITEMS - MIN_MAX_ITEMS - 1,
        )

        val timeoutSecs = settings.twitterBrowseTimeoutSecs.toInt()
        Text(
            "Request timeout: $timeoutSecs seconds",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = timeoutSecs.toFloat(),
            onValueChange = { onSetTimeoutSecs(it.roundToInt()) },
            valueRange = MIN_TIMEOUT.toFloat()..MAX_TIMEOUT.toFloat(),
            steps = MAX_TIMEOUT - MIN_TIMEOUT - 1,
        )
    }
}
