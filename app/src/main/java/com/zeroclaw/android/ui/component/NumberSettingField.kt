/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType

/**
 * Single-line numeric settings field that standardizes field chrome and accessibility.
 *
 * Renders an [OutlinedTextField] configured for numeric entry with an optional suffix and
 * supporting text. The raw [value] string is forwarded verbatim through [onValueChange]; this
 * component performs no parsing or coercion. Parsing and coercion stay with the caller so each
 * settings field keeps its own exact semantics (Int vs Long vs Float, coerce vs
 * bounds-with-[isError] vs plain), while this component only standardizes the visual chrome and
 * accessibility wiring.
 *
 * @param value Current raw text value of the field.
 * @param onValueChange Callback invoked with the raw, unparsed text whenever the user edits it.
 * @param label Text label rendered for the field.
 * @param modifier Modifier applied to the field; width is always filled and semantics are added.
 * @param enabled Whether the field accepts input.
 * @param isError Whether the field is in an error state, styling it accordingly.
 * @param supportingText Optional helper or error text rendered beneath the field.
 * @param suffix Optional trailing suffix rendered inside the field (for example a unit).
 * @param keyboardType Soft-keyboard type used for input, defaulting to [KeyboardType.Number].
 * @param contentDescription Accessibility description, defaulting to "<label> input".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    suffix: String? = null,
    keyboardType: KeyboardType = KeyboardType.Number,
    contentDescription: String = "$label input",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { this.contentDescription = contentDescription },
    )
}
