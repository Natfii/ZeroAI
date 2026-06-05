/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable

/**
 * Memory settings area holder.
 *
 * Owns daemon-affecting memory backend, auto-save, hygiene, retention windows,
 * embedding provider/model, and hybrid-search weights; all mark a restart as
 * required.
 */
@Stable
internal class MemorySettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryBackend */
    fun updateMemoryBackend(backend: String) {
        s.updateDaemonSetting { setMemoryBackend(backend) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryAutoSave */
    fun updateMemoryAutoSave(enabled: Boolean) {
        s.updateDaemonSetting { setMemoryAutoSave(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryHygieneEnabled */
    fun updateMemoryHygieneEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setMemoryHygieneEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryArchiveAfterDays */
    fun updateMemoryArchiveAfterDays(days: Int) {
        s.updateDaemonSetting { setMemoryArchiveAfterDays(days) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryPurgeAfterDays */
    fun updateMemoryPurgeAfterDays(days: Int) {
        s.updateDaemonSetting { setMemoryPurgeAfterDays(days) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryEmbeddingProvider */
    fun updateMemoryEmbeddingProvider(provider: String) {
        s.updateDaemonSetting { setMemoryEmbeddingProvider(provider) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryEmbeddingModel */
    fun updateMemoryEmbeddingModel(model: String) {
        s.updateDaemonSetting { setMemoryEmbeddingModel(model) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryVectorWeight */
    fun updateMemoryVectorWeight(weight: Float) {
        s.updateDaemonSetting { setMemoryVectorWeight(weight) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryKeywordWeight */
    fun updateMemoryKeywordWeight(weight: Float) {
        s.updateDaemonSetting { setMemoryKeywordWeight(weight) }
    }
}
