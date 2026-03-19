/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.util.Log
import com.zeroclaw.android.model.RouteHint

/**
 * Two-tier message classifier for provider routing.
 *
 * Selects between Gemini Nano (on-device, ~0.5s with prefix cache) and
 * the [HeuristicClassifier] (zero-latency, regex-based) depending on
 * whether the app is in the foreground and Nano is available.
 *
 * Decision flow:
 * 1. If [isForeground] returns `true` and [nanoClassifier] is non-null,
 *    attempt Nano classification.
 * 2. If Nano returns a valid result, use it.
 * 3. Otherwise, fall back to [HeuristicClassifier].
 *
 * @param nanoClassifier The Nano classifier, or `null` if the model
 *   is unavailable on this device.
 * @param isForeground Lambda that returns `true` when the app is the
 *   top foreground activity (Nano requires this).
 */
class MessageClassifier(
    private val nanoClassifier: NanoClassifier?,
    private val isForeground: () -> Boolean,
) {
    /**
     * Classifies a message into a [RouteHint] complexity tier.
     *
     * @param message The raw user message.
     * @return The classified [RouteHint]. Never returns `null`.
     */
    suspend fun classify(message: String): RouteHint {
        if (isForeground() && nanoClassifier != null) {
            val nanoResult = nanoClassifier.classify(message)
            if (nanoResult != null) {
                Log.d(TAG, "Classified via Nano: $nanoResult")
                return nanoResult
            }
            Log.d(TAG, "Nano returned null, falling back to Heuristic")
        }
        val result = HeuristicClassifier.classify(message)
        Log.d(TAG, "Classified via Heuristic: $result")
        return result
    }

    companion object {
        private const val TAG = "MessageClassifier"
    }
}
