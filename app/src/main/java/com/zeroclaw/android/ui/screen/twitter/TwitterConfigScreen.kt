/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.twitter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

private const val MIN_MAX_ITEMS = 1
private const val MAX_MAX_ITEMS = 50
private const val MIN_TIMEOUT = 1
private const val MAX_TIMEOUT = 60

/**
 * Configuration screen for the Twitter/X browse tool.
 *
 * Displays connection status, account info, enable/disable toggle,
 * and configuration sliders for max items and timeout.
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showLoginSheet by remember { mutableStateOf(false) }

    if (showLoginSheet) {
        TwitterLoginSheet(
            onCookiesExtracted = { cookies ->
                viewModel.onCookiesExtracted(cookies)
                showLoginSheet = false
            },
            onDismiss = { showLoginSheet = false },
        )
        return
    }

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
        when (val state = uiState) {
            is TwitterConfigViewModel.UiState.Loading -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is TwitterConfigViewModel.UiState.Empty -> {
                // Convention compliance
            }
            is TwitterConfigViewModel.UiState.Error -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = state.retry) { Text("Retry") }
                    }
                }
            }
            is TwitterConfigViewModel.UiState.Content -> {
                TwitterConfigContent(
                    state = state,
                    onConnect = { showLoginSheet = true },
                    onDisconnect = viewModel::disconnect,
                    onSetEnabled = viewModel::setEnabled,
                    onSetMaxItems = viewModel::setMaxItems,
                    onSetTimeoutSecs = viewModel::setTimeoutSecs,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun TwitterConfigContent(
    state: TwitterConfigViewModel.UiState.Content,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
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
        if (!state.connected) {
            Text(
                "Let your AI companion browse X/Twitter in read-only mode",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onConnect,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp),
            ) {
                Text("Connect X")
            }
        } else {
            Text(
                text =
                    if (state.handle != null && !state.handle.all { it.isDigit() }) {
                        "Connected as @${state.handle}"
                    } else {
                        "Connected"
                    },
                style = MaterialTheme.typography.titleMedium,
            )
            val enabledDesc = if (state.enabled) "enabled" else "disabled"
            Switch(
                checked = state.enabled,
                onCheckedChange = onSetEnabled,
                modifier =
                    Modifier.semantics {
                        contentDescription = "X browsing"
                        stateDescription = enabledDesc
                    },
            )
            Text(
                text = if (state.enabled) "Read-only browsing active" else "Browsing disabled",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Max items per request: ${state.maxItems}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = state.maxItems.toFloat(),
                onValueChange = { onSetMaxItems(it.roundToInt()) },
                valueRange = MIN_MAX_ITEMS.toFloat()..MAX_MAX_ITEMS.toFloat(),
                steps = MAX_MAX_ITEMS - MIN_MAX_ITEMS - 1,
            )
            Text(
                "Timeout: ${state.timeoutSecs} seconds",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = state.timeoutSecs.toFloat(),
                onValueChange = { onSetTimeoutSecs(it.roundToInt()) },
                valueRange = MIN_TIMEOUT.toFloat()..MAX_TIMEOUT.toFloat(),
                steps = MAX_TIMEOUT - MIN_TIMEOUT - 1,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) { Text("Disconnect") }
        }
    }
}
