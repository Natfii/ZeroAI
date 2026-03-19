/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Number of milliseconds in one day. */
private const val MILLIS_PER_DAY = 86_400_000L

/** Number of days for the "Last 7 days" option. */
private const val DAYS_7 = 7

/** Number of days for the "Last 30 days" option. */
private const val DAYS_30 = 30

/**
 * Time window option for conversation history access.
 *
 * Each variant represents a preset or custom time boundary that
 * controls how far back the AI agent can read messages.
 *
 * @property label Human-readable label for the radio button.
 */
enum class TimeWindowOption(
    val label: String,
) {
    /** Allow access to the entire conversation history. */
    ALL_HISTORY("All history"),

    /** Restrict access to the last 7 days of messages. */
    LAST_7_DAYS("Last 7 days"),

    /** Restrict access to the last 30 days of messages. */
    LAST_30_DAYS("Last 30 days"),

    /** Allow the user to pick a custom start date. */
    CUSTOM("Custom start date"),
}

/**
 * Bottom sheet for selecting a conversation time window.
 *
 * Presents radio button options for history access duration. The
 * default selection is [TimeWindowOption.LAST_7_DAYS]. On confirmation,
 * invokes the callback with the calculated epoch millis cutoff or null
 * for all history.
 *
 * @param conversationName Display name shown in the sheet title.
 * @param onConfirm Callback with the selected window start in epoch millis,
 *   or null for all history.
 * @param onDismiss Callback when the sheet is dismissed without confirming.
 * @param onRequestDatePicker Callback when the user selects Custom and taps
 *   Confirm; receives the callback to invoke with the picked epoch millis.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationAllowlistSheet(
    conversationName: String,
    onConfirm: (windowStartMs: Long?) -> Unit,
    onDismiss: () -> Unit,
    onRequestDatePicker: ((onDatePicked: (Long) -> Unit) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf(TimeWindowOption.LAST_7_DAYS) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Allow access to: $conversationName",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TimeWindowOption.entries.forEach { option ->
                TimeWindowRow(
                    option = option,
                    isSelected = selected == option,
                    onSelect = { selected = option },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val windowStart =
                        when (selected) {
                            TimeWindowOption.ALL_HISTORY -> null
                            TimeWindowOption.LAST_7_DAYS -> {
                                System.currentTimeMillis() - (DAYS_7 * MILLIS_PER_DAY)
                            }
                            TimeWindowOption.LAST_30_DAYS -> {
                                System.currentTimeMillis() - (DAYS_30 * MILLIS_PER_DAY)
                            }
                            TimeWindowOption.CUSTOM -> {
                                if (onRequestDatePicker != null) {
                                    onRequestDatePicker { epochMs ->
                                        onConfirm(epochMs)
                                    }
                                    return@Button
                                }
                                null
                            }
                        }
                    onConfirm(windowStart)
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics {
                            contentDescription = "Confirm time window selection"
                        },
            ) {
                Text("Confirm")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Single radio button row for a time window option.
 *
 * @param option The [TimeWindowOption] this row represents.
 * @param isSelected Whether this option is currently selected.
 * @param onSelect Callback when this option is tapped.
 */
@Composable
private fun TimeWindowRow(
    option: TimeWindowOption,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics {
                    contentDescription = "${option.label}, ${if (isSelected) "selected" else "not selected"}"
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
        )
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
