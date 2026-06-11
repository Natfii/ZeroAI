/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.ssh

import android.util.Log
import com.zeroclaw.ffi.sshAgentAddHardwareIdentity
import com.zeroclaw.ffi.sshAgentAddIdentity
import com.zeroclaw.ffi.sshAgentRegisterHwSigner
import com.zeroclaw.ffi.sshAgentStart
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.flow.first

/**
 * Brings the in-process ssh-agent up for the session and reconciles
 * on-disk key state with the encrypted-at-rest store.
 *
 * Startup splits into two phases so the decrypt step can be gated behind the
 * device-credential app lock (the agent-holds-keys ⟺ app-unlocked invariant):
 *
 *  - [prepareAgent] runs the lock-independent work that may always run: a
 *    one-time migration of legacy plaintext `<keyId>.pem` files into
 *    [keyStore] (with strict verify-before-delete so a plaintext key is never
 *    destroyed unless its encrypted copy reads back byte-for-byte), starting
 *    the agent on `<sshDir>/agent.sock`, and pruning orphaned metadata. No
 *    private key is decrypted here, so it is safe to call while the app is
 *    locked — the agent simply holds no identities yet.
 *  - [loadKeysIntoAgent] is the decrypt step: it reads every stored private
 *    key and adds it to the running agent. It must only be invoked once the
 *    app is unlocked, since it materialises plaintext key bytes in the agent.
 *
 * Both phases are idempotent and crash-safe: the agent start is idempotent in
 * Rust, the migration is gated by a marker file, and a failure to load any
 * single key is logged and skipped rather than aborting.
 *
 * @param sshDir Base SSH directory (`filesDir/ssh`) that hosts the agent
 *   socket and the migration marker.
 * @param keyStore Encrypted-at-rest store for SSH private key bytes.
 * @param dataStore Metadata store reconciled against [keyStore] at startup.
 * @param hardwareStore Android Keystore operations backing hardware
 *   identities; its signer callback is registered during [prepareAgent].
 */
class SshAgentInitializer(
    private val sshDir: File,
    private val keyStore: EncryptedSshKeyStore,
    private val dataStore: SshDataStore,
    private val hardwareStore: HardwareSshKeyStore,
) {
    /**
     * Runs the lock-independent agent bring-up: migration, agent start, and
     * metadata prune. Does NOT decrypt or load any keys.
     *
     * Safe to call from a background dispatcher while the app is still locked.
     * Each phase is independently guarded so a single failure cannot leave the
     * agent unstarted: a migration error is logged and the agent still starts;
     * a prune error is logged and ignored.
     *
     * @return `true` if the agent socket is up so [loadKeysIntoAgent] can run,
     *   `false` if the agent failed to start.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun prepareAgent(): Boolean {
        runCatching { migratePlaintextKeys() }
            .onFailure { error ->
                Log.e(TAG, "SSH key migration failed: ${error.message}")
            }

        val started =
            runCatching { sshAgentStart(sshDir.absolutePath) }
                .onFailure { error ->
                    Log.e(TAG, "SSH agent start failed: ${error.message}")
                }.isSuccess
        if (!started) return false

        runCatching { sshAgentRegisterHwSigner(HardwareSshSigner(hardwareStore)) }
            .onFailure { error ->
                Log.e(TAG, "SSH hardware signer registration failed: ${error.message}")
            }

        runCatching { dataStore.pruneStaleKeys(keyStore.keyIds()) }
            .onFailure { error ->
                Log.w(TAG, "SSH metadata prune failed: ${error.message}")
            }
        return true
    }

    /**
     * Decrypts every stored software key and registers every hardware
     * identity with the running agent.
     *
     * Must only run once the app is unlocked — this is the step that
     * materialises plaintext software key bytes in the live agent (hardware
     * keys contribute only their public halves; their private keys stay in
     * the Keystore and additionally cannot sign while the device is locked).
     * A per-key failure is logged and skipped so one bad key cannot block
     * the rest of the session's keys; each decrypted buffer is zeroed after
     * its add.
     *
     * Safe to call from a background dispatcher.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun loadKeysIntoAgent() {
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

        var hardware = 0
        val hardwareEntries =
            dataStore.keys
                .first()
                .filter { it.isHardware }
                .mapNotNull { entry -> entry.keystoreAlias?.let { alias -> entry to alias } }
        for ((entry, alias) in hardwareEntries) {
            if (!hardwareStore.contains(alias)) {
                Log.w(
                    TAG,
                    "Hardware SSH key ${entry.keyId} missing from Keystore " +
                        "(invalidated or wiped); skipping registration",
                )
                continue
            }
            try {
                sshAgentAddHardwareIdentity(entry.publicKeyOpenssh, alias, entry.label)
                hardware += 1
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register hardware SSH key ${entry.keyId}: ${e.message}")
            }
        }
        Log.i(TAG, "Loaded $loaded software + $hardware hardware SSH key(s) into the agent")
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
