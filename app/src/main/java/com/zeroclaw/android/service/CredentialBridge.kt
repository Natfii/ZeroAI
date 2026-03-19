/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.util.Log
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.ffi.FfiCredentialResolver
import kotlinx.coroutines.runBlocking

/**
 * Bridges Rust credential resolution to Android's encrypted key storage.
 *
 * Implements [FfiCredentialResolver] so that the Rust daemon can lazily
 * fetch per-provider API keys from [ApiKeyRepository] (backed by
 * `EncryptedSharedPreferences`). Registered at daemon start alongside
 * [EventBridge].
 *
 * @param apiKeyRepository The repository that decrypts and serves API keys.
 */
class CredentialBridge(
    private val apiKeyRepository: ApiKeyRepository,
) : FfiCredentialResolver {
    /**
     * Resolves the API key for [provider] from encrypted storage.
     *
     * Called from Rust background threads. Uses [runBlocking] because:
     * - Never called from the main thread
     * - [ApiKeyRepository.getByProviderFresh] reads from in-memory
     *   `StateFlow` (fast path) and only does network I/O for OAuth
     *   token refresh (rare)
     * - The Rust-side cache means each provider is resolved at most
     *   once per daemon lifecycle
     *
     * @param provider Provider name (e.g. "anthropic", "gemini").
     * @return Decrypted API key, or empty string if not stored.
     */
    override fun resolveCredential(provider: String): String =
        runBlocking {
            val key = apiKeyRepository.getByProviderFresh(provider)?.key.orEmpty()
            if (key.isNotEmpty()) {
                Log.d(TAG, "Resolved credential for provider: $provider")
            }
            key
        }

    /**
     * Registers this bridge with the Rust FFI layer.
     *
     * Call after [com.zeroclaw.ffi.startDaemon] succeeds.
     */
    fun register() {
        com.zeroclaw.ffi.registerCredentialResolver(this)
    }

    /**
     * Unregisters this bridge and clears the Rust credential cache.
     *
     * Call before or alongside [com.zeroclaw.ffi.stopDaemon].
     */
    fun unregister() {
        com.zeroclaw.ffi.unregisterCredentialResolver()
    }

    /** Constants for [CredentialBridge]. */
    companion object {
        private const val TAG = "CredentialBridge"
    }
}
