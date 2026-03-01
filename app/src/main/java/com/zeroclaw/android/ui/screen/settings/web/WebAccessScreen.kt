/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings.web

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
import androidx.compose.material3.MaterialTheme
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
import com.zeroclaw.android.ui.screen.settings.SettingsViewModel

/** Available web search engine options. */
private val WEB_SEARCH_ENGINES = listOf("duckduckgo", "brave")

/**
 * Web access configuration screen for web fetch, web search, HTTP request, and vision tools.
 *
 * Maps to upstream `[tools.web_fetch]`, `[tools.web_search]`, `[tools.http_request]`,
 * and `[multimodal]` TOML sections.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param settingsViewModel The shared [SettingsViewModel].
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun WebAccessScreen(
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

        WebFetchSection(settings = settings, viewModel = settingsViewModel)
        WebSearchSection(settings = settings, viewModel = settingsViewModel)
        HttpRequestSection(settings = settings, viewModel = settingsViewModel)
        VisionSection(settings = settings, viewModel = settingsViewModel)

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Web fetch tool configuration section.
 *
 * Controls whether the agent can fetch and read web page content, along with
 * domain allowlists, blocklists, response size limits, and timeouts.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 */
@Composable
private fun WebFetchSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Web Fetch")

    SettingsToggleRow(
        title = "Enable web fetch",
        subtitle = "Allow the agent to fetch and read web pages",
        checked = settings.webFetchEnabled,
        onCheckedChange = { viewModel.updateWebFetchEnabled(it) },
        contentDescription = "Enable web fetch tool",
    )

    OutlinedTextField(
        value = settings.webFetchAllowedDomains,
        onValueChange = { viewModel.updateWebFetchAllowedDomains(it) },
        label = { Text("Allowed domains") },
        supportingText = { Text("Comma-separated (empty allows all)") },
        enabled = settings.webFetchEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.webFetchBlockedDomains,
        onValueChange = { viewModel.updateWebFetchBlockedDomains(it) },
        label = { Text("Blocked domains") },
        supportingText = { Text("Comma-separated domains to deny") },
        enabled = settings.webFetchEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.webFetchMaxResponseSize.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateWebFetchMaxResponseSize(it) }
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
            v.toIntOrNull()?.let { viewModel.updateWebFetchTimeoutSecs(it) }
        },
        label = { Text("Timeout (seconds)") },
        singleLine = true,
        enabled = settings.webFetchEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Web search tool configuration section.
 *
 * Controls whether the agent can perform web searches, the search engine
 * provider, Brave API key (when using Brave), max results, and timeouts.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebSearchSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Web Search")

    SettingsToggleRow(
        title = "Enable web search",
        subtitle = "Allow the agent to search the web for information",
        checked = settings.webSearchEnabled,
        onCheckedChange = { viewModel.updateWebSearchEnabled(it) },
        contentDescription = "Enable web search tool",
    )

    var engineExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = engineExpanded,
        onExpandedChange = { engineExpanded = it },
    ) {
        OutlinedTextField(
            value = settings.webSearchProvider,
            onValueChange = {},
            readOnly = true,
            label = { Text("Search engine") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(engineExpanded) },
            enabled = settings.webSearchEnabled,
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = engineExpanded,
            onDismissRequest = { engineExpanded = false },
        ) {
            for (engine in WEB_SEARCH_ENGINES) {
                DropdownMenuItem(
                    text = { Text(engine) },
                    onClick = {
                        viewModel.updateWebSearchProvider(engine)
                        engineExpanded = false
                    },
                )
            }
        }
    }

    if (settings.webSearchProvider == "brave") {
        OutlinedTextField(
            value = settings.webSearchBraveApiKey,
            onValueChange = { viewModel.updateWebSearchBraveApiKey(it) },
            label = { Text("Brave Search API key") },
            supportingText = { Text("Required for Brave search engine") },
            singleLine = true,
            enabled = settings.webSearchEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    OutlinedTextField(
        value = settings.webSearchMaxResults.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateWebSearchMaxResults(it) }
        },
        label = { Text("Max results") },
        supportingText = { Text("Number of search results (1\u201310)") },
        singleLine = true,
        enabled = settings.webSearchEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.webSearchTimeoutSecs.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateWebSearchTimeoutSecs(it) }
        },
        label = { Text("Timeout (seconds)") },
        singleLine = true,
        enabled = settings.webSearchEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * HTTP request tool configuration section.
 *
 * Controls whether the agent can make arbitrary HTTP requests. Uses a
 * deny-by-default model where only explicitly listed domains are accessible.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 */
@Composable
private fun HttpRequestSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "HTTP Request")

    SettingsToggleRow(
        title = "Enable HTTP requests",
        subtitle = "Allow the agent to make arbitrary HTTP requests",
        checked = settings.httpRequestEnabled,
        onCheckedChange = { viewModel.updateHttpRequestEnabled(it) },
        contentDescription = "Enable HTTP request tool",
    )

    OutlinedTextField(
        value = settings.httpRequestAllowedDomains,
        onValueChange = { viewModel.updateHttpRequestAllowedDomains(it) },
        label = { Text("Allowed domains") },
        supportingText = { Text("Comma-separated (required, deny-by-default)") },
        enabled = settings.httpRequestEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text =
            "HTTP requests use a deny-by-default policy. Only domains listed " +
                "above will be accessible. Leave empty to block all requests.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Vision / multimodal configuration section.
 *
 * Controls image limits and remote fetch behaviour for multimodal
 * messages. Maps to upstream `[multimodal]` TOML section.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 */
@Composable
private fun VisionSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Vision")

    OutlinedTextField(
        value = settings.multimodalMaxImages.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateMultimodalMaxImages(it) }
        },
        label = { Text("Max images per request") },
        supportingText = { Text("Number of images allowed (1\u201316)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.multimodalMaxImageSizeMb.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateMultimodalMaxImageSizeMb(it) }
        },
        label = { Text("Max image size (MB)") },
        supportingText = { Text("Maximum file size per image (1\u201320)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    SettingsToggleRow(
        title = "Allow remote fetch",
        subtitle = "Let the agent download images from remote URLs for vision",
        checked = settings.multimodalAllowRemoteFetch,
        onCheckedChange = { viewModel.updateMultimodalAllowRemoteFetch(it) },
        contentDescription = "Allow remote image fetch for vision",
    )
}
