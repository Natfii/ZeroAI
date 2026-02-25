/*
 * Copyright 2026 ZeroClaw Community
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.ui.component.SettingsToggleRow

/** Available sandbox enabled states (tri-state: auto, true, false). */
private val SANDBOX_ENABLED_OPTIONS = listOf("" to "Auto-detect", "true" to "Enabled", "false" to "Disabled")

/** Available sandbox backend options. */
private val SANDBOX_BACKENDS = listOf("auto", "landlock", "firejail", "bubblewrap", "docker", "none")

/** Available OTP method options. */
private val OTP_METHODS = listOf("totp", "hotp")

/**
 * Advanced security configuration screen for sandbox, resources, audit, OTP, and e-stop.
 *
 * Maps to upstream `[security.sandbox]`, `[security.resources]`, `[security.audit]`,
 * `[security.otp]`, and `[security.estop]` TOML sections.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param settingsViewModel The shared [SettingsViewModel].
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun SecurityAdvancedScreen(
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

        SandboxSection(settings = settings, viewModel = settingsViewModel)
        ResourcesSection(settings = settings, viewModel = settingsViewModel)
        AuditSection(settings = settings, viewModel = settingsViewModel)
        OtpSection(settings = settings, viewModel = settingsViewModel)
        EstopSection(settings = settings, viewModel = settingsViewModel)

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Sandbox configuration section with tri-state enabled toggle and backend selector.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SandboxSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Sandbox")

    var enabledExpanded by remember { mutableStateOf(false) }
    val currentLabel = SANDBOX_ENABLED_OPTIONS.firstOrNull { it.first == settings.securitySandboxEnabled }?.second ?: "Auto-detect"

    ExposedDropdownMenuBox(
        expanded = enabledExpanded,
        onExpandedChange = { enabledExpanded = it },
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Sandboxing") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(enabledExpanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = enabledExpanded,
            onDismissRequest = { enabledExpanded = false },
        ) {
            for ((value, label) in SANDBOX_ENABLED_OPTIONS) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        viewModel.updateSecuritySandboxEnabled(value)
                        enabledExpanded = false
                    },
                )
            }
        }
    }

    var backendExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = backendExpanded,
        onExpandedChange = { backendExpanded = it },
    ) {
        OutlinedTextField(
            value = settings.securitySandboxBackend,
            onValueChange = {},
            readOnly = true,
            label = { Text("Backend") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(backendExpanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = backendExpanded,
            onDismissRequest = { backendExpanded = false },
        ) {
            for (backend in SANDBOX_BACKENDS) {
                DropdownMenuItem(
                    text = { Text(backend) },
                    onClick = {
                        viewModel.updateSecuritySandboxBackend(backend)
                        backendExpanded = false
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = settings.securitySandboxFirejailArgs,
        onValueChange = { viewModel.updateSecuritySandboxFirejailArgs(it) },
        label = { Text("Firejail extra arguments") },
        supportingText = { Text("Comma-separated") },
        enabled = settings.securitySandboxBackend == "firejail",
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Resource limits configuration section.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 */
@Composable
private fun ResourcesSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Resource Limits")

    OutlinedTextField(
        value = settings.securityResourcesMaxMemoryMb.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateSecurityResourcesMaxMemoryMb(it) }
        },
        label = { Text("Max memory (MB)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.securityResourcesMaxCpuTimeSecs.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateSecurityResourcesMaxCpuTimeSecs(it) }
        },
        label = { Text("Max CPU time (seconds)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.securityResourcesMaxSubprocesses.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateSecurityResourcesMaxSubprocesses(it) }
        },
        label = { Text("Max subprocesses") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    SettingsToggleRow(
        title = "Memory monitoring",
        subtitle = "Track memory usage of sandboxed processes",
        checked = settings.securityResourcesMemoryMonitoring,
        onCheckedChange = { viewModel.updateSecurityResourcesMemoryMonitoring(it) },
        contentDescription = "Enable memory monitoring",
    )
}

/**
 * Security audit logging toggle.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 */
@Composable
private fun AuditSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Audit Logging")

    SettingsToggleRow(
        title = "Enable audit log",
        subtitle = "Record security-relevant events for review",
        checked = settings.securityAuditEnabled,
        onCheckedChange = { viewModel.updateSecurityAuditEnabled(it) },
        contentDescription = "Enable security audit logging",
    )
}

/**
 * One-time password (OTP) configuration section.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtpSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "One-Time Password (OTP)")

    SettingsToggleRow(
        title = "Enable OTP",
        subtitle = "Require OTP verification for gated actions",
        checked = settings.securityOtpEnabled,
        onCheckedChange = { viewModel.updateSecurityOtpEnabled(it) },
        contentDescription = "Enable OTP verification",
    )

    var methodExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = methodExpanded,
        onExpandedChange = { methodExpanded = it },
    ) {
        OutlinedTextField(
            value = settings.securityOtpMethod,
            onValueChange = {},
            readOnly = true,
            label = { Text("OTP method") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(methodExpanded) },
            enabled = settings.securityOtpEnabled,
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = methodExpanded,
            onDismissRequest = { methodExpanded = false },
        ) {
            for (method in OTP_METHODS) {
                DropdownMenuItem(
                    text = { Text(method) },
                    onClick = {
                        viewModel.updateSecurityOtpMethod(method)
                        methodExpanded = false
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = settings.securityOtpTokenTtlSecs.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateSecurityOtpTokenTtlSecs(it) }
        },
        label = { Text("Token TTL (seconds)") },
        singleLine = true,
        enabled = settings.securityOtpEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.securityOtpCacheValidSecs.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateSecurityOtpCacheValidSecs(it) }
        },
        label = { Text("Cache validity (seconds)") },
        singleLine = true,
        enabled = settings.securityOtpEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.securityOtpGatedActions,
        onValueChange = { viewModel.updateSecurityOtpGatedActions(it) },
        label = { Text("Gated actions") },
        supportingText = { Text("Comma-separated (e.g. shell, file_write, browser)") },
        enabled = settings.securityOtpEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.securityOtpGatedDomains,
        onValueChange = { viewModel.updateSecurityOtpGatedDomains(it) },
        label = { Text("Gated domains") },
        supportingText = { Text("Comma-separated domain names") },
        enabled = settings.securityOtpEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.securityOtpGatedDomainCategories,
        onValueChange = { viewModel.updateSecurityOtpGatedDomainCategories(it) },
        label = { Text("Gated domain categories") },
        supportingText = { Text("Comma-separated categories") },
        enabled = settings.securityOtpEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Emergency stop (e-stop) configuration section.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 */
@Composable
private fun EstopSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Emergency Stop")

    SettingsToggleRow(
        title = "Enable e-stop",
        subtitle = "Allow emergency shutdown of all agent operations",
        checked = settings.securityEstopEnabled,
        onCheckedChange = { viewModel.updateSecurityEstopEnabled(it) },
        contentDescription = "Enable emergency stop",
    )

    SettingsToggleRow(
        title = "Require OTP to resume",
        subtitle = "After triggering e-stop, require OTP to restart",
        checked = settings.securityEstopRequireOtpToResume,
        onCheckedChange = { viewModel.updateSecurityEstopRequireOtpToResume(it) },
        enabled = settings.securityEstopEnabled,
        contentDescription = "Require OTP to resume after e-stop",
    )
}
