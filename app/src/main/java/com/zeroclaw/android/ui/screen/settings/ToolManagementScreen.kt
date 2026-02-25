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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.ui.component.SettingsToggleRow

/**
 * Tool management screen for configuring built-in tool integrations.
 *
 * Maps to upstream `[browser]`, `[http_request]`, `[composio]`, `[web_fetch]`, and `[web_search]`
 * TOML sections. Each tool can be toggled on/off with domain
 * allowlists where applicable.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param settingsViewModel The shared [SettingsViewModel].
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ToolManagementScreen(
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

        SectionHeader(title = "Browser Tool")

        SettingsToggleRow(
            title = "Enable browser",
            subtitle = "Allow the agent to browse web pages",
            checked = settings.browserEnabled,
            onCheckedChange = { settingsViewModel.updateBrowserEnabled(it) },
            contentDescription = "Enable browser tool",
        )

        OutlinedTextField(
            value = settings.browserAllowedDomains,
            onValueChange = { settingsViewModel.updateBrowserAllowedDomains(it) },
            label = { Text("Allowed domains") },
            supportingText = { Text("Comma-separated (empty = all domains)") },
            enabled = settings.browserEnabled,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(title = "HTTP Request Tool")

        SettingsToggleRow(
            title = "Enable HTTP requests",
            subtitle = "Allow the agent to make HTTP calls to external APIs",
            checked = settings.httpRequestEnabled,
            onCheckedChange = { settingsViewModel.updateHttpRequestEnabled(it) },
            contentDescription = "Enable HTTP request tool",
        )

        OutlinedTextField(
            value = settings.httpRequestAllowedDomains,
            onValueChange = { settingsViewModel.updateHttpRequestAllowedDomains(it) },
            label = { Text("Allowed domains") },
            supportingText = { Text("Comma-separated (empty = all domains)") },
            enabled = settings.httpRequestEnabled,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(title = "Composio Integration")

        SettingsToggleRow(
            title = "Enable Composio",
            subtitle = "Connect to Composio for third-party tool integrations",
            checked = settings.composioEnabled,
            onCheckedChange = { settingsViewModel.updateComposioEnabled(it) },
            contentDescription = "Enable Composio",
        )

        OutlinedTextField(
            value = settings.composioApiKey,
            onValueChange = { settingsViewModel.updateComposioApiKey(it) },
            label = { Text("API key") },
            singleLine = true,
            enabled = settings.composioEnabled,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.composioEntityId,
            onValueChange = { settingsViewModel.updateComposioEntityId(it) },
            label = { Text("Entity ID") },
            singleLine = true,
            enabled = settings.composioEnabled,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(title = "Web Fetch Tool")

        SettingsToggleRow(
            title = "Enable web fetch",
            subtitle = "Allow the agent to fetch content from URLs",
            checked = settings.webFetchEnabled,
            onCheckedChange = { settingsViewModel.updateWebFetchEnabled(it) },
            contentDescription = "Enable web fetch tool",
        )

        OutlinedTextField(
            value = settings.webFetchAllowedDomains,
            onValueChange = { settingsViewModel.updateWebFetchAllowedDomains(it) },
            label = { Text("Allowed domains") },
            supportingText = { Text("Comma-separated (empty = all domains)") },
            enabled = settings.webFetchEnabled,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.webFetchBlockedDomains,
            onValueChange = { settingsViewModel.updateWebFetchBlockedDomains(it) },
            label = { Text("Blocked domains") },
            supportingText = { Text("Comma-separated") },
            enabled = settings.webFetchEnabled,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.webFetchMaxResponseSize.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { settingsViewModel.updateWebFetchMaxResponseSize(it) }
            },
            label = { Text("Max response size (bytes)") },
            singleLine = true,
            enabled = settings.webFetchEnabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.webFetchTimeoutSecs.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { settingsViewModel.updateWebFetchTimeoutSecs(it) }
            },
            label = { Text("Timeout (seconds)") },
            singleLine = true,
            enabled = settings.webFetchEnabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(title = "Web Search Tool")

        SettingsToggleRow(
            title = "Enable web search",
            subtitle = "Allow the agent to search the web",
            checked = settings.webSearchEnabled,
            onCheckedChange = { settingsViewModel.updateWebSearchEnabled(it) },
            contentDescription = "Enable web search tool",
        )

        OutlinedTextField(
            value = settings.webSearchProvider,
            onValueChange = { settingsViewModel.updateWebSearchProvider(it) },
            label = { Text("Search provider") },
            supportingText = { Text("duckduckgo or brave") },
            singleLine = true,
            enabled = settings.webSearchEnabled,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.webSearchBraveApiKey,
            onValueChange = { settingsViewModel.updateWebSearchBraveApiKey(it) },
            label = { Text("Brave API key") },
            singleLine = true,
            enabled = settings.webSearchEnabled,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.webSearchMaxResults.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { settingsViewModel.updateWebSearchMaxResults(it) }
            },
            label = { Text("Max results") },
            singleLine = true,
            enabled = settings.webSearchEnabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = settings.webSearchTimeoutSecs.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { settingsViewModel.updateWebSearchTimeoutSecs(it) }
            },
            label = { Text("Timeout (seconds)") },
            singleLine = true,
            enabled = settings.webSearchEnabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(title = "Query Classification")

        SettingsToggleRow(
            title = "Enable query classification",
            subtitle = "Classify queries to route them to the best model",
            checked = settings.queryClassificationEnabled,
            onCheckedChange = { settingsViewModel.updateQueryClassificationEnabled(it) },
            contentDescription = "Enable query classification",
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
