/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.oauth

import android.content.Context
import com.zeroclaw.ffi.FfiAuthProfile
import com.zeroclaw.ffi.listAuthProfilesStandalone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin helpers for the Rust-owned auth-profile store.
 *
 * This centralizes provider normalization and the standalone metadata query
 * path used before the daemon is running.
 */
object AuthProfileStore {
    private const val OPENAI_PROFILE_PROVIDER = "openai-codex"
    private const val ANTHROPIC_PROFILE_PROVIDER = "anthropic"

    /**
     * Lists stored auth profiles without requiring the daemon to be running.
     *
     * @param context Application context used to resolve [Context.getFilesDir].
     * @return Current auth profiles from the Rust-owned store.
     */
    fun listStandalone(context: Context): List<FfiAuthProfile> = listAuthProfilesStandalone(context.filesDir.absolutePath)

    /**
     * Lists stored auth profiles on [Dispatchers.IO].
     *
     * Callers that are already in a coroutine and do not need an immediate
     * synchronous result should prefer this helper to avoid blocking the
     * main thread while UniFFI reads the Rust-owned store.
     *
     * @param context Application context used to resolve [Context.getFilesDir].
     * @return Current auth profiles from the Rust-owned store.
     */
    suspend fun listStandaloneOnIo(context: Context): List<FfiAuthProfile> =
        withContext(Dispatchers.IO) {
            listStandalone(context)
        }

    /**
     * Returns the Rust auth-profile provider key for an Android provider ID.
     *
     * @param providerId Android provider ID or alias.
     * @return Canonical Rust auth-profile provider key, or null when the
     *   provider does not use auth profiles.
     */
    fun authProfileProviderFor(providerId: String): String? =
        when (providerId.lowercase()) {
            "openai", "openai-codex", "openai_codex", "codex", "chatgpt" -> OPENAI_PROFILE_PROVIDER
            "anthropic" -> ANTHROPIC_PROFILE_PROVIDER
            else -> null
        }

    /**
     * Returns true when [providerId] is backed by the auth-profile store.
     *
     * @param providerId Android provider ID or alias.
     * @return Whether this provider uses Rust-owned auth profiles.
     */
    fun usesAuthProfiles(providerId: String): Boolean = authProfileProviderFor(providerId) != null

    /**
     * Returns the first standalone auth profile for [providerId], if present.
     *
     * @param context Application context used to read the standalone store.
     * @param providerId Android provider ID or alias.
     * @return Matching auth profile, or null when absent.
     */
    fun findStandaloneProfile(
        context: Context,
        providerId: String,
    ): FfiAuthProfile? {
        val authProvider = authProfileProviderFor(providerId) ?: return null
        return listStandalone(context).firstOrNull { it.provider == authProvider }
    }
}
