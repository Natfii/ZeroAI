/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.ThemeMode
import com.zeroclaw.android.ui.component.ContentPane
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.ui.component.SettingsListItem
import com.zeroclaw.android.ui.screen.terminal.theme.TerminalThemePicker

/** Vertical padding around section dividers. */
private val DIVIDER_PADDING = 8.dp

/** Padding between a radio button and its label. */
private val RADIO_LABEL_PADDING = 8.dp

/** Horizontal inset matching the Material 3 ListItem content padding. */
private val LIST_ITEM_INSET = 16.dp

/**
 * Unified appearance screen hosting the app theme and the terminal/shell palette.
 *
 * Co-locates two pre-existing, independently-persisted controls: the app
 * [ThemeMode] (light/dark/system) and the terminal/shell color palette. The
 * terminal palette retains its quick-access shortcut in the terminal tab; this
 * screen is the discoverable home for both.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param viewModel ViewModel exposing the current theme and palette selections.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun AppearanceScreen(
    edgeMargin: Dp,
    viewModel: AppearanceViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val terminalThemeName by viewModel.terminalThemeName.collectAsStateWithLifecycle()
    var showTerminalPicker by remember { mutableStateOf(false) }

    ContentPane(
        modifier =
            modifier
                .padding(horizontal = edgeMargin),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(DIVIDER_PADDING))

            SectionHeader(title = "App theme")
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    ThemeOptionRow(
                        mode = mode,
                        selected = mode == theme,
                        onSelect = { viewModel.updateTheme(mode) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = DIVIDER_PADDING))

            SectionHeader(title = "Terminal & shell theme")
            SettingsListItem(
                icon = Icons.Outlined.Palette,
                title = "Color palette",
                subtitle = terminalThemeName,
                onClick = { showTerminalPicker = true },
            )
            Text(
                text = "Applies to both the terminal and the in-app shell.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        horizontal = LIST_ITEM_INSET,
                        vertical = RADIO_LABEL_PADDING,
                    ),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showTerminalPicker) {
        TerminalThemePicker(
            themes = viewModel.terminalThemes(),
            currentThemeName = terminalThemeName,
            onSelect = { viewModel.selectTerminalTheme(it) },
            onDismiss = { showTerminalPicker = false },
        )
    }
}

/**
 * Single selectable app-theme row with a leading radio button.
 *
 * @param mode The [ThemeMode] this row represents.
 * @param selected Whether this row is the active selection.
 * @param onSelect Called when the row is tapped.
 */
@Composable
private fun ThemeOptionRow(
    mode: ThemeMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val label =
        when (mode) {
            ThemeMode.SYSTEM -> "System default"
            ThemeMode.LIGHT -> "Light"
            ThemeMode.DARK -> "Dark"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onSelect,
                    role = Role.RadioButton,
                ).padding(vertical = RADIO_LABEL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = RADIO_LABEL_PADDING),
        )
    }
}
