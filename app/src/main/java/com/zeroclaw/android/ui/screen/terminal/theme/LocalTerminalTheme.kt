/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition-scoped access to the active [TerminalTheme].
 *
 * Provided once near the root of the terminal screen so that nested REPL
 * composables can match the palette, foreground, and background of the
 * GPU TTY surface without prop-drilling the theme through every layer.
 *
 * Resolves to `null` until the view model has loaded the persisted
 * selection. Consumers MUST fall back to Material colors when the value
 * is `null` so the REPL remains readable before the theme is ready.
 *
 * Backed by [staticCompositionLocalOf] because the active theme changes
 * infrequently (only via the theme picker); a change recomposes every
 * reader, which is acceptable for a deliberate user action.
 */
val LocalTerminalTheme: ProvidableCompositionLocal<TerminalTheme?> =
    staticCompositionLocalOf { null }
