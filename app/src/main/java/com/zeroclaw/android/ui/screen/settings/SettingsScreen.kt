/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.navigation.SettingsNavAction
import com.zeroclaw.android.ui.component.ContentPane
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.ui.component.SettingsListItem

/**
 * Root settings screen displaying a sectioned list of configuration options.
 *
 * Thin stateful wrapper that collects ViewModel flows and delegates
 * rendering to [SettingsContent].
 *
 * @param onNavigate Callback invoked with a [SettingsNavAction] when the user taps a setting.
 * @param onRerunWizard Callback to reset onboarding and navigate to the setup wizard.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param settingsViewModel ViewModel providing current settings for dynamic subtitles.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun SettingsScreen(
    onNavigate: (SettingsNavAction) -> Unit,
    onRerunWizard: () -> Unit,
    edgeMargin: Dp,
    settingsViewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    SettingsContent(
        settings = settings,
        edgeMargin = edgeMargin,
        onNavigate = onNavigate,
        onRerunWizard = onRerunWizard,
        modifier = modifier,
    )
}

/**
 * Stateless settings content composable for testing.
 *
 * @param settings Current app settings snapshot.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param onNavigate Callback for settings navigation.
 * @param onRerunWizard Callback to reset onboarding.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
internal fun SettingsContent(
    settings: AppSettings,
    edgeMargin: Dp,
    onNavigate: (SettingsNavAction) -> Unit,
    onRerunWizard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRerunDialog by remember { mutableStateOf(false) }

    ContentPane(
        modifier =
            modifier
                .padding(horizontal = edgeMargin),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SectionHeader(title = "Daemon")
            SettingsListItem(
                icon = Icons.Outlined.Settings,
                title = "Service Configuration",
                subtitle =
                    "${settings.host}:${settings.port}" +
                        if (settings.autoStartOnBoot) " | auto-start" else "",
                onClick = { onNavigate(SettingsNavAction.ServiceConfig) },
            )
            SettingsListItem(
                icon = Icons.Outlined.BatteryAlert,
                title = "Battery Settings",
                subtitle = "Optimization exemptions",
                onClick = { onNavigate(SettingsNavAction.Battery) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(title = "Security")
            SettingsListItem(
                icon = Icons.Outlined.Security,
                title = "Autonomy Level",
                subtitle = settings.autonomyLevel,
                onClick = { onNavigate(SettingsNavAction.Autonomy) },
            )
            SettingsListItem(
                icon = Icons.Outlined.TaskAlt,
                title = "Skill Permissions",
                subtitle = "View and revoke dangerous capability grants",
                onClick = { onNavigate(SettingsNavAction.SkillPermissions) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(title = "Access & Integrations")
            SettingsListItem(
                icon = Icons.Outlined.Badge,
                title = "Provider Logins",
                subtitle = "OAuth sessions for ChatGPT",
                onClick = { onNavigate(SettingsNavAction.ProviderConnections) },
            )
            SettingsListItem(
                icon = Icons.Outlined.Key,
                title = "API Keys",
                subtitle = "Manual keys for provider APIs",
                onClick = { onNavigate(SettingsNavAction.ApiKeys) },
            )
            SettingsListItem(
                icon = Icons.Outlined.Forum,
                title = "Chat Apps",
                subtitle = "Telegram and Discord remote chat endpoints",
                onClick = { onNavigate(SettingsNavAction.Channels) },
            )
            SettingsListItem(
                icon = Icons.Outlined.Key,
                title = "SSH Keys",
                subtitle = "Manage SSH private keys",
                onClick = { onNavigate(SettingsNavAction.SshKeys) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(title = "Configuration")
            SettingsListItem(
                icon = Icons.Outlined.Memory,
                title = "Memory Advanced",
                subtitle = "Hygiene and recall weights",
                onClick = { onNavigate(SettingsNavAction.MemoryAdvanced) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(title = "Diagnostics")
            SettingsListItem(
                icon = Icons.AutoMirrored.Outlined.Subject,
                title = "Log Viewer",
                subtitle = "View daemon and service logs",
                onClick = { onNavigate(SettingsNavAction.LogViewer) },
            )
            SettingsListItem(
                icon = Icons.Outlined.HealthAndSafety,
                title = "ZeroAI Doctor",
                subtitle = "Validate config, keys, and connectivity",
                onClick = { onNavigate(SettingsNavAction.Doctor) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(title = "Inspect & Browse")
            SettingsListItem(
                icon = Icons.Outlined.Psychology,
                title = "Memory Browser",
                subtitle = "Browse and search memory entries",
                onClick = { onNavigate(SettingsNavAction.MemoryBrowser) },
            )
            SettingsListItem(
                icon = Icons.Outlined.Schedule,
                title = "Scheduled Tasks",
                subtitle =
                    if (settings.schedulerEnabled) {
                        "Cron jobs, scheduler, and heartbeat"
                    } else {
                        "Scheduler off — cron jobs paused"
                    },
                onClick = { onNavigate(SettingsNavAction.CronJobs) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(title = "App")
            SettingsListItem(
                icon = Icons.Outlined.Palette,
                title = "Appearance",
                subtitle = "App theme and terminal palette",
                onClick = { onNavigate(SettingsNavAction.Appearance) },
            )
            SettingsListItem(
                icon = Icons.Outlined.Refresh,
                title = "Re-run Setup Wizard",
                subtitle = "Walk through initial configuration again",
                onClick = { showRerunDialog = true },
            )
            SettingsListItem(
                icon = Icons.Outlined.Info,
                title = "About",
                subtitle = "Version, licenses, links",
                onClick = { onNavigate(SettingsNavAction.About) },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showRerunDialog) {
        RerunWizardDialog(
            onConfirm = {
                showRerunDialog = false
                onRerunWizard()
            },
            onDismiss = { showRerunDialog = false },
        )
    }
}

/**
 * Confirmation dialog shown before re-running the setup wizard.
 *
 * @param onConfirm Called when the user confirms.
 * @param onDismiss Called when the user cancels.
 */
@Composable
private fun RerunWizardDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-run Setup Wizard?") },
        text = {
            Text(
                "This will open the initial setup wizard again. " +
                    "Your agent identity (AIEOS) will be cleared so you can " +
                    "generate a fresh one. Provider logins, API keys, chat apps, " +
                    "and other settings are preserved.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
