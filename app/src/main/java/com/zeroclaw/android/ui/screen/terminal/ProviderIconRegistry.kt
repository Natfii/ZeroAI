/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.annotation.DrawableRes
import com.zeroclaw.android.R

/**
 * Attribution data for a response provider.
 *
 * Displayed inline at the start of each terminal response block to
 * indicate which model produced the response.
 *
 * @property icon Drawable resource ID for the provider logo.
 * @property name Human-readable provider name for accessibility.
 */
data class ProviderAttribution(
    @DrawableRes val icon: Int,
    val name: String,
)

/**
 * Maps provider identifier strings to their [ProviderAttribution].
 *
 * Used by the terminal output renderer to display the correct provider
 * icon at the start of each response block. Provider IDs come from
 * the daemon configuration or from the on-device routing decision.
 */
object ProviderIconRegistry {
    /** Provider identifier used for on-device Nano responses. */
    const val NANO_PROVIDER = "nano"

    /** Fallback attribution when the provider is unknown. */
    private val FALLBACK =
        ProviderAttribution(
            icon = R.drawable.ic_provider_nano,
            name = "AI",
        )

    /** Provider ID to attribution mapping. */
    private val REGISTRY: Map<String, ProviderAttribution> =
        mapOf(
            "anthropic" to
                ProviderAttribution(
                    icon = R.drawable.ic_provider_anthropic,
                    name = "Anthropic",
                ),
            "openai" to
                ProviderAttribution(
                    icon = R.drawable.ic_provider_openai,
                    name = "OpenAI",
                ),
            "gemini" to
                ProviderAttribution(
                    icon = R.drawable.ic_provider_gemini,
                    name = "Gemini",
                ),
            "ollama" to
                ProviderAttribution(
                    icon = R.drawable.ic_provider_ollama,
                    name = "Ollama",
                ),
            NANO_PROVIDER to
                ProviderAttribution(
                    icon = R.drawable.ic_provider_nano,
                    name = "Nano",
                ),
        )

    /**
     * Looks up the attribution for a provider ID.
     *
     * @param providerId Provider identifier string (e.g. "anthropic").
     *   Case-insensitive. May be null for unknown providers.
     * @return The matching [ProviderAttribution], or a generic fallback.
     */
    fun forProvider(providerId: String?): ProviderAttribution = providerId?.let { REGISTRY[it.lowercase()] } ?: FALLBACK
}
