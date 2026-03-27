/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Maps Android pointer events to terminal mouse event parameters.
 *
 * Maintains per-gesture state (scroll accumulator, long-press timer,
 * committed gesture mode) that resets on each new [onDown] call.
 *
 * Motion events are coalesced to 60 Hz to prevent saturating the
 * 256-slot PTY write channel.
 */
internal class TtyMouseReporter {
    /** Mouse action constants matching ghostty C enum values. */
    companion object {
        const val ACTION_PRESS: UByte = 0u
        const val ACTION_RELEASE: UByte = 1u
        const val ACTION_MOTION: UByte = 2u

        const val BUTTON_LEFT: UByte = 1u
        const val BUTTON_RIGHT: UByte = 2u
        const val BUTTON_SCROLL_UP: UByte = 4u
        const val BUTTON_SCROLL_DOWN: UByte = 5u

        /** Minimum elapsed nanos between motion event submissions (60 Hz). */
        private const val MOTION_THROTTLE_NS = 16_666_667L

        /** Long-press timeout in milliseconds. */
        private const val LONG_PRESS_TIMEOUT_MS = 400L

        /** Slop threshold in pixels for scroll-vs-drag discrimination. */
        private const val SLOP_PX = 24f

        /** Nanoseconds per millisecond, used to convert nanoTime to ms. */
        private const val NANOS_PER_MS = 1_000_000L
    }

    private enum class GestureMode { UNDECIDED, DRAG, SCROLL }

    private var mode = GestureMode.UNDECIDED
    private var downX = 0f
    private var downY = 0f
    private var currentButton: UByte = BUTTON_LEFT
    private var scrollAccumulator = 0f
    private var lastMotionNanos = 0L
    private var longPressScheduled = false
    private var longPressFired = false
    private var longPressDownTime = 0L
    private var secondaryPointerSeen = false

    /**
     * Resets state for a new gesture and emits a left-click press.
     *
     * @param x surface pixel X
     * @param y surface pixel Y
     * @param mods modifier bitmask
     * @param emit callback to submit the mouse event to FFI
     */
    fun onDown(
        x: Float,
        y: Float,
        mods: UInt,
        emit: (action: UByte, button: UByte, px: Float, py: Float, m: UInt) -> Unit,
    ) {
        mode = GestureMode.UNDECIDED
        downX = x
        downY = y
        currentButton = BUTTON_LEFT
        scrollAccumulator = 0f
        lastMotionNanos = System.nanoTime()
        longPressScheduled = true
        longPressFired = false
        longPressDownTime = System.nanoTime()
        secondaryPointerSeen = false

        emit(ACTION_PRESS, BUTTON_LEFT, x, y, mods)
    }

    /**
     * Processes a pointer move event. Discriminates between drag and
     * scroll on the first move past the slop threshold. Motion events
     * are coalesced to 60 Hz.
     *
     * @param x surface pixel X
     * @param y surface pixel Y
     * @param cellHeight cell height in pixels (for scroll tick sizing)
     * @param mods modifier bitmask
     * @param emit callback to submit the mouse event to FFI
     */
    fun onMove(
        x: Float,
        y: Float,
        cellHeight: Float,
        mods: UInt,
        emit: (action: UByte, button: UByte, px: Float, py: Float, m: UInt) -> Unit,
    ) {
        val dx = x - downX
        val dy = y - downY

        if (dx * dx + dy * dy > SLOP_PX * SLOP_PX) {
            longPressScheduled = false
        }

        checkLongPress(x, y, mods, emit)

        if (mode == GestureMode.UNDECIDED) {
            val absDx = kotlin.math.abs(dx)
            val absDy = kotlin.math.abs(dy)
            if (absDx < SLOP_PX && absDy < SLOP_PX) return

            mode = if (absDy > absDx) GestureMode.SCROLL else GestureMode.DRAG
        }

        when (mode) {
            GestureMode.SCROLL -> handleScroll(x, y, cellHeight, mods, emit)
            GestureMode.DRAG -> handleDrag(x, y, mods, emit)
            GestureMode.UNDECIDED -> {}
        }
    }

    /**
     * Ends the current gesture. Emits a release event for the active
     * button.
     *
     * @param x surface pixel X
     * @param y surface pixel Y
     * @param mods modifier bitmask
     * @param emit callback to submit the mouse event to FFI
     */
    fun onUp(
        x: Float,
        y: Float,
        mods: UInt,
        emit: (action: UByte, button: UByte, px: Float, py: Float, m: UInt) -> Unit,
    ) {
        longPressScheduled = false
        emit(ACTION_RELEASE, currentButton, x, y, mods)
    }

    /**
     * Cancels the current gesture. Resets state without emitting
     * any events. Called on [android.view.MotionEvent.ACTION_CANCEL].
     */
    fun onCancel() {
        longPressScheduled = false
        longPressFired = false
        mode = GestureMode.UNDECIDED
    }

    /**
     * Notifies the reporter that a secondary pointer was detected
     * (multi-finger gesture, e.g. pinch). Cancels any pending
     * long-press to prevent spurious right-clicks during zoom.
     */
    fun onSecondaryPointer() {
        secondaryPointerSeen = true
        longPressScheduled = false
    }

    /**
     * Checks whether the long-press timer has elapsed while the
     * finger is stationary. If so, emits a right-click press and
     * triggers haptic feedback.
     *
     * @param x surface pixel X at the current pointer position
     * @param y surface pixel Y at the current pointer position
     * @param mods modifier bitmask
     * @param emit callback to submit the mouse event to FFI
     * @param view optional view for haptic feedback (null skips haptics)
     */
    fun checkLongPress(
        x: Float,
        y: Float,
        mods: UInt,
        emit: (action: UByte, button: UByte, px: Float, py: Float, m: UInt) -> Unit,
        view: View? = null,
    ) {
        if (!longPressScheduled || longPressFired || secondaryPointerSeen) return
        val elapsed = (System.nanoTime() - longPressDownTime) / NANOS_PER_MS
        if (elapsed < LONG_PRESS_TIMEOUT_MS) return

        longPressFired = true
        longPressScheduled = false

        emit(ACTION_RELEASE, BUTTON_LEFT, x, y, mods)
        currentButton = BUTTON_RIGHT
        emit(ACTION_PRESS, BUTTON_RIGHT, x, y, mods)

        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * Extracts modifier bitmask from Android meta-state flags.
     *
     * @param metaState value from [android.view.MotionEvent.getMetaState]
     * @return ghostty-compatible modifier bitmask
     */
    fun modsFromMetaState(metaState: Int): UInt {
        var mods = 0u
        if (metaState and android.view.KeyEvent.META_SHIFT_ON != 0) mods = mods or 1u
        if (metaState and android.view.KeyEvent.META_CTRL_ON != 0) mods = mods or 2u
        if (metaState and android.view.KeyEvent.META_ALT_ON != 0) mods = mods or 4u
        if (metaState and android.view.KeyEvent.META_META_ON != 0) mods = mods or 8u
        return mods
    }

    /**
     * Emits scroll wheel events for accumulated vertical distance.
     *
     * @param x surface pixel X
     * @param y surface pixel Y
     * @param cellHeight cell height in pixels for scroll tick sizing
     * @param mods modifier bitmask
     * @param emit callback to submit the mouse event to FFI
     */
    private fun handleScroll(
        x: Float,
        y: Float,
        cellHeight: Float,
        mods: UInt,
        emit: (action: UByte, button: UByte, px: Float, py: Float, m: UInt) -> Unit,
    ) {
        scrollAccumulator += (y - downY)
        downY = y
        while (scrollAccumulator >= cellHeight) {
            scrollAccumulator -= cellHeight
            emit(ACTION_PRESS, BUTTON_SCROLL_DOWN, x, y, mods)
            emit(ACTION_RELEASE, BUTTON_SCROLL_DOWN, x, y, mods)
        }
        while (scrollAccumulator <= -cellHeight) {
            scrollAccumulator += cellHeight
            emit(ACTION_PRESS, BUTTON_SCROLL_UP, x, y, mods)
            emit(ACTION_RELEASE, BUTTON_SCROLL_UP, x, y, mods)
        }
    }

    /**
     * Emits a motion event for a drag gesture, throttled to 60 Hz.
     *
     * @param x surface pixel X
     * @param y surface pixel Y
     * @param mods modifier bitmask
     * @param emit callback to submit the mouse event to FFI
     */
    private fun handleDrag(
        x: Float,
        y: Float,
        mods: UInt,
        emit: (action: UByte, button: UByte, px: Float, py: Float, m: UInt) -> Unit,
    ) {
        val now = System.nanoTime()
        if (now - lastMotionNanos < MOTION_THROTTLE_NS) return
        lastMotionNanos = now
        emit(ACTION_MOTION, currentButton, x, y, mods)
    }
}
