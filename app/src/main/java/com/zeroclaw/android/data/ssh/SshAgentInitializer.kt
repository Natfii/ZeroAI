/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.ssh

import android.util.Log
import com.zeroclaw.ffi.sshAgentAddIdentity
import com.zeroclaw.ffi.sshAgentStart
import java.io.File
import java.security.SecureRandom

/**
 * Brings the in-process ssh-agent up for the session and reconciles
 * on-disk key state with the encrypted-at-rest store.
 *
 * Owns three startup responsibilities that must run before any terminal
 * shell forks (so the bundled `ssh` can authenticate via `SSH_AUTH_SOCK`):
 *
 *  1. A one-time migration of any legacy plaintext `<keyId>.pem` files left
 *     under [keysDir] by older builds into [keyStore], with a strict
 *     verify-before-delete so a plaintext key is never destroyed unless its
 *     encrypted copy reads back byte-for-byte.
 *  2. Starting the agent on `<sshDir>/agent.sock`.
 *  3. Decrypting every stored key and loading it into the running agent so
 *     it is held for the session.
 *
 * [initialize] is idempotent and crash-safe: the agent start is idempotent
 * in Rust, the migration is gated by a marker file, and a failure to load
 * any single key is logged and skipped rather than aborting startup.
 *
 * @param sshDir Base SSH directory (`filesDir/ssh`) that hosts the agent
 *   socket and the migration marker.
 * @param keyStore Encrypted-at-rest store for SSH private key bytes.
 * @param dataStore Metadata store reconciled against [keyStore] at startup.
 */
class SshAgentInitializer(
    private val sshDir: File,
    private val keyStore: EncryptedSshKeyStore,
    private val dataStore: SshDataStore,
) {
    /**
     * Runs the migration, starts the agent, loads all keys, and prunes
     * orphaned metadata.
     *
     * Safe to call from a background dispatcher. Each phase is independently
     * guarded so a single failure cannot leave the agent unstarted: a
     * migration error is logged and the agent still starts; a per-key load
     * error is logged and remaining keys still load.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun initialize() {
        runCatching { migratePlaintextKeys() }
            .onFailure { error ->
                Log.e(TAG, "SSH key migration failed: ${error.message}")
            }

        val started =
            runCatching { sshAgentStart(sshDir.absolutePath) }
                .onFailure { error ->
                    Log.e(TAG, "SSH agent start failed: ${error.message}")
                }.isSuccess
        if (!started) return

        loadStoredKeysIntoAgent()
        runCatching { dataStore.pruneStaleKeys(keyStore.keyIds()) }
            .onFailure { error ->
                Log.w(TAG, "SSH metadata prune failed: ${error.message}")
            }
    }

    /**
     * Decrypts every stored key and adds it to the running agent.
     *
     * A per-key failure (corrupt blob, parse error) is logged and skipped so
     * one bad key cannot block the rest of the session's keys from loading.
     * The decrypted buffer is zeroed after each add.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun loadStoredKeysIntoAgent() {
        var loaded = 0
        for (keyId in keyStore.keyIds()) {
            val pem = keyStore.get(keyId) ?: continue
            try {
                sshAgentAddIdentity(pem)
                loaded += 1
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load SSH key $keyId into agent: ${e.message}")
            } finally {
                pem.fill(0)
            }
        }
        Log.i(TAG, "Loaded $loaded SSH key(s) into the session agent")
    }

    /**
     * Migrates legacy plaintext `<keyId>.pem` files into the encrypted store.
     *
     * Gated by a [MIGRATION_MARKER] file so it runs at most once. For each
     * plaintext key the bytes are read, stored via [EncryptedSshKeyStore.put],
     * and verified by reading them back and comparing byte-for-byte; only on a
     * verified match is the plaintext file securely overwritten and deleted. A
     * key whose encrypted copy does not verify is left on disk untouched and
     * counted as failed. The marker is written even when some keys fail so the
     * scan does not repeat every launch; surviving plaintext files remain
     * available for manual recovery.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun migratePlaintextKeys() {
        val marker = File(sshDir, MIGRATION_MARKER)
        if (marker.exists()) return

        val keysDir = File(sshDir, LEGACY_KEYS_SUBDIR)
        val pemFiles = keysDir.listFiles { file -> file.isFile && file.name.endsWith(PEM_SUFFIX) }
        if (pemFiles == null || pemFiles.isEmpty()) {
            writeMarker(marker)
            return
        }

        var migrated = 0
        var failed = 0
        for (pemFile in pemFiles) {
            val keyId = pemFile.name.removeSuffix(PEM_SUFFIX)
            if (migratePlaintextKey(keyId, pemFile)) migrated += 1 else failed += 1
        }
        writeMarker(marker)
        Log.i(TAG, "SSH plaintext migration: $migrated migrated, $failed failed/skipped")
    }

    /**
     * Migrates a single plaintext key, returning whether it was migrated and
     * its plaintext file securely deleted.
     *
     * Stores the bytes, reads them back, and only deletes the plaintext file
     * when the round-trip matches exactly. The plaintext and read-back buffers
     * are zeroed before returning regardless of outcome.
     *
     * @param keyId UUID parsed from the plaintext filename.
     * @param pemFile Legacy plaintext key file.
     * @return `true` if encrypted and verified (plaintext deleted), else
     *   `false` (plaintext left untouched).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun migratePlaintextKey(
        keyId: String,
        pemFile: File,
    ): Boolean {
        val plaintext = pemFile.readBytes()
        var readBack: ByteArray? = null
        return try {
            if (keyStore.contains(keyId)) {
                Log.i(TAG, "SSH key $keyId already encrypted; removing plaintext")
                securelyDelete(pemFile)
                return true
            }
            keyStore.put(keyId, plaintext)
            readBack = keyStore.get(keyId)
            if (readBack != null && readBack.contentEquals(plaintext)) {
                securelyDelete(pemFile)
                true
            } else {
                Log.w(TAG, "SSH key $keyId failed verify-after-encrypt; keeping plaintext")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSH key $keyId migration error: ${e.message}; keeping plaintext")
            false
        } finally {
            plaintext.fill(0)
            readBack?.fill(0)
        }
    }

    /**
     * Overwrites [file] with random bytes, then deletes it.
     *
     * Best-effort defence against the plaintext key lingering in free blocks
     * after deletion. Failure to delete is logged, not thrown.
     *
     * @param file Plaintext key file to scrub and remove.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun securelyDelete(file: File) {
        try {
            val length = file.length().toInt().coerceAtLeast(0)
            if (length > 0) {
                val noise = ByteArray(length).also { SecureRandom().nextBytes(it) }
                file.outputStream().use { it.write(noise) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scrub ${file.name}: ${e.message}")
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete plaintext key ${file.name}")
        }
    }

    /**
     * Writes the one-time migration marker, logging on failure.
     *
     * @param marker Marker file under [sshDir].
     */
    @Suppress("TooGenericExceptionCaught")
    private fun writeMarker(marker: File) {
        try {
            marker.parentFile?.mkdirs()
            marker.writeText(MIGRATION_MARKER_BODY)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write SSH migration marker: ${e.message}")
        }
    }

    /** Constants for [SshAgentInitializer]. */
    companion object {
        private const val TAG = "SshAgentInit"
        private const val LEGACY_KEYS_SUBDIR = "keys"
        private const val PEM_SUFFIX = ".pem"
        private const val MIGRATION_MARKER = ".keys_migrated"
        private const val MIGRATION_MARKER_BODY = "1"
    }
}
