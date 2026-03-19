/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DaemonServicePrefs].
 */
@DisplayName("DaemonServicePrefs")
class DaemonServicePrefsTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val store = mutableMapOf<String, Boolean>()

    @BeforeEach
    fun setUp() {
        editor =
            mockk(relaxed = true) {
                every { putBoolean(any(), any()) } answers {
                    store[firstArg()] = secondArg<Boolean>()
                    this@mockk
                }
                every { commit() } returns true
            }
        prefs =
            mockk {
                every { edit() } returns editor
                every { getBoolean(any(), any()) } answers {
                    store[firstArg()] ?: secondArg()
                }
            }
        context =
            mockk {
                every {
                    getSharedPreferences(
                        DaemonServicePrefs.PREFS_NAME,
                        Context.MODE_PRIVATE,
                    )
                } returns prefs
            }
    }

    @Test
    @DisplayName("auto-start defaults to false")
    fun `auto start defaults to false`() {
        assertFalse(DaemonServicePrefs.isAutoStartOnBootEnabled(context))
    }

    @Test
    @DisplayName("setAutoStartOnBoot persists value")
    fun `setAutoStartOnBoot persists value`() {
        val committed = DaemonServicePrefs.setAutoStartOnBoot(context, true)

        assertTrue(committed)
        assertTrue(DaemonServicePrefs.isAutoStartOnBootEnabled(context))
    }
}
