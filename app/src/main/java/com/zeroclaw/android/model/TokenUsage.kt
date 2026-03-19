/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * Token usage from a single LLM API call.
 *
 * Maps to the native `FfiTokenUsage` record. Fields are nullable because
 * not all providers report usage data.
 *
 * @property inputTokens Number of input (prompt) tokens, if reported.
 * @property outputTokens Number of output (completion) tokens, if reported.
 */
data class TokenUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
) {
    /** Total tokens (input + output), or null if neither is available. */
    val total: Long?
        get() =
            when {
                inputTokens != null && outputTokens != null -> inputTokens + outputTokens
                inputTokens != null -> inputTokens
                outputTokens != null -> outputTokens
                else -> null
            }
}
