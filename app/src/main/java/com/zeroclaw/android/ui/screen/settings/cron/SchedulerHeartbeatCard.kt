/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.cron

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.ui.component.NumberSettingField
import com.zeroclaw.android.ui.component.SettingsToggleRow

/**
 * Collapsible card exposing the scheduler-loop and heartbeat configuration.
 *
 * Co-located with the cron job list because these knobs (`[scheduler]` and
 * `[heartbeat]` TOML) govern the very loop that runs the jobs below: the
 * scheduler toggle is the master switch — when off, no cron job runs.
 *
 * @param schedulerEnabled Whether the scheduler loop is enabled.
 * @param maxTasks Maximum number of scheduled tasks.
 * @param maxConcurrent Maximum concurrently-running tasks.
 * @param heartbeatEnabled Whether periodic heartbeat ticks are enabled.
 * @param heartbeatIntervalMinutes Heartbeat tick interval in minutes.
 * @param onSchedulerEnabledChange Called when the scheduler toggle changes.
 * @param onMaxTasksChange Called with the parsed max-tasks value.
 * @param onMaxConcurrentChange Called with the parsed max-concurrent value.
 * @param onHeartbeatEnabledChange Called when the heartbeat toggle changes.
 * @param onHeartbeatIntervalChange Called with the parsed interval value.
 * @param modifier Modifier applied to the card.
 */
@Composable
internal fun SchedulerHeartbeatCard(
    schedulerEnabled: Boolean,
    maxTasks: Long,
    maxConcurrent: Long,
    heartbeatEnabled: Boolean,
    heartbeatIntervalMinutes: Long,
    onSchedulerEnabledChange: (Boolean) -> Unit,
    onMaxTasksChange: (Long) -> Unit,
    onMaxConcurrentChange: (Long) -> Unit,
    onHeartbeatEnabledChange: (Boolean) -> Unit,
    onHeartbeatIntervalChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .semantics {
                            contentDescription =
                                if (expanded) {
                                    "Collapse scheduler settings"
                                } else {
                                    "Expand scheduler settings"
                                }
                        },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Scheduler & Heartbeat",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text =
                            if (schedulerEnabled) {
                                "Scheduler on"
                            } else {
                                "Scheduler off — no jobs run"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector =
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsToggleRow(
                        title = "Enable scheduler",
                        subtitle = "Master switch for cron-style scheduled tasks",
                        checked = schedulerEnabled,
                        onCheckedChange = onSchedulerEnabledChange,
                        contentDescription = "Enable task scheduler",
                    )

                    NumberSettingField(
                        value = maxTasks.toString(),
                        onValueChange = { it.toLongOrNull()?.let(onMaxTasksChange) },
                        label = "Max tasks",
                        enabled = schedulerEnabled,
                    )

                    NumberSettingField(
                        value = maxConcurrent.toString(),
                        onValueChange = { it.toLongOrNull()?.let(onMaxConcurrentChange) },
                        label = "Max concurrent",
                        enabled = schedulerEnabled,
                    )

                    SettingsToggleRow(
                        title = "Enable heartbeat",
                        subtitle = "Periodic ticks for keep-alive and monitoring",
                        checked = heartbeatEnabled,
                        onCheckedChange = onHeartbeatEnabledChange,
                        contentDescription = "Enable heartbeat",
                    )

                    NumberSettingField(
                        value = heartbeatIntervalMinutes.toString(),
                        onValueChange = { it.toLongOrNull()?.let(onHeartbeatIntervalChange) },
                        label = "Interval (minutes)",
                        enabled = heartbeatEnabled,
                    )
                }
            }
        }
    }
}
