/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.SlotCredentialType
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.ffi.FfiAuthProfile

/**
 * Shared slot-aware config helpers for daemon/TOML generation.
 *
 * The fixed provider-slot stack keeps routing rows in the agent table, but
 * config generation still has to coexist with any remaining legacy freeform
 * rows. This helper makes slot rows deterministic without dropping legacy
 * rows during the migration window.
 */
object SlotAwareAgentConfig {
    /**
     * Returns true when [apiKey] has a direct daemon-usable secret or base URL.
     *
     * OAuth/session-backed logins are intentionally excluded here because the
     * current Android daemon only knows how to route live model traffic through
     * direct provider credentials.
     */
    fun hasDirectProviderCredentials(apiKey: ApiKey?): Boolean = apiKey != null && (apiKey.key.isNotBlank() || apiKey.baseUrl.isNotBlank())

    /**
     * Returns enabled, configured agents in slot-first config order.
     *
     * Fixed slot rows are ordered by [ProviderSlotRegistry] base order. Any
     * remaining legacy rows are appended afterwards, sorted by their effective
     * config display name.
     */
    fun orderedConfiguredAgents(agents: List<Agent>): List<Agent> =
        agents
            .filter {
                it.isEnabled &&
                    it.provider.isNotBlank() &&
                    it.modelName.isNotBlank() &&
                    (slotFor(it)?.routesModelRequests ?: true)
            }.sortedWith(
                compareBy<Agent>({ slotFor(it)?.baseOrder ?: Int.MAX_VALUE }, { configName(it).lowercase() }),
            )

    /**
     * Resolves effective default provider/model from the slot-aware agent list.
     *
     * The first configured agent with usable credentials wins, preferring fixed
     * slot rows over legacy rows. When no agent has usable credentials, the
     * first enabled agent's provider is used so the daemon surfaces a clear
     * "missing key" error instead of silently falling through to stale
     * DataStore defaults (which may reference a disabled provider).
     */
    suspend fun resolveEffectiveDefaults(
        settings: AppSettings,
        agents: List<Agent>,
        hasUsableCredentials: suspend (Agent) -> Boolean,
    ): AppSettings {
        val ordered = orderedConfiguredAgents(agents)
        val primary = ordered.firstOrNull { agent -> hasUsableCredentials(agent) }
        if (primary != null) {
            return settings.copy(
                defaultProvider = configProvider(primary),
                defaultModel = primary.modelName,
            )
        }
        val firstEnabled = ordered.firstOrNull() ?: return settings
        return settings.copy(
            defaultProvider = configProvider(firstEnabled),
            defaultModel = firstEnabled.modelName,
        )
    }

    /** Returns the stable config table name for [agent]. */
    fun configName(agent: Agent): String = slotFor(agent)?.displayName ?: agent.name.ifBlank { agent.id }

    /**
     * Returns the daemon-facing provider string for [agent].
     *
     * Slot-backed rows use their explicit Rust provider mapping. Legacy rows
     * are normalized through the Kotlin provider registry first.
     */
    fun configProvider(agent: Agent): String = slotFor(agent)?.rustProvider ?: configProvider(agent.provider)

    /**
     * Normalizes a provider ID for daemon config output.
     *
     * Example: `google-gemini` becomes `gemini`.
     */
    fun configProvider(provider: String): String {
        if (provider.isBlank()) return ""
        val canonical =
            ProviderRegistry.findById(provider.trim())?.id
                ?: provider.trim().lowercase()
        return ProviderSlotRegistry
            .all()
            .firstOrNull { it.providerRegistryId == canonical }
            ?.rustProvider
            ?: canonical
    }

    /**
     * Returns true when [provider] has a usable API key, base URL, or an
     * unexpired auth profile for a slot that still routes model traffic.
     */
    fun hasUsableProviderCredentials(
        provider: String,
        apiKey: ApiKey?,
        authProfiles: List<FfiAuthProfile>,
    ): Boolean =
        hasDirectProviderCredentials(apiKey) ||
            hasManagedAuthProfile(
                provider = provider,
                authProfiles = authProfiles,
                providerFilter = ::providerSupportsRoutingOAuth,
            )

    /**
     * Returns true when [provider] has credentials the live daemon can use today.
     *
     * This is stricter than [hasUsableProviderCredentials]. It excludes managed
     * ChatGPT and Claude logins until the daemon grows dedicated provider
     * transports for those session-backed flows.
     */
    fun hasUsableDaemonProviderCredentials(
        provider: String,
        apiKey: ApiKey?,
        authProfiles: List<FfiAuthProfile>,
    ): Boolean =
        hasDirectProviderCredentials(apiKey) ||
            hasManagedAuthProfile(
                provider = provider,
                authProfiles = authProfiles,
                providerFilter = ::providerSupportsDaemonManagedAuth,
            )

    /**
     * Returns a user-facing label for a connected managed-login profile, if present.
     *
     * This is used to explain why a connected login may still not satisfy live
     * daemon routing requirements.
     */
    fun connectedManagedAuthDisplayLabel(
        provider: String,
        authProfiles: List<FfiAuthProfile>,
    ): String? {
        val authProvider = AuthProfileStore.authProfileProviderFor(provider) ?: return null
        if (!hasConnectedAuthProvider(authProvider, authProfiles)) {
            return null
        }
        return when (authProvider) {
            "openai-codex" -> "ChatGPT"
            "anthropic" -> "Claude Code"
            "gemini" -> "Google account"
            else -> null
        }
    }

    private fun slotFor(agent: Agent) =
        ProviderSlotRegistry.findById(
            agent.slotId.takeIf { it.isNotBlank() } ?: agent.id,
        )

    private fun hasConnectedAuthProvider(
        authProvider: String,
        authProfiles: List<FfiAuthProfile>,
    ): Boolean {
        val now = System.currentTimeMillis()
        return authProfiles.any { profile ->
            val expiresAtMs = profile.expiresAtMs
            profile.provider == authProvider &&
                (expiresAtMs == null || expiresAtMs > now)
        }
    }

    private fun hasManagedAuthProfile(
        provider: String,
        authProfiles: List<FfiAuthProfile>,
        providerFilter: (String) -> Boolean,
    ): Boolean {
        if (!providerFilter(provider)) {
            return false
        }
        val authProvider = AuthProfileStore.authProfileProviderFor(provider) ?: return false
        return hasConnectedAuthProvider(authProvider, authProfiles)
    }

    private fun providerSupportsRoutingOAuth(provider: String): Boolean {
        val authProvider = AuthProfileStore.authProfileProviderFor(provider) ?: return false
        return ProviderSlotRegistry
            .all()
            .any { slot ->
                slot.credentialType == SlotCredentialType.OAUTH &&
                    slot.routesModelRequests &&
                    slot.authProfileProvider == authProvider
            }
    }

    /**
     * Whether a provider supports daemon-routed managed auth.
     *
     * Anthropic OAuth tokens (`sk-ant-oat01-...`) can be passed as the
     * `api_key` in the daemon TOML config. The upstream Anthropic provider
     * detects the `sk-ant-oat01-` prefix and switches to Bearer auth with
     * the `anthropic-beta: oauth-2025-04-20` header automatically.
     *
     * Google account OAuth is scoped to Workspace apps and ChatGPT needs
     * a dedicated daemon transport that has not landed yet.
     */
    private fun providerSupportsDaemonManagedAuth(provider: String): Boolean = provider == "anthropic"
}
