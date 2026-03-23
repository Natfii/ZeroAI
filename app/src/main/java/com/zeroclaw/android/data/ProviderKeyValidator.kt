/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import com.zeroclaw.android.model.ProviderInfo
import org.json.JSONObject

/**
 * Validates provider API key formats and parses JSON-body authentication errors.
 *
 * Used by both the onboarding wizard and the settings API key screen to provide
 * client-side feedback before attempting a network probe.
 */
object ProviderKeyValidator {
    /** Substrings in a JSON error body that indicate an authentication failure. */
    private val AUTH_ERROR_INDICATORS =
        listOf(
            "authentication_error",
            "invalid_api_key",
            "unauthenticated",
            "unauthorized",
            "invalid_key",
            "invalid x-api-key",
        )

    /**
     * Validates that [key] matches the expected format for [providerInfo].
     *
     * Checks the key prefix first, then enforces a minimum length when
     * [ProviderInfo.minKeyLength] is non-zero. Returns null if the key is
     * blank or passes all checks. Error messages never include any portion
     * of the entered key.
     *
     * @param providerInfo Provider metadata containing the expected [ProviderInfo.keyPrefix]
     *   and [ProviderInfo.minKeyLength].
     * @param key The API key value entered by the user.
     * @return Warning hint string, or null if no issue detected.
     */
    fun validateKeyFormat(
        providerInfo: ProviderInfo,
        key: String,
    ): String? {
        if (key.isBlank()) return null
        if (providerInfo.keyPrefix.isNotEmpty() && !key.startsWith(providerInfo.keyPrefix)) {
            return providerInfo.keyPrefixHint
        }
        if (providerInfo.minKeyLength > 0 && key.length < providerInfo.minKeyLength) {
            return "Key appears too short (expected at least ${providerInfo.minKeyLength} characters)"
        }
        return null
    }

    /**
     * Checks whether an HTTP 200 response body contains a JSON authentication error.
     *
     * Some providers return HTTP 200 with an error object in the body instead
     * of using proper HTTP status codes. This method detects known patterns
     * from Anthropic, OpenAI, Google, and generic error formats.
     *
     * @param responseBody The raw JSON response body, or null.
     * @return True if the body contains a recognizable authentication error.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun isJsonBodyAuthError(responseBody: String?): Boolean {
        if (responseBody.isNullOrBlank()) return false
        return try {
            val root = JSONObject(responseBody)
            val errorText = extractErrorText(root)
            if (errorText.isBlank()) return false
            val lower = errorText.lowercase()
            AUTH_ERROR_INDICATORS.any { it in lower }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extracts concatenated error text from known JSON error envelope formats.
     *
     * Checks for:
     * - `{"error": {"type": "...", "message": "...", "code": "...", "status": "..."}}`
     * - `{"type": "error", "error": {"type": "..."}}`
     *
     * @param root The parsed JSON root object.
     * @return Concatenated error field values, or empty string if no error found.
     */
    private fun extractErrorText(root: JSONObject): String {
        val errorObj = root.optJSONObject("error")
        if (errorObj != null) {
            return buildString {
                append(errorObj.optString("type", ""))
                append(" ")
                append(errorObj.optString("message", ""))
                append(" ")
                append(errorObj.optString("code", ""))
                append(" ")
                append(errorObj.optString("status", ""))
            }
        }
        if (root.optString("type") == "error") {
            val nested = root.optJSONObject("error")
            if (nested != null) {
                return nested.optString("type", "")
            }
        }
        return ""
    }
}
