/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.oauth

import android.content.Context
import com.zeroclaw.ffi.FfiAuthProfile
import com.zeroclaw.ffi.listAuthProfilesStandalone
import com.zeroclaw.ffi.removeAuthProfileStandalone
import com.zeroclaw.ffi.writeAuthProfile

/**
 * Facade for persisting OAuth credentials to the encrypted Rust-owned auth-profile store
 * in the app's files directory.
 *
 * All writes and removes are delegated to the Rust FFI layer, which owns the
 * persistence logic including atomic writes, JSON schema management, and
 * active-profile tracking.
 */
object AuthProfileWriter {
    /** Provider name for the default OpenAI Codex (ChatGPT) OAuth profile. */
    private const val PROVIDER = "openai-codex"

    /** Provider name for Anthropic, matching the upstream factory registration. */
    private const val ANTHROPIC_PROVIDER = "anthropic"

    /** Profile name within the provider namespace. */
    private const val PROFILE_NAME = "default"

    /**
     * Lists stored auth profiles directly from the app-owned encrypted Rust store.
     *
     * @param context Android context for resolving [Context.getFilesDir].
     * @return All persisted auth profiles for the current app user.
     */
    @Synchronized
    fun listProfiles(context: Context): List<FfiAuthProfile> =
        listAuthProfilesStandalone(
            dataDir = context.filesDir.absolutePath,
        )

    /**
     * Writes (or updates) the OpenAI Codex OAuth profile in the encrypted auth-profile store.
     *
     * @param context Android context for resolving [Context.getFilesDir].
     * @param accessToken OAuth access token (Bearer token).
     * @param refreshToken OAuth refresh token for automatic renewal.
     * @param expiresAtMs Epoch milliseconds when [accessToken] expires, or null when unknown.
     */
    @Synchronized
    fun writeCodexProfile(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresAtMs: Long?,
    ) {
        writeAuthProfile(
            dataDir = context.filesDir.absolutePath,
            provider = PROVIDER,
            profileName = PROFILE_NAME,
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = null,
            expiresAtMs = expiresAtMs,
            scopes = null,
        )
    }

    /**
     * Removes the OpenAI Codex OAuth profile from the encrypted auth-profile store.
     *
     * @param context Android context for resolving [Context.getFilesDir].
     */
    @Synchronized
    fun removeCodexProfile(context: Context) {
        removeAuthProfileStandalone(
            dataDir = context.filesDir.absolutePath,
            provider = PROVIDER,
            profileName = PROFILE_NAME,
        )
    }

    /**
     * Writes (or updates) the Anthropic OAuth profile in the encrypted auth-profile store.
     *
     * @param context Android context for resolving [Context.getFilesDir].
     * @param accessToken OAuth access token (Bearer token).
     * @param refreshToken OAuth refresh token for automatic renewal.
     * @param expiresAtMs Epoch milliseconds when [accessToken] expires, or null when unknown.
     */
    @Synchronized
    fun writeAnthropicProfile(
        context: Context,
        accessToken: String,
        refreshToken: String,
        expiresAtMs: Long?,
    ) {
        writeAuthProfile(
            dataDir = context.filesDir.absolutePath,
            provider = ANTHROPIC_PROVIDER,
            profileName = PROFILE_NAME,
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = null,
            expiresAtMs = expiresAtMs,
            scopes = null,
        )
    }

    /**
     * Removes the Anthropic OAuth profile from the encrypted auth-profile store.
     *
     * @param context Android context for resolving [Context.getFilesDir].
     */
    @Synchronized
    fun removeAnthropicProfile(context: Context) {
        removeAuthProfileStandalone(
            dataDir = context.filesDir.absolutePath,
            provider = ANTHROPIC_PROVIDER,
            profileName = PROFILE_NAME,
        )
    }
}
