/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.Context
import com.zeroclaw.ffi.getAccessTokenStandalone
import com.zeroclaw.ffi.mergeAuthProfileMetadataStandalone
import com.zeroclaw.ffi.removeAuthProfileStandalone
import com.zeroclaw.ffi.writeAuthProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages per-skill credentials via the auth profile system.
 *
 * Uses the `skill::{skillName}` provider namespace in
 * `auth-profiles.json`. All I/O is delegated to Rust via FFI.
 */
object SkillCredentialManager {
    /** Provider prefix for skill-scoped credentials. */
    private const val SKILL_PROVIDER_PREFIX = "skill::"

    /** Profile name used for all skill credentials. */
    private const val DEFAULT_PROFILE = "default"

    /**
     * Stores a credential for a skill.
     *
     * Two-step flow: writes the encrypted token first, then merges
     * metadata (api_base_url, auth_type) as a separate call because
     * [writeAuthProfile] does not accept metadata directly.
     *
     * @param context Android context for file directory resolution.
     * @param skillName Skill identifier (e.g. `"zerofans"`).
     * @param token The bearer token or API key to store.
     * @param metadata Additional config (e.g. `mapOf("api_base_url" to "...", "auth_type" to "bearer")`).
     */
    suspend fun writeSkillCredential(
        context: Context,
        skillName: String,
        token: String,
        metadata: Map<String, String> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val provider = "$SKILL_PROVIDER_PREFIX$skillName"
        val dataDir = context.filesDir.absolutePath
        writeAuthProfile(
            dataDir = dataDir,
            provider = provider,
            profileName = DEFAULT_PROFILE,
            accessToken = token,
            refreshToken = null,
            idToken = null,
            expiresAtMs = null,
            scopes = null,
        )
        if (metadata.isNotEmpty()) {
            mergeAuthProfileMetadataStandalone(
                dataDir = dataDir,
                provider = provider,
                profileName = DEFAULT_PROFILE,
                metadataJson = Json.encodeToString(metadata),
            )
        }
    }

    /**
     * Returns the stored access token for a skill, or `null` if none is present.
     *
     * @param context Android context for file directory resolution.
     * @param skillName Skill identifier.
     * @return The stored token string, or `null` if no credential is found.
     */
    suspend fun getSkillToken(
        context: Context,
        skillName: String,
    ): String? =
        withContext(Dispatchers.IO) {
            getAccessTokenStandalone(
                dataDir = context.filesDir.absolutePath,
                provider = "$SKILL_PROVIDER_PREFIX$skillName",
                profileName = DEFAULT_PROFILE,
            )
        }

    /**
     * Removes the stored credential for a skill.
     *
     * @param context Android context for file directory resolution.
     * @param skillName Skill identifier.
     */
    suspend fun removeSkillCredential(
        context: Context,
        skillName: String,
    ) = withContext(Dispatchers.IO) {
        removeAuthProfileStandalone(
            dataDir = context.filesDir.absolutePath,
            provider = "$SKILL_PROVIDER_PREFIX$skillName",
            profileName = DEFAULT_PROFILE,
        )
    }
}
