/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.memory

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InterestingnessFilter")
class InterestingnessFilterTest {
    @Test
    @DisplayName("short message is not interesting")
    fun `short message`() {
        assertFalse(InterestingnessFilter.isInteresting("hi", heuristicCaptured = false))
    }

    @Test
    @DisplayName("long personal message is interesting")
    fun `long personal`() {
        val msg = "I've been thinking about switching from IntelliJ to VS Code for my Kotlin projects because the startup time is getting really annoying on my machine"
        assertTrue(InterestingnessFilter.isInteresting(msg, heuristicCaptured = false))
    }

    @Test
    @DisplayName("long impersonal message is not interesting")
    fun `long impersonal`() {
        val msg = "The function returns a list of all the items in the database that match the query parameters specified in the configuration file for this module"
        assertFalse(InterestingnessFilter.isInteresting(msg, heuristicCaptured = false))
    }

    @Test
    @DisplayName("heuristic already captured means not interesting")
    fun `heuristic captured`() {
        val msg = "My name is Natali and I live in Seattle and I work with Kotlin and Rust for Android development"
        assertFalse(InterestingnessFilter.isInteresting(msg, heuristicCaptured = true))
    }

    @Test
    @DisplayName("command message is not interesting")
    fun `command message`() {
        assertFalse(InterestingnessFilter.isInteresting("/start_game pokemon red version please start it now for me", heuristicCaptured = false))
    }

    @Test
    @DisplayName("rich personal disclosure is interesting")
    fun `rich disclosure`() {
        val msg = "I'm a data scientist at Google, I've been doing ML for 10 years and recently switched to working on LLM fine-tuning for production systems"
        assertTrue(InterestingnessFilter.isInteresting(msg, heuristicCaptured = false))
    }
}
