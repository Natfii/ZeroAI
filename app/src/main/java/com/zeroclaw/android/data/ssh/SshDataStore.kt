/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.ssh

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zeroclaw.ffi.sshKeyExists
import io.github.osipxd.security.crypto.encryptedPreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Preferences DataStore key for the serialized key list. */
private val KEY_SSH_KEYS = stringPreferencesKey("ssh_keys")

/** JSON codec for serializing key entries. */
private val json = Json { ignoreUnknownKeys = true }

/** Encrypted DataStore delegate for SSH metadata. */
private val Context.sshStore by encryptedPreferencesDataStore(name = "ssh_store")

/**
 * Encrypted persistent store for SSH key metadata.
 *
 * Backed by Preferences DataStore with Tink StreamingAead encryption.
 * Private key material is never stored here — only public metadata
 * (labels, fingerprints, algorithm, timestamps).
 *
 * @param context Application context for DataStore access.
 */
class SshDataStore(
    private val context: Context,
) {
    /** Observable list of stored SSH key entries. */
    val keys: Flow<List<SshKeyEntry>> =
        context.sshStore.data.map { prefs ->
            val raw = prefs[KEY_SSH_KEYS] ?: return@map emptyList()
            json.decodeFromString<List<SshKeyEntry>>(raw)
        }

    /**
     * Adds a key entry to the store.
     *
     * @param entry Metadata to persist.
     */
    suspend fun addKey(entry: SshKeyEntry) {
        context.sshStore.edit { prefs ->
            val current =
                prefs[KEY_SSH_KEYS]
                    ?.let { json.decodeFromString<List<SshKeyEntry>>(it) }
                    ?: emptyList()
            prefs[KEY_SSH_KEYS] = json.encodeToString(current + entry)
        }
    }

    /**
     * Removes a key entry by its [keyId].
     *
     * @param keyId The UUID of the key to remove.
     */
    suspend fun removeKey(keyId: String) {
        context.sshStore.edit { prefs ->
            val current =
                prefs[KEY_SSH_KEYS]
                    ?.let { json.decodeFromString<List<SshKeyEntry>>(it) }
                    ?: return@edit
            prefs[KEY_SSH_KEYS] =
                json.encodeToString(
                    current.filter { it.keyId != keyId },
                )
        }
    }

    /**
     * Removes entries whose Rust-side key file no longer exists.
     *
     * Called at startup to recover from external file deletion
     * or app data corruption.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun pruneStaleKeys() {
        context.sshStore.edit { prefs ->
            val current =
                prefs[KEY_SSH_KEYS]
                    ?.let { json.decodeFromString<List<SshKeyEntry>>(it) }
                    ?: return@edit
            val valid =
                current.filter { entry ->
                    try {
                        sshKeyExists(entry.keyId)
                    } catch (_: Exception) {
                        false
                    }
                }
            if (valid.size != current.size) {
                prefs[KEY_SSH_KEYS] = json.encodeToString(valid)
            }
        }
    }

    /** Clears all stored key metadata. */
    suspend fun clear() {
        context.sshStore.edit { it.remove(KEY_SSH_KEYS) }
    }
}
