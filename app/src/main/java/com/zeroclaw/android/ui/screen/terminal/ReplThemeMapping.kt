/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.zeroclaw.android.ui.screen.terminal.theme.LocalTerminalTheme
import com.zeroclaw.android.ui.screen.terminal.theme.TerminalTheme

/** Opaque-alpha mask applied to packed ARGB values before display. */
private const val OPAQUE_ALPHA = 0xFF000000.toInt()

/** ANSI palette index for the normal blue used by the input prompt. */
private const val ANSI_BLUE = 4

/** ANSI palette index for the normal red used by error text. */
private const val ANSI_RED = 1

/** ANSI palette index for bright black (dim gray) used by secondary text. */
private const val ANSI_BRIGHT_BLACK = 8

/**
 * Semantic role of a REPL scrollback element, used to resolve a color
 * from the active [TerminalTheme] palette.
 *
 * Each role maps to a fixed ANSI palette index (or the theme foreground)
 * so that REPL blocks render with the same colors as the GPU TTY surface
 * instead of the Material color scheme.
 */
enum class BlockRole {
    /** The leading `> ` prompt glyph on a user input line. */
    INPUT_PROMPT,

    /** The user-typed text of an input line. */
    INPUT_TEXT,

    /** Normal agent/command response text. */
    RESPONSE,

    /** Error text. */
    ERROR,

    /** Dimmed system/status text. */
    SYSTEM,

    /** Secondary text inside structured output (JSON, image labels, hints). */
    STRUCTURED,
}

/**
 * Resolves the display [Color] for a REPL [role] from this theme.
 *
 * Uses the theme foreground for primary text and fixed ANSI palette
 * indices for accents (blue prompt, red error, dim gray secondary). The
 * returned color is forced opaque so text never renders invisible if a
 * palette entry lacks an alpha channel.
 *
 * @param role Semantic role of the element being colored.
 * @return Opaque ARGB color drawn from the theme palette or foreground.
 */
fun TerminalTheme.colorForRole(role: BlockRole): Color =
    when (role) {
        BlockRole.INPUT_PROMPT -> paletteColor(ANSI_BLUE)
        BlockRole.INPUT_TEXT -> fgArgb.toOpaqueColor()
        BlockRole.RESPONSE -> fgArgb.toOpaqueColor()
        BlockRole.ERROR -> paletteColor(ANSI_RED)
        BlockRole.SYSTEM -> paletteColor(ANSI_BRIGHT_BLACK)
        BlockRole.STRUCTURED -> paletteColor(ANSI_BRIGHT_BLACK)
    }

/**
 * Background [Color] for the REPL scrollback surface, matching the TTY
 * default background so both surfaces share one canvas color.
 */
fun TerminalTheme.replBackground(): Color = bgArgb.toOpaqueColor()

/**
 * Border/divider [Color] for REPL containers (structured output, code
 * fallbacks), drawn from the dim bright-black palette entry.
 */
fun TerminalTheme.replBorder(): Color = paletteColor(ANSI_BRIGHT_BLACK)

/**
 * Returns the opaque [Color] at palette [index], falling back to the
 * theme foreground when the index is out of range.
 *
 * @param index ANSI palette index in `0..15`.
 */
private fun TerminalTheme.paletteColor(index: Int): Color = palette.getOrNull(index)?.toOpaqueColor() ?: fgArgb.toOpaqueColor()

/** Converts a packed ARGB [UInt] to an opaque Compose [Color]. */
private fun UInt.toOpaqueColor(): Color = Color(this.toInt() or OPAQUE_ALPHA)

/**
 * Resolves the display [Color] for a REPL [role] from the active
 * [LocalTerminalTheme], falling back to the canonical Material color for
 * that role before a theme has loaded.
 *
 * Single source of truth for the theme-or-Material decision so every
 * terminal surface (REPL input bar, Shell input row, mode toggle,
 * scrollback) resolves the same role to the same color and the surfaces
 * cannot silently drift apart.
 *
 * @param role Semantic role of the element being colored.
 * @return Opaque themed color, or the Material default when no theme is active.
 */
@Composable
fun themedRoleColor(role: BlockRole): Color = LocalTerminalTheme.current?.colorForRole(role) ?: role.materialFallback()

/**
 * Resolves the background [Color] for a terminal surface from the active
 * [LocalTerminalTheme], falling back to the Material surface color before
 * a theme has loaded.
 *
 * @return Opaque themed background, or the Material surface when no theme is active.
 */
@Composable
fun themedReplBackground(): Color = LocalTerminalTheme.current?.replBackground() ?: MaterialTheme.colorScheme.surface

/**
 * Material color used for this [BlockRole] before a terminal theme has
 * loaded, keeping the no-theme fallback consistent across call sites.
 *
 * @return Material color scheme entry matching the role's semantics.
 */
@Composable
private fun BlockRole.materialFallback(): Color =
    when (this) {
        BlockRole.INPUT_PROMPT -> MaterialTheme.colorScheme.primary
        BlockRole.INPUT_TEXT, BlockRole.RESPONSE -> MaterialTheme.colorScheme.onSurface
        BlockRole.ERROR -> MaterialTheme.colorScheme.error
        BlockRole.SYSTEM, BlockRole.STRUCTURED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
