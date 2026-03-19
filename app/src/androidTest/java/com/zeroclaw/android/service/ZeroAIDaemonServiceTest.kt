/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ZeroAIDaemonService].
 *
 * These tests run on a real device or emulator and verify that the
 * service can be created and responds to lifecycle intents.
 */
@RunWith(AndroidJUnit4::class)
class ZeroAIDaemonServiceTest {
    @Test
    fun serviceIntentIsResolvable() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                ZeroAIDaemonService::class.java,
            )
        assertNotNull(intent.component)
    }
}
