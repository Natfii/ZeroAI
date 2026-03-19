/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.plugins

import com.zeroclaw.android.ui.screen.settings.TestSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OfficialPluginSettingsSync].
 */
@DisplayName("OfficialPluginSettingsSync")
class OfficialPluginSettingsSyncTest {
    @Test
    @DisplayName("restoreDefaults disables all configurable official plugin settings")
    fun `restoreDefaults disables all configurable official plugin settings`() =
        runTest {
            val repository = TestSettingsRepository()
            repository.setWebSearchEnabled(true)
            repository.setWebFetchEnabled(true)
            repository.setHttpRequestEnabled(true)
            repository.setComposioEnabled(true)
            repository.setTranscriptionEnabled(true)
            repository.setQueryClassificationEnabled(true)
            repository.setSharedFolderEnabled(true)

            OfficialPluginSettingsSync.restoreDefaults(repository)

            val settings = repository.settings.first()
            assertFalse(settings.webSearchEnabled)
            assertFalse(settings.webFetchEnabled)
            assertFalse(settings.httpRequestEnabled)
            assertFalse(settings.composioEnabled)
            assertFalse(settings.transcriptionEnabled)
            assertFalse(settings.queryClassificationEnabled)
            assertFalse(settings.sharedFolderEnabled)
        }

    @Test
    @DisplayName("restoreDefaults disables shared folder")
    fun `restoreDefaults disables shared folder`() =
        runTest {
            val repository = TestSettingsRepository()
            repository.setSharedFolderEnabled(true)

            OfficialPluginSettingsSync.restoreDefaults(repository)

            val settings = repository.settings.first()
            assertFalse(settings.sharedFolderEnabled)
        }
}
