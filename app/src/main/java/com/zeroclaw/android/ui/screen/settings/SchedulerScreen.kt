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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.ui.component.SettingsToggleRow

/**
 * Scheduler and heartbeat configuration screen.
 *
 * Maps to upstream `[scheduler]` and `[heartbeat]` TOML sections:
 * task scheduling limits and periodic heartbeat tick configuration.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param settingsViewModel The shared [SettingsViewModel].
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun SchedulerScreen(
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

        SectionHeader(title = "Task Scheduler")

        SettingsToggleRow(
            title = "Enable scheduler",
            subtitle = "Allow cron-style scheduled tasks",
            checked = settings.schedulerEnabled,
            onCheckedChange = { settingsViewModel.updateSchedulerEnabled(it) },
            contentDescription = "Enable task scheduler",
        )

        OutlinedTextField(
            value = settings.schedulerMaxTasks.toString(),
            onValueChange = { v ->
                v.toLongOrNull()?.let { settingsViewModel.updateSchedulerMaxTasks(it) }
            },
            label = { Text("Max tasks") },
            singleLine = true,
            enabled = settings.schedulerEnabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.schedulerMaxConcurrent.toString(),
            onValueChange = { v ->
                v.toLongOrNull()?.let { settingsViewModel.updateSchedulerMaxConcurrent(it) }
            },
            label = { Text("Max concurrent") },
            singleLine = true,
            enabled = settings.schedulerEnabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(title = "Heartbeat")

        SettingsToggleRow(
            title = "Enable heartbeat",
            subtitle = "Periodic heartbeat ticks for keep-alive and monitoring",
            checked = settings.heartbeatEnabled,
            onCheckedChange = { settingsViewModel.updateHeartbeatEnabled(it) },
            contentDescription = "Enable heartbeat",
        )

        OutlinedTextField(
            value = settings.heartbeatIntervalMinutes.toString(),
            onValueChange = { v ->
                v.toLongOrNull()?.let { settingsViewModel.updateHeartbeatIntervalMinutes(it) }
            },
            label = { Text("Interval (minutes)") },
            singleLine = true,
            enabled = settings.heartbeatEnabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
