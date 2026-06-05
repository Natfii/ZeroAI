/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable
import kotlinx.coroutines.launch

/**
 * Lifecycle settings area holder.
 *
 * Owns onboarding reset, which orchestrates three collaborators directly (not
 * via the daemon write seam) to preserve the exact clear-identity, mark-restart,
 * reset-onboarding ordering.
 */
@Stable
internal class LifecycleSettingsActions(
    private val s: SettingsActionScope,
) {
    /**
     * Resets onboarding completion state so the setup wizard is shown again.
     *
     * Clears the AIEOS identity JSON so the wizard generates a fresh
     * identity document. Existing API keys and other settings are preserved.
     */
    fun resetOnboarding() {
        s.scope.launch {
            s.repository.setIdentityJson("")
            s.daemonBridge.markRestartRequired()
            s.onboardingRepository.reset()
        }
    }
}
