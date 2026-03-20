/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data

import com.zeroclaw.android.data.oauth.authProfileProviderFor

/**
 * Credential type for a fixed provider slot.
 */
enum class SlotCredentialType {
    /** Manual API key entry. */
    API_KEY,

    /** OAuth or session-backed login. */
    OAUTH,

    /** Base URL with optional API key. */
    URL_KEY,
}

/**
 * Immutable definition of a provider slot in the Agents tab.
 *
 * @property slotId Stable identifier used in persistence and navigation.
 * @property displayName Human-readable slot label.
 * @property credentialType Authentication mode for this slot.
 * @property baseOrder Stable display ordering within the slot catalog.
 * @property rustProvider Canonical provider string for the daemon config layer.
 * @property authProfileProvider Rust auth-profile provider key for OAuth slots.
 * @property providerRegistryId Kotlin provider registry ID for icons and metadata.
 * @property routesModelRequests Whether this slot participates in daemon model routing.
 */
data class ProviderSlot(
    val slotId: String,
    val displayName: String,
    val credentialType: SlotCredentialType,
    val baseOrder: Int,
    val rustProvider: String,
    val authProfileProvider: String?,
    val providerRegistryId: String,
    val routesModelRequests: Boolean = true,
)

/**
 * Static registry of the fixed provider slots shown in the Agents tab.
 */
object ProviderSlotRegistry {
    /**
     * Hidden slots kept for code compatibility but not shown in any UI.
     * Claude Code OAuth was blocked by Anthropic server-side (2026-01).
     */
    @Suppress("unused")
    private val hiddenSlots: List<ProviderSlot> =
        listOf(
            ProviderSlot(
                slotId = "claude-code",
                displayName = "Claude Code",
                credentialType = SlotCredentialType.OAUTH,
                baseOrder = 4,
                rustProvider = "anthropic",
                authProfileProvider = "anthropic",
                providerRegistryId = "anthropic",
            ),
        )

    private val slots: List<ProviderSlot> =
        listOf(
            ProviderSlot(
                slotId = "gemini-api",
                displayName = "Gemini API",
                credentialType = SlotCredentialType.API_KEY,
                baseOrder = 0,
                rustProvider = "gemini",
                authProfileProvider = null,
                providerRegistryId = "google-gemini",
            ),
            ProviderSlot(
                slotId = "openai-api",
                displayName = "OpenAI API",
                credentialType = SlotCredentialType.API_KEY,
                baseOrder = 1,
                rustProvider = "openai",
                authProfileProvider = null,
                providerRegistryId = "openai",
            ),
            ProviderSlot(
                slotId = "chatgpt",
                displayName = "ChatGPT",
                credentialType = SlotCredentialType.OAUTH,
                baseOrder = 2,
                rustProvider = "openai",
                authProfileProvider = "openai-codex",
                providerRegistryId = "openai",
            ),
            ProviderSlot(
                slotId = "anthropic-api",
                displayName = "Anthropic API",
                credentialType = SlotCredentialType.API_KEY,
                baseOrder = 3,
                rustProvider = "anthropic",
                authProfileProvider = null,
                providerRegistryId = "anthropic",
            ),
            ProviderSlot(
                slotId = "openrouter-api",
                displayName = "OpenRouter API",
                credentialType = SlotCredentialType.API_KEY,
                baseOrder = 5,
                rustProvider = "openrouter",
                authProfileProvider = null,
                providerRegistryId = "openrouter",
            ),
            ProviderSlot(
                slotId = "xai-api",
                displayName = "xAI API",
                credentialType = SlotCredentialType.API_KEY,
                baseOrder = 7,
                rustProvider = "xai",
                authProfileProvider = null,
                providerRegistryId = "xai",
            ),
            ProviderSlot(
                slotId = "ollama",
                displayName = "Ollama",
                credentialType = SlotCredentialType.URL_KEY,
                baseOrder = 8,
                rustProvider = "ollama",
                authProfileProvider = null,
                providerRegistryId = "ollama",
            ),
        )

    private val byId: Map<String, ProviderSlot> = slots.associateBy { it.slotId }

    init {
        check(slots.map { it.slotId }.distinct().size == slots.size) {
            "ProviderSlotRegistry contains duplicate slot IDs"
        }
        check(slots.map { it.baseOrder }.distinct().size == slots.size) {
            "ProviderSlotRegistry contains duplicate base orders"
        }
        check(slots.all { ProviderRegistry.findById(it.providerRegistryId) != null }) {
            "ProviderSlotRegistry contains unknown providerRegistryId values"
        }
        check(
            slots
                .filter { it.authProfileProvider != null }
                .all { slot ->
                    authProfileProviderFor(slot.providerRegistryId) ==
                        slot.authProfileProvider
                },
        ) {
            "ProviderSlotRegistry auth-profile mappings do not match AuthProfileStore"
        }
    }

    /** Returns all slots in stable display order. */
    fun all(): List<ProviderSlot> = slots

    /** Returns only slots that participate in daemon model routing. */
    fun allRouting(): List<ProviderSlot> = slots.filter { slot -> slot.routesModelRequests }

    /** Returns the slot with [slotId], or null when unknown. */
    fun findById(slotId: String): ProviderSlot? = byId[slotId]

    /**
     * Resolves a slot ID from a provider registry ID and auth mode.
     *
     * @param providerRegistryId Canonical Kotlin provider registry ID.
     * @param isOAuth True for OAuth/session-backed slots, false otherwise.
     * @return Matching slot ID, or null when no slot matches.
     */
    fun resolveSlotId(
        providerRegistryId: String,
        isOAuth: Boolean,
    ): String? =
        slots
            .firstOrNull { slot ->
                slot.providerRegistryId == providerRegistryId &&
                    if (isOAuth) {
                        slot.credentialType == SlotCredentialType.OAUTH
                    } else {
                        slot.credentialType != SlotCredentialType.OAUTH
                    }
            }?.slotId
}
