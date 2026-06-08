/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.ssh

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.zeroclaw.android.data.SecurePrefsProvider
import com.zeroclaw.android.data.StorageHealth

/**
 * Encrypted-at-rest store for SSH private keys.
 *
 * Holds each key's OpenSSH-format private PEM as an AES-256-GCM encrypted
 * blob inside the consolidated `zeroai_secure_v1`
 * [androidx.security.crypto.EncryptedSharedPreferences] file — the same
 * Keystore-wrapped [androidx.security.crypto.MasterKey] used for API keys
 * — keyed by the key's UUID. Public metadata (label, fingerprint, public
 * key) lives separately in [SshDataStore]; only the secret bytes live here.
 *
 * Private keys never touch the filesystem in plaintext: the in-app
 * ssh-agent receives the decrypted bytes just-in-time over the FFI.
 *
 * @param context Application context for the EncryptedSharedPreferences
 *   initialisation. Required unless [prefsOverride] is supplied.
 * @param prefsOverride Optional pre-built [SharedPreferences] for tests;
 *   when provided, [storageHealth] defaults to [StorageHealth.Healthy].
 */
class EncryptedSshKeyStore(
    context: Context? = null,
    prefsOverride: SharedPreferences? = null,
) {
    /** Health of the encrypted backing store (Healthy / Recovered / Degraded). */
    val storageHealth: StorageHealth

    private val prefs: SharedPreferences

    init {
        if (prefsOverride != null) {
            prefs = prefsOverride
            storageHealth = StorageHealth.Healthy
        } else {
            requireNotNull(context) { "context required when prefsOverride is null" }
            val (created, health) = SecurePrefsProvider.create(context, PREFS_NAME)
            prefs = created
            storageHealth = health
        }
    }

    /**
     * Encrypts and stores the OpenSSH private PEM [pem] under [keyId],
     * overwriting any existing entry.
     *
     * @param keyId UUID identifying the key (shared with [SshDataStore]).
     * @param pem OpenSSH-format private key bytes to encrypt at rest.
     * @throws IllegalStateException if the encrypted store is unavailable.
     */
    fun put(
        keyId: String,
        pem: ByteArray,
    ) {
        val encoded = Base64.encodeToString(pem, Base64.NO_WRAP)
        check(prefs.edit().putString(keyId, encoded).commit()) {
            "Encrypted storage unavailable: unable to store SSH key"
        }
    }

    /**
     * Returns the decrypted OpenSSH private PEM bytes for [keyId], or
     * `null` when no key is stored under that id.
     *
     * @param keyId UUID identifying the key.
     * @return Decrypted private key bytes, or `null` if absent.
     */
    fun get(keyId: String): ByteArray? = prefs.getString(keyId, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /**
     * Returns the ids of every stored key.
     *
     * @return Set of stored key UUIDs.
     */
    fun keyIds(): Set<String> = prefs.all.keys.toSet()

    /**
     * Returns whether a key is stored under [keyId].
     *
     * @param keyId UUID identifying the key.
     * @return `true` if an encrypted key exists for [keyId].
     */
    fun contains(keyId: String): Boolean = prefs.contains(keyId)

    /**
     * Removes the stored key for [keyId]. Idempotent.
     *
     * @param keyId UUID identifying the key to remove.
     * @throws IllegalStateException if the encrypted store is unavailable.
     */
    fun delete(keyId: String) {
        check(prefs.edit().remove(keyId).commit()) {
            "Encrypted storage unavailable: unable to delete SSH key"
        }
    }

    /** Constants for [EncryptedSshKeyStore]. */
    companion object {
        private const val PREFS_NAME = "zeroclaw_ssh_keys"
    }
}
