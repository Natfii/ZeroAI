/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal.theme

/**
 * Terminal color theme defining the 16 ANSI palette colors plus
 * background, foreground, and cursor colors.
 *
 * Colors are stored as packed ARGB (`0xAARRGGBB`). The palette
 * follows the standard ANSI order: 0-7 normal, 8-15 bright.
 *
 * @property name Display name of the theme.
 * @property bgArgb Default background color.
 * @property fgArgb Default foreground color.
 * @property cursorArgb Cursor color, or null to use foreground.
 * @property palette 16-entry ANSI color palette in standard order.
 * @property isDark Whether this is a dark theme (for UI hints).
 */
data class TerminalTheme(
    val name: String,
    val bgArgb: UInt,
    val fgArgb: UInt,
    val cursorArgb: UInt?,
    val palette: List<UInt>,
    val isDark: Boolean,
) {
    init {
        require(palette.size == PALETTE_SIZE) {
            "palette must have exactly $PALETTE_SIZE entries, got ${palette.size}"
        }
    }

    /**
     * Theme constants and utilities.
     */
    companion object {
        /** Standard ANSI palette size (8 normal + 8 bright). */
        const val PALETTE_SIZE = 16
    }
}
