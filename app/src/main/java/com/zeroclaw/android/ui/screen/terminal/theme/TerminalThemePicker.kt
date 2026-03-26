/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Corner radius for the dialog surface and theme cards. */
private const val DIALOG_CORNER_DP = 16

/** Corner radius for individual theme row cards. */
private const val CARD_CORNER_DP = 12

/** Border width for the selected theme indicator. */
private const val SELECTED_BORDER_DP = 2

/** Size of each color swatch circle. */
private const val SWATCH_SIZE_DP = 16

/** Spacing between color swatch circles. */
private const val SWATCH_SPACING_DP = 4

/** Inner padding for each theme card row. */
private const val CARD_PADDING_DP = 12

/** Outer padding for the dialog surface content. */
private const val DIALOG_PADDING_DP = 16

/** Bottom padding below the dialog title. */
private const val TITLE_BOTTOM_PAD_DP = 16

/** Vertical spacing between theme cards. */
private const val CARD_VERTICAL_SPACING_DP = 4

/** Number of ANSI palette colors shown as swatches per theme. */
private const val SWATCH_COUNT = 8

/**
 * Full-screen dialog showing available terminal color themes.
 *
 * Each theme is displayed as a card with its name and a swatch of its
 * first 8 ANSI palette colors on the theme's background. Tapping a
 * theme invokes [onSelect] and dismisses the dialog. The currently
 * active theme is highlighted with a primary-colored border.
 *
 * @param themes All available themes in display order.
 * @param currentThemeName Name of the currently active theme, or null if none is set.
 * @param onSelect Callback with the selected [TerminalTheme].
 * @param onDismiss Callback to close the dialog without changing the theme.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalThemePicker(
    themes: List<TerminalTheme>,
    currentThemeName: String?,
    onSelect: (TerminalTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(DIALOG_CORNER_DP.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(DIALOG_PADDING_DP.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(DIALOG_PADDING_DP.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Terminal Theme",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = TITLE_BOTTOM_PAD_DP.dp),
                )

                themes.forEach { theme ->
                    val isSelected = theme.name == currentThemeName
                    val borderMod =
                        if (isSelected) {
                            Modifier.border(
                                width = SELECTED_BORDER_DP.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(CARD_CORNER_DP.dp),
                            )
                        } else {
                            Modifier
                        }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            borderMod
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
                                .background(Color(theme.bgArgb.toInt()))
                                .clickable {
                                    onSelect(theme)
                                    onDismiss()
                                }.padding(CARD_PADDING_DP.dp)
                                .semantics {
                                    contentDescription =
                                        if (isSelected) {
                                            "${theme.name} theme, selected"
                                        } else {
                                            "${theme.name} theme"
                                        }
                                },
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = theme.name,
                                color = Color(theme.fgArgb.toInt()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(SWATCH_SPACING_DP.dp),
                        ) {
                            for (i in 0 until SWATCH_COUNT) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(SWATCH_SIZE_DP.dp)
                                            .clip(CircleShape)
                                            .background(Color(theme.palette[i].toInt())),
                                )
                            }
                        }
                    }

                    if (theme != themes.last()) {
                        Box(modifier = Modifier.padding(vertical = CARD_VERTICAL_SPACING_DP.dp))
                    }
                }
            }
        }
    }
}
