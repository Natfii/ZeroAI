/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Minimum touch target height for the slider track area. */
private val SLIDER_MIN_TOUCH_TARGET = 48.dp

/**
 * Integer-stepped slider settings field that standardizes chrome and accessibility.
 *
 * Renders a label row with the current value alongside a Material 3 [Slider]
 * snapped to whole-number steps across [valueRange]. The component mirrors
 * [NumberSettingField]'s role for bounded numeric settings where a slider is
 * a better fit than free-text entry: the caller supplies the current [value]
 * and receives each snapped integer through [onValueChange], keeping parsing
 * and persistence semantics with the caller.
 *
 * Accessibility: the slider carries a content description naming the setting
 * and a state description announcing the current value (formatted via
 * [valueLabel]), so screen readers announce both name and value. The touch
 * target is at least 48dp tall.
 *
 * @param value Current integer value of the setting.
 * @param onValueChange Callback invoked with the snapped integer value as the user drags.
 * @param label Text label rendered above the slider and used in the content description.
 * @param valueRange Inclusive integer range the slider covers.
 * @param modifier Modifier applied to the root column; width is always filled.
 * @param enabled Whether the slider accepts input.
 * @param supportingText Optional helper text rendered beneath the slider.
 * @param valueLabel Formats the current value for display and announcement,
 *   defaulting to the plain number (for example append a unit suffix).
 */
@Composable
fun SliderSettingField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
    valueLabel: (Int) -> String = { it.toString() },
) {
    val displayedValue = valueLabel(value)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = displayedValue,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
            enabled = enabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SLIDER_MIN_TOUCH_TARGET)
                    .semantics {
                        contentDescription = "$label slider"
                        stateDescription = displayedValue
                    },
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
