/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.AnnotatedString

/** Available reasoning-effort choices for OpenAI reasoning models. */
private val ReasoningEffortOptions =
    listOf(
        "auto" to "Auto (model default)",
        "none" to "None",
        "low" to "Low",
        "medium" to "Medium",
        "high" to "High",
        "xhigh" to "XHigh",
    )

/** Default helper text for [ReasoningEffortDropdown]. */
private val DefaultSupportingText =
    AnnotatedString(
        "Auto uses model defaults. Explicit levels currently apply to OpenAI reasoning models.",
    )

/**
 * Dropdown for selecting the global reasoning-effort override.
 *
 * @param selectedEffort Persisted reasoning-effort value.
 * @param onEffortSelected Called when the user picks a new effort value.
 * @param modifier Modifier applied to the dropdown root.
 * @param label Field label shown above the selected value.
 * @param supportingText Optional helper text shown under the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningEffortDropdown(
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Reasoning effort",
    supportingText: AnnotatedString = DefaultSupportingText,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedReasoningLabel =
        ReasoningEffortOptions
            .firstOrNull { it.first == selectedEffort }
            ?.second
            ?: selectedEffort

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedReasoningLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            supportingText = { Text(supportingText) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ReasoningEffortOptions.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onEffortSelected(value)
                        expanded = false
                    },
                )
            }
        }
    }
}
