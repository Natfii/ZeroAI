/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.ModelListFormat
import com.zeroclaw.android.model.ProviderInfo
import com.zeroclaw.android.util.LocalPowerSaveMode

/** Size of the loading spinner shown while fetching live models. */
private const val SPINNER_SIZE_DP = 20

/** Stroke width of the loading spinner. */
private const val SPINNER_STROKE_DP = 2

/**
 * Editable text field with dropdown suggestions for model name entry.
 *
 * Suggestions are model IDs fetched live from the provider's API — there
 * are no static fallback lists, so the dropdown never shows stale model
 * names. While the field text exactly matches one of the suggestions, the
 * full list is shown so that a selected model does not filter the dropdown
 * down to itself; substring filtering applies only while typing a query.
 *
 * @param value Current text value.
 * @param onValueChanged Callback invoked when text changes or a suggestion is selected.
 * @param suggestions Model IDs fetched live from the provider API.
 * @param modifier Modifier applied to the root layout.
 * @param label Text label for the field.
 * @param isLoading Whether live model data is currently being fetched.
 * @param emptyHint Supporting text shown when no suggestions are loaded,
 *   typically built with [modelSuggestionEmptyHint].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSuggestionField(
    value: String,
    onValueChanged: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    label: String = "Model",
    isLoading: Boolean = false,
    emptyHint: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    val filteredSuggestions =
        remember(value, suggestions) {
            val exactSelection = suggestions.any { it.equals(value, ignoreCase = true) }
            if (value.isBlank() || exactSelection) {
                suggestions
            } else {
                val query = value.lowercase()
                suggestions.filter { it.lowercase().contains(query) }
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded && filteredSuggestions.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChanged(it)
                expanded = true
            },
            label = { Text(label) },
            placeholder = { Text("Select or type a model") },
            trailingIcon = {
                if (isLoading) {
                    if (LocalPowerSaveMode.current) {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(SPINNER_SIZE_DP.dp),
                            strokeWidth = SPINNER_STROKE_DP.dp,
                        )
                    }
                } else if (suggestions.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            supportingText =
                if (isLoading) {
                    { Text("Fetching models…") }
                } else if (suggestions.isEmpty() && emptyHint != null) {
                    { Text(emptyHint) }
                } else {
                    null
                },
            keyboardOptions = KeyboardOptions(autoCorrect = false),
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .semantics { contentDescription = "$label field with suggestions" },
        )

        if (filteredSuggestions.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                filteredSuggestions.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            onValueChanged(model)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Builds the supporting hint for [ModelSuggestionField] when no live
 * models have been loaded for the selected provider.
 *
 * @param providerInfo Registry metadata for the selected provider, null when unknown.
 * @param hasCredential Whether a credential sufficient to fetch models has been entered.
 * @return Hint text, or null when the provider has no model listing endpoint.
 */
fun modelSuggestionEmptyHint(
    providerInfo: ProviderInfo?,
    hasCredential: Boolean,
): String? =
    when {
        providerInfo == null -> null
        providerInfo.modelListFormat == ModelListFormat.NONE -> null
        providerInfo.modelListRequiresKey && !hasCredential ->
            "Enter an API key to load available models"
        else -> "No models loaded yet — type a model ID or check the connection"
    }
