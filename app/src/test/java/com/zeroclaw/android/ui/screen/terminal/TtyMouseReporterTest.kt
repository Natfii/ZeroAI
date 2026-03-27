/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit tests for [TtyMouseReporter] gesture-to-mouse-event mapping. */
class TtyMouseReporterTest {
    private lateinit var reporter: TtyMouseReporter
    private val events = mutableListOf<MouseEvent>()

    /** Captured mouse event from the emit callback. */
    private data class MouseEvent(
        val action: UByte,
        val button: UByte,
        val x: Float,
        val y: Float,
        val mods: UInt,
    )

    private val emit: (UByte, UByte, Float, Float, UInt) -> Unit =
        { a, b, x, y, m -> events.add(MouseEvent(a, b, x, y, m)) }

    @BeforeEach
    fun setUp() {
        reporter = TtyMouseReporter()
        events.clear()
    }

    @Test
    fun `onDown emits left-click press event`() {
        reporter.onDown(100f, 200f, 0u, emit)

        assertEquals(1, events.size)
        val ev = events[0]
        assertEquals(TtyMouseReporter.ACTION_PRESS, ev.action)
        assertEquals(TtyMouseReporter.BUTTON_LEFT, ev.button)
        assertEquals(100f, ev.x)
        assertEquals(200f, ev.y)
        assertEquals(0u, ev.mods)
    }

    @Test
    fun `onUp emits release for left button after down`() {
        reporter.onDown(50f, 60f, 0u, emit)
        events.clear()

        reporter.onUp(50f, 60f, 0u, emit)

        assertEquals(1, events.size)
        val ev = events[0]
        assertEquals(TtyMouseReporter.ACTION_RELEASE, ev.action)
        assertEquals(TtyMouseReporter.BUTTON_LEFT, ev.button)
    }

    @Test
    fun `vertical swipe downward past slop produces scroll-down ticks`() {
        val cellHeight = 32f
        reporter.onDown(100f, 100f, 0u, emit)
        events.clear()

        // Move down by 3 cells (96px) in one step — accumulator fills 3 times
        reporter.onMove(100f, 196f, cellHeight, 0u, emit)

        val scrollDownEvents =
            events.filter {
                it.button == TtyMouseReporter.BUTTON_SCROLL_DOWN &&
                    it.action == TtyMouseReporter.ACTION_PRESS
            }
        assertTrue(scrollDownEvents.isNotEmpty(), "Expected at least one scroll-down tick")
    }

    @Test
    fun `vertical swipe upward produces scroll-up ticks`() {
        val cellHeight = 32f
        reporter.onDown(100f, 300f, 0u, emit)
        events.clear()

        // Move up by 3 cells (96px) in one step
        reporter.onMove(100f, 204f, cellHeight, 0u, emit)

        val scrollUpEvents =
            events.filter {
                it.button == TtyMouseReporter.BUTTON_SCROLL_UP &&
                    it.action == TtyMouseReporter.ACTION_PRESS
            }
        assertTrue(scrollUpEvents.isNotEmpty(), "Expected at least one scroll-up tick")
    }

    @Test
    fun `horizontal drag past slop emits motion event`() {
        reporter.onDown(100f, 100f, 0u, emit)
        events.clear()

        // Sleep past the 60Hz throttle window (17ms minimum)
        Thread.sleep(20)

        // Move horizontally by 50px (well past 24px slop)
        reporter.onMove(150f, 100f, 32f, 0u, emit)

        val motionEvents = events.filter { it.action == TtyMouseReporter.ACTION_MOTION }
        assertEquals(1, motionEvents.size, "Expected exactly one motion event")
        val ev = motionEvents[0]
        assertEquals(TtyMouseReporter.BUTTON_LEFT, ev.button)
        assertEquals(150f, ev.x)
        assertEquals(100f, ev.y)
    }

    @Test
    fun `onCancel resets state without emitting any events`() {
        reporter.onDown(50f, 50f, 0u, emit)
        events.clear()

        reporter.onCancel()

        assertTrue(events.isEmpty(), "onCancel must not emit any events")
    }

    @Test
    fun `secondary pointer cancels pending long press`() {
        reporter.onDown(100f, 100f, 0u, emit)
        events.clear()

        // Register a secondary pointer (e.g. pinch gesture)
        reporter.onSecondaryPointer()

        // Wait well past the 400ms long-press timeout
        Thread.sleep(450)

        // checkLongPress should be a no-op now
        reporter.checkLongPress(100f, 100f, 0u, emit)

        val rightClickEvents = events.filter { it.button == TtyMouseReporter.BUTTON_RIGHT }
        assertTrue(rightClickEvents.isEmpty(), "No right-click should fire after secondary pointer")
    }

    @Test
    fun `modsFromMetaState maps shift ctrl alt meta to correct bitmask`() {
        // Each flag individually
        assertEquals(1u, reporter.modsFromMetaState(android.view.KeyEvent.META_SHIFT_ON))
        assertEquals(2u, reporter.modsFromMetaState(android.view.KeyEvent.META_CTRL_ON))
        assertEquals(4u, reporter.modsFromMetaState(android.view.KeyEvent.META_ALT_ON))
        assertEquals(8u, reporter.modsFromMetaState(android.view.KeyEvent.META_META_ON))

        // Combined Shift + Ctrl + Alt + Meta
        val all =
            android.view.KeyEvent.META_SHIFT_ON or
                android.view.KeyEvent.META_CTRL_ON or
                android.view.KeyEvent.META_ALT_ON or
                android.view.KeyEvent.META_META_ON
        assertEquals(15u, reporter.modsFromMetaState(all))

        // No flags set
        assertEquals(0u, reporter.modsFromMetaState(0))
    }
}
