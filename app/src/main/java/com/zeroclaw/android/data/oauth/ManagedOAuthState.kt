/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.oauth

import android.content.Context
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.model.ApiKey
import java.util.UUID
import kotlinx.coroutines.flow.first

/** Canonical provider IDs backed by the Rust auth-profile store. */
private val ManagedProviderIds = setOf("anthropic", "openai-codex", "google-gemini")

/** Returns the canonical provider ID for OAuth providers backed by auth profiles. */
fun canonicalManagedProvider(provider: String): String? =
    when (provider.trim().lowercase()) {
        "anthropic", "claude", "claude-code" -> "anthropic"
        "openai-codex", "openai_codex", "codex", "openai" -> "openai-codex"
        "google-gemini", "gemini", "google", "vertex" -> "google-gemini"
        else -> null
    }

/** Returns the auth-profile provider key used by Rust for the managed provider. */
fun authProfileProviderFor(provider: String): String? =
    when (canonicalManagedProvider(provider)) {
        "google-gemini" -> "gemini"
        else -> canonicalManagedProvider(provider)
    }

/** Returns true when [provider] uses the Rust-managed auth profile store. */
fun isManagedProvider(provider: String): Boolean = canonicalManagedProvider(provider) in ManagedProviderIds

/**
 * Saves lightweight UI metadata for an OAuth-managed provider connection.
 *
 * The record intentionally stores no refresh token and no bearer token because
 * token material is owned by the Rust auth-profile store.
 */
suspend fun saveManagedProviderMetadata(
    repository: ApiKeyRepository,
    provider: String,
    expiresAt: Long = 0L,
) {
    val canonical = canonicalManagedProvider(provider) ?: provider
    val matchingKeys =
        repository.keys
            .first()
            .filter { canonicalManagedProvider(it.provider) == canonical }

    matchingKeys.drop(1).forEach { repository.delete(it.id) }
    val primaryId = matchingKeys.firstOrNull()?.id ?: UUID.randomUUID().toString()
    repository.save(
        ApiKey(
            id = primaryId,
            provider = canonical,
            key = "",
            refreshToken = "",
            expiresAt = expiresAt,
        ),
    )
}

/**
 * Removes all persisted Kotlin-side state for a managed OAuth provider.
 *
 * This clears duplicated repository metadata, repairs default-provider state,
 * and repoints or disables agents that still reference the removed provider.
 */
suspend fun purgeManagedProviderState(
    provider: String,
    keyRepository: ApiKeyRepository,
    settingsRepository: SettingsRepository,
    agentRepository: AgentRepository?,
) {
    val canonical = canonicalManagedProvider(provider) ?: return
    val keysToDelete =
        keyRepository.keys
            .first()
            .filter { canonicalManagedProvider(it.provider) == canonical }
    keysToDelete.forEach { keyRepository.delete(it.id) }

    val remainingKeys = keyRepository.keys.first()
    val fallbackProvider = remainingKeys.firstOrNull()?.provider.orEmpty()
    val settings = settingsRepository.settings.first()
    if (canonicalManagedProvider(settings.defaultProvider) == canonical) {
        settingsRepository.setDefaultProvider(fallbackProvider)
        settingsRepository.setDefaultModel("")
    }

    agentRepository?.let { repository ->
        repository.agents
            .first()
            .filter { canonicalManagedProvider(it.provider) == canonical }
            .forEach { agent ->
                val updated =
                    if (fallbackProvider.isBlank()) {
                        agent.copy(isEnabled = false)
                    } else {
                        agent.copy(provider = fallbackProvider)
                    }
                repository.save(updated)
            }
    }
}

/**
 * Removes stale managed-provider metadata whose canonical auth profile is missing.
 */
suspend fun repairManagedProviderState(
    context: Context,
    keyRepository: ApiKeyRepository,
    settingsRepository: SettingsRepository,
    agentRepository: AgentRepository?,
) {
    val storedProfiles = AuthProfileStore.listStandalone(context).map { it.provider }.toSet()
    ManagedProviderIds.forEach { provider ->
        val authProfileProvider = authProfileProviderFor(provider).orEmpty()
        if (authProfileProvider !in storedProfiles) {
            purgeManagedProviderState(
                provider = provider,
                keyRepository = keyRepository,
                settingsRepository = settingsRepository,
                agentRepository = agentRepository,
            )
        }
    }
}
