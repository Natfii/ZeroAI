/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.RouteHint
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Classifies messages using Gemini Nano on-device inference.
 *
 * Uses a low-temperature, short-output classification prompt to categorize
 * messages into [RouteHint] tiers. Requires the app to be in the foreground
 * (AICore blocks background inference).
 *
 * Falls back to `null` on any failure (timeout, model unavailable, quota
 * exceeded), allowing [MessageClassifier] to use the heuristic fallback.
 *
 * @param bridge The on-device inference bridge for Gemini Nano access.
 */
class NanoClassifier(
    private val bridge: OnDeviceInferenceBridge,
) {
    /**
     * Classifies a user message via Gemini Nano.
     *
     * @param message The raw user message.
     * @return The classified [RouteHint], or `null` if classification failed
     *   (model unavailable, timeout, quota exceeded, or unparseable output).
     */
    suspend fun classify(message: String): RouteHint? {
        val prompt = buildClassificationPrompt(message)

        val result =
            withTimeoutOrNull(CLASSIFICATION_TIMEOUT_MS) {
                bridge.generateText(prompt).getOrNull()
            } ?: return null

        return parseClassification(result)
    }

    /** Classification prompt construction and output parsing utilities. */
    companion object {
        /** Max time to wait for Nano classification before falling back. */
        private const val CLASSIFICATION_TIMEOUT_MS = 3_000L

        /** Max message characters to include in classification prompt. */
        private const val MAX_MESSAGE_CHARS = 1_000

        /**
         * Builds the classification prompt for Gemini Nano.
         *
         * Uses low-ambiguity instructions with constrained output to maximize
         * determinism. The message is truncated to [MAX_MESSAGE_CHARS] to stay
         * within token budget.
         *
         * @param message The raw user message.
         * @return The full classification prompt.
         */
        internal fun buildClassificationPrompt(message: String): String {
            val truncated =
                if (message.length > MAX_MESSAGE_CHARS) {
                    message.take(MAX_MESSAGE_CHARS)
                } else {
                    message
                }
            return "Classify this message into exactly one category. " +
                "Categories: simple, complex, creative, tool_use. " +
                "Respond with only the category name, nothing else.\n\n" +
                "Message: $truncated"
        }

        /**
         * Parses Nano's classification output into a [RouteHint].
         *
         * Tolerates leading/trailing whitespace and mixed case.
         *
         * @param output The raw model output.
         * @return The parsed [RouteHint], or `null` if the output didn't
         *   match any known category.
         */
        internal fun parseClassification(output: String): RouteHint? =
            when (output.trim().lowercase()) {
                "simple" -> RouteHint.SIMPLE
                "complex" -> RouteHint.COMPLEX
                "creative" -> RouteHint.CREATIVE
                "tool_use" -> RouteHint.TOOL_USE
                else -> null
            }
    }
}
