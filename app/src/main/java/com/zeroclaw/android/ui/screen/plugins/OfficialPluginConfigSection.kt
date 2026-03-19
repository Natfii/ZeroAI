/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.ui.component.SecretTextField
import com.zeroclaw.android.ui.component.SettingsToggleRow
import com.zeroclaw.android.ui.screen.settings.SettingsViewModel

/** Available web search engine options. */
private val WEB_SEARCH_ENGINES = listOf("auto", "brave", "google")

/**
 * Renders a purpose-built configuration form for an official plugin.
 *
 * Dispatches to a per-plugin section composable based on [officialPluginId].
 * Each section reads from [settings] and writes changes via [viewModel],
 * mirroring the fields previously found in `WebAccessScreen` and
 * `ToolManagementScreen`.
 *
 * @param officialPluginId One of the [OfficialPlugins] constant IDs.
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun OfficialPluginConfigSection(
    officialPluginId: String,
    settings: AppSettings,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        when (officialPluginId) {
            OfficialPlugins.WEB_SEARCH -> WebSearchConfig(settings, viewModel)
            OfficialPlugins.WEB_FETCH -> WebFetchConfig(settings, viewModel)
            OfficialPlugins.HTTP_REQUEST -> HttpRequestConfig(settings, viewModel)
            OfficialPlugins.COMPOSIO -> ComposioConfig(settings, viewModel)
            OfficialPlugins.SHARED_FOLDER -> SharedFolderConfig(settings, viewModel)
            OfficialPlugins.VISION -> VisionConfig(settings, viewModel)
            OfficialPlugins.TRANSCRIPTION -> TranscriptionConfig(settings, viewModel)
            OfficialPlugins.QUERY_CLASSIFICATION -> QueryClassificationConfig(settings, viewModel)
        }
    }
}

/**
 * Web search plugin configuration.
 *
 * Controls the search engine provider, max results, and timeout.
 * Maps to upstream `[tools.web_search]` TOML section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebSearchConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
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

    OutlinedTextField(
        value = settings.webSearchMaxResults.toString(),
        onValueChange = { v ->
            v.toLongOrNull()?.let { viewModel.updateWebSearchMaxResults(it) }
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
            v.toLongOrNull()?.let { viewModel.updateWebSearchTimeoutSecs(it) }
        },
        label = { Text("Timeout (seconds)") },
        singleLine = true,
        enabled = settings.webSearchEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.webSearchBraveApiKey.ifBlank { "" },
        onValueChange = { viewModel.updateWebSearchBraveApiKey(it) },
        label = { Text("Brave API key") },
        supportingText = { Text("Brave Search API subscription token") },
        singleLine = true,
        enabled = settings.webSearchEnabled,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.webSearchGoogleApiKey.ifBlank { "" },
        onValueChange = { viewModel.updateWebSearchGoogleApiKey(it) },
        label = { Text("Google API key") },
        supportingText = { Text("Google Custom Search API key") },
        singleLine = true,
        enabled = settings.webSearchEnabled,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.webSearchGoogleCx,
        onValueChange = { viewModel.updateWebSearchGoogleCx(it) },
        label = { Text("Google Search Engine ID") },
        supportingText = { Text("Custom Search Engine ID (cx)") },
        singleLine = true,
        enabled = settings.webSearchEnabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Web fetch plugin configuration.
 *
 * Controls domain allowlists, blocklists, response size limits, and
 * timeouts. Maps to upstream `[tools.web_fetch]` TOML section.
 */
@Composable
private fun WebFetchConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
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
            v.toLongOrNull()?.let { viewModel.updateWebFetchMaxResponseSize(it) }
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
            v.toLongOrNull()?.let { viewModel.updateWebFetchTimeoutSecs(it) }
        },
        label = { Text("Timeout (seconds)") },
        singleLine = true,
        enabled = settings.webFetchEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * HTTP request plugin configuration.
 *
 * Controls domain allowlists, response size limits, and timeouts.
 * Uses a deny-by-default policy. Maps to upstream `[tools.http_request]`
 * TOML section.
 */
@Composable
private fun HttpRequestConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    OutlinedTextField(
        value = settings.httpRequestAllowedDomains,
        onValueChange = { viewModel.updateHttpRequestAllowedDomains(it) },
        label = { Text("Allowed domains") },
        supportingText = { Text("Comma-separated (required, deny-by-default)") },
        enabled = settings.httpRequestEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.httpRequestMaxResponseSize.toString(),
        onValueChange = { v ->
            v.toLongOrNull()?.let { viewModel.updateHttpRequestMaxResponseSize(it) }
        },
        label = { Text("Max response size (bytes)") },
        singleLine = true,
        enabled = settings.httpRequestEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.httpRequestTimeoutSecs.toString(),
        onValueChange = { v ->
            v.toLongOrNull()?.let { viewModel.updateHttpRequestTimeoutSecs(it) }
        },
        label = { Text("Timeout (seconds)") },
        singleLine = true,
        enabled = settings.httpRequestEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

    if (settings.httpRequestEnabled && settings.httpRequestAllowedDomains.isBlank()) {
        Text(
            text = "No allowed domains configured \u2014 HTTP requests will be rejected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Composio integration plugin configuration.
 *
 * Controls the API key and entity ID for third-party tool integrations
 * via Composio. Maps to upstream `[composio]` TOML section.
 */
@Composable
private fun ComposioConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SecretTextField(
        value = settings.composioApiKey,
        onValueChange = { viewModel.updateComposioApiKey(it) },
        label = "API key",
        enabled = settings.composioEnabled,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.composioEntityId,
        onValueChange = { viewModel.updateComposioEntityId(it) },
        label = { Text("Entity ID") },
        singleLine = true,
        enabled = settings.composioEnabled,
        modifier = Modifier.fillMaxWidth(),
    )

    if (settings.composioEnabled && settings.composioApiKey.isBlank()) {
        Text(
            text = "Composio requires an API key",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Shared folder plugin configuration.
 *
 * Shows the selected folder display name, a "Change Folder" button
 * that launches the SAF tree picker, and folder status. The
 * [ActivityResultLauncher][androidx.activity.result.ActivityResultLauncher]
 * is registered internally via [rememberLauncherForActivityResult].
 *
 * Follows Dolphin Emulator's URI canonicalization pattern: the URI
 * is canonicalized via [android.content.ContentResolver.canonicalize] before
 * persisting, producing a stable identifier across provider quirks.
 */
@Composable
private fun SharedFolderConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current

    val folderPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val takeFlags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                val canonicalized = context.contentResolver.canonicalize(uri) ?: uri
                viewModel.updateSharedFolderUri(canonicalized.toString())
            }
        }

    if (settings.sharedFolderUri.isNotBlank()) {
        val displayName =
            remember(settings.sharedFolderUri) {
                getSharedFolderDisplayName(context, Uri.parse(settings.sharedFolderUri))
            }

        OutlinedTextField(
            value = displayName ?: "Unknown folder",
            onValueChange = {},
            readOnly = true,
            label = { Text("Selected folder") },
            enabled = settings.sharedFolderEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Button(
        onClick = { folderPickerLauncher.launch(null) },
        enabled = settings.sharedFolderEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (settings.sharedFolderUri.isBlank()) "Choose Folder" else "Change Folder",
        )
    }

    if (settings.sharedFolderEnabled && settings.sharedFolderUri.isBlank()) {
        Text(
            text = "No folder selected \u2014 tap Choose Folder to pick one",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Extracts the display name from a SAF document URI.
 *
 * @return The folder display name, or null if the URI is stale.
 */
private fun getSharedFolderDisplayName(
    context: Context,
    uri: Uri,
): String? {
    try {
        val docUri =
            DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri),
            )
        context.contentResolver
            .query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
    } catch (_: Exception) {
        // URI is stale or permission revoked
    }
    return null
}

/**
 * Vision / multimodal plugin configuration.
 *
 * Controls image limits and remote fetch behaviour. Maps to upstream
 * `[multimodal]` TOML section.
 */
@Composable
private fun VisionConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
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

/**
 * Transcription plugin configuration.
 *
 * Controls the Whisper-compatible API endpoint, model, language, and
 * max duration. Maps to upstream `[transcription]` TOML section.
 */
@Composable
private fun TranscriptionConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    OutlinedTextField(
        value = settings.transcriptionApiUrl,
        onValueChange = { viewModel.updateTranscriptionApiUrl(it) },
        label = { Text("API URL") },
        supportingText = { Text("Whisper-compatible transcription endpoint") },
        singleLine = true,
        enabled = settings.transcriptionEnabled,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.transcriptionModel,
        onValueChange = { viewModel.updateTranscriptionModel(it) },
        label = { Text("Model") },
        supportingText = { Text("Transcription model name") },
        singleLine = true,
        enabled = settings.transcriptionEnabled,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.transcriptionLanguage,
        onValueChange = { viewModel.updateTranscriptionLanguage(it) },
        label = { Text("Language hint") },
        supportingText = { Text("ISO 639-1 code (e.g. \"en\", \"es\") or blank for auto-detect") },
        singleLine = true,
        enabled = settings.transcriptionEnabled,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.transcriptionMaxDurationSecs.toString(),
        onValueChange = { v ->
            v.toLongOrNull()?.let { viewModel.updateTranscriptionMaxDurationSecs(it) }
        },
        label = { Text("Max duration (seconds)") },
        supportingText = { Text("Maximum audio clip length to transcribe") },
        singleLine = true,
        enabled = settings.transcriptionEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Query classification plugin configuration.
 *
 * This plugin has no additional configuration beyond the enable toggle
 * (handled by the parent screen). Shows a brief description of the
 * feature.
 */
@Composable
private fun QueryClassificationConfig(
    @Suppress("UNUSED_PARAMETER") settings: AppSettings,
    @Suppress("UNUSED_PARAMETER") viewModel: SettingsViewModel,
) {
    Text(
        text =
            "Query classification analyses incoming messages to route them to " +
                "the most appropriate model. No additional configuration is required.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
