/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Modal bottom sheet for pasting an Anthropic OAuth authorization code.
 *
 * Anthropic's OAuth flow redirects to a web page that displays the code
 * rather than redirecting to localhost. The user copies the code from the
 * browser and pastes it here. Input is cleaned (whitespace, newlines,
 * quotes stripped) before submission.
 *
 * @param visible Whether the sheet is shown.
 * @param onSubmit Called with the cleaned authorization code.
 * @param onDismiss Called when the user cancels or the sheet is dismissed.
 * @param isLoading Disables input and shows a spinner during token exchange.
 * @param errorMessage Displayed below the text field when non-null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnthropicCodeSheet(
    visible: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rawInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val displayError = errorMessage ?: localError

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .semantics { paneTitle = "Paste Claude authorization code" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Paste your Claude code",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Copy the code from the browser and paste it here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = rawInput,
                onValueChange = {
                    rawInput = it
                    localError = null
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Authorization code input"
                        },
                enabled = !isLoading,
                label = { Text("Authorization code") },
                maxLines = 3,
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                isError = displayError != null,
                supportingText =
                    if (displayError != null) {
                        {
                            Text(
                                text = displayError,
                                modifier =
                                    Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                    },
                            )
                        }
                    } else {
                        null
                    },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val cleaned = cleanCode(rawInput)
                    val validationError = validateCode(cleaned)
                    if (validationError != null) {
                        localError = validationError
                    } else {
                        localError = null
                        onSubmit(cleaned)
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Submit authorization code"
                        },
                enabled = rawInput.isNotBlank() && !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Submit")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Cancel authorization"
                    },
            ) {
                Text("Cancel")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Cleans a pasted authorization code by removing common copy artifacts.
 *
 * Strips leading/trailing whitespace, embedded newlines, carriage returns,
 * non-breaking spaces (U+00A0), and surrounding quotation marks.
 */
private fun cleanCode(raw: String): String {
    var cleaned =
        raw
            .replace("\n", "")
            .replace("\r", "")
            .replace("\u00A0", " ")
            .trim()
    @Suppress("ComplexCondition")
    if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
        (cleaned.startsWith("'") && cleaned.endsWith("'"))
    ) {
        cleaned = cleaned.substring(1, cleaned.length - 1).trim()
    }
    return cleaned
}

/**
 * Validates a cleaned authorization code.
 *
 * @return An error message if invalid, or null if valid.
 */
private fun validateCode(code: String): String? =
    when {
        code.isEmpty() -> "Please paste your authorization code"
        code.startsWith("sk-ant-") ->
            "This looks like an API key or token, not an authorization code"
        else -> null
    }
