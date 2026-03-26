/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal.theme

/**
 * Parses Ghostty-format terminal theme files into [TerminalTheme].
 *
 * The format is key=value pairs, one per line:
 * ```
 * background = #1e1e2e
 * foreground = #cdd6f4
 * cursor-color = #f5e0dc
 * palette = 0=#45475a
 * palette = 1=#f38ba8
 * ```
 *
 * Lines starting with `#` are comments. Blank lines are ignored.
 * Both `background` and `foreground` are required; all other fields
 * are optional. Unspecified palette entries use xterm defaults.
 */
object TerminalThemeParser {
    /** Number of hex digits in a 6-digit RGB color string. */
    private const val HEX_COLOR_LENGTH = 6

    /** Radix used for hexadecimal parsing. */
    private const val HEX_RADIX = 16

    /** Alpha channel shifted to the high byte of a packed ARGB color. */
    private const val ALPHA_MASK = 0xFF000000u

    /** Bitmask for a single 8-bit channel. */
    private const val CHANNEL_MASK = 0xFFu

    /** Maximum value of a single 8-bit color channel. */
    private const val CHANNEL_MAX = 255f

    /** BT.601 red luminance weight. */
    private const val LUM_RED = 0.299f

    /** BT.601 green luminance weight. */
    private const val LUM_GREEN = 0.587f

    /** BT.601 blue luminance weight. */
    private const val LUM_BLUE = 0.114f

    /** Luminance threshold above which a color is considered light. */
    private const val LIGHT_THRESHOLD = 0.5f

    /** Bit shift to extract the red channel from a packed ARGB value. */
    private const val RED_SHIFT = 16

    /** Bit shift to extract the green channel from a packed ARGB value. */
    private const val GREEN_SHIFT = 8

    /** Standard xterm 16-color palette defaults (packed ARGB). */
    private val XTERM_DEFAULTS: List<UInt> =
        listOf(
            0xFF000000u,
            0xFFCD0000u,
            0xFF00CD00u,
            0xFFCDCD00u,
            0xFF0000EEu,
            0xFFCD00CDu,
            0xFF00CDCDu,
            0xFFE5E5E5u,
            0xFF7F7F7Fu,
            0xFFFF0000u,
            0xFF00FF00u,
            0xFFFFFF00u,
            0xFF5C5CFFu,
            0xFFFF00FFu,
            0xFF00FFFFu,
            0xFFFFFFFFu,
        )

    /**
     * Parses a Ghostty-format theme string into a [TerminalTheme].
     *
     * @param name Display name for the theme.
     * @param content Raw theme file content.
     * @return Parsed theme, or null if required fields are missing.
     */
    fun parse(
        name: String,
        content: String,
    ): TerminalTheme? {
        val fields = parseFields(content)
        val bg = fields.bg ?: return null
        val fg = fields.fg ?: return null
        return TerminalTheme(
            name = name,
            bgArgb = bg,
            fgArgb = fg,
            cursorArgb = fields.cursor,
            palette = fields.palette,
            isDark = !isLightColor(bg),
        )
    }

    /**
     * Parsed intermediate fields extracted from a theme file.
     *
     * @property bg Packed ARGB background color, or null if absent.
     * @property fg Packed ARGB foreground color, or null if absent.
     * @property cursor Packed ARGB cursor color, or null if absent.
     * @property palette Mutable 16-entry palette list.
     */
    private data class ParsedFields(
        val bg: UInt?,
        val fg: UInt?,
        val cursor: UInt?,
        val palette: MutableList<UInt>,
    )

    /**
     * Iterates over content lines and accumulates parsed fields.
     *
     * @param content Raw theme file content.
     * @return Accumulated [ParsedFields] from all key=value pairs.
     */
    private fun parseFields(content: String): ParsedFields {
        var bg: UInt? = null
        var fg: UInt? = null
        var cursor: UInt? = null
        val palette = XTERM_DEFAULTS.toMutableList()

        for (line in content.lines()) {
            val trimmed = line.trim()
            val eqIndex = trimmed.indexOf('=')
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eqIndex < 0) continue
            val key = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()
            when (key) {
                "background" -> bg = parseHexColor(value)
                "foreground" -> fg = parseHexColor(value)
                "cursor-color" -> cursor = parseHexColor(value)
                "palette" -> applyPaletteEntry(value, palette)
            }
        }

        return ParsedFields(bg = bg, fg = fg, cursor = cursor, palette = palette)
    }

    /**
     * Parses a palette value of the form `index=#RRGGBB` and writes it into [palette].
     *
     * @param value Raw value string from the `palette` key.
     * @param palette Mutable palette list to update in place.
     */
    private fun applyPaletteEntry(
        value: String,
        palette: MutableList<UInt>,
    ) {
        val palEq = value.indexOf('=')
        if (palEq <= 0) return
        val index = value.substring(0, palEq).trim().toIntOrNull() ?: return
        val color = parseHexColor(value.substring(palEq + 1).trim()) ?: return
        if (index in 0 until TerminalTheme.PALETTE_SIZE) {
            palette[index] = color
        }
    }

    /**
     * Parses `#RRGGBB` hex string to packed ARGB `0xFFRRGGBB`.
     *
     * @return Packed ARGB color, or null if the format is invalid.
     */
    private fun parseHexColor(hex: String): UInt? {
        val stripped = hex.removePrefix("#")
        if (stripped.length != HEX_COLOR_LENGTH) return null
        val rgb = stripped.toUIntOrNull(radix = HEX_RADIX) ?: return null
        return ALPHA_MASK or rgb
    }

    /**
     * Determines if a packed ARGB color is perceptually light.
     *
     * Uses the ITU-R BT.601 luminance formula. Colors with luminance
     * above [LIGHT_THRESHOLD] are considered light.
     */
    private fun isLightColor(argb: UInt): Boolean {
        val r = ((argb shr RED_SHIFT) and CHANNEL_MASK).toFloat() / CHANNEL_MAX
        val g = ((argb shr GREEN_SHIFT) and CHANNEL_MASK).toFloat() / CHANNEL_MAX
        val b = (argb and CHANNEL_MASK).toFloat() / CHANNEL_MAX
        val luminance = LUM_RED * r + LUM_GREEN * g + LUM_BLUE * b
        return luminance > LIGHT_THRESHOLD
    }
}
