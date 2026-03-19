/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Text field for API keys, tokens, and other secrets with paste support.
 *
 * Uses [KeyboardType.Text] instead of [KeyboardType.Password] to avoid
 * triggering Android's secure keyboard policy that blocks clipboard paste.
 * The value is visually masked with [PasswordVisualTransformation] and a
 * reveal/hide toggle icon allows the user to inspect the value.
 *
 * @param value Current field value.
 * @param onValueChange Callback when the value changes.
 * @param label Display label for the field.
 * @param modifier Modifier applied to the text field.
 * @param enabled Whether the field is interactive.
 * @param isError Whether to display the error indicator.
 * @param supportingText Optional supporting text composable.
 * @param imeAction Keyboard IME action (defaults to [ImeAction.Done]).
 */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText,
        visualTransformation =
            if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        trailingIcon = {
            IconButton(
                onClick = { revealed = !revealed },
                modifier =
                    Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription =
                                if (revealed) "Hide $label" else "Show $label"
                        },
            ) {
                Icon(
                    imageVector =
                        if (revealed) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                    contentDescription = null,
                )
            }
        },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = imeAction,
            ),
        modifier =
            modifier.semantics {
                contentDescription = label
            },
    )
}
