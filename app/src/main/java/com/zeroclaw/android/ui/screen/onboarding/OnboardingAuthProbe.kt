/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.onboarding

/**
 * Returns `true` only when a model-list probe failed with HTTP 401 or
 * 403 — the auth-failure signal we want to block onboarding on.
 *
 * Network errors, 5xx, timeouts, DNS failures, and anything else pass
 * through (return `false`) so that offline setup and transient provider
 * issues do not stop the user from completing onboarding. The user can
 * always fix credentials later in Settings.
 *
 * @param probeResult Result returned from `ModelFetcher.fetchModels`.
 */
internal fun isDefinitiveAuthFailure(probeResult: Result<*>): Boolean {
    val failure = probeResult.exceptionOrNull() ?: return false
    val msg = failure.message ?: ""
    return "HTTP 401" in msg || "HTTP 403" in msg
}
