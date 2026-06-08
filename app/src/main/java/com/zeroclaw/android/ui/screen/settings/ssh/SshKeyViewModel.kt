/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.ssh

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ssh.SshKeyEntry
import com.zeroclaw.ffi.SshGeneratedKey
import com.zeroclaw.ffi.SshKeyAlgorithm
import com.zeroclaw.ffi.sshAgentAddIdentity
import com.zeroclaw.ffi.sshAgentReset
import com.zeroclaw.ffi.sshGenerateKey
import com.zeroclaw.ffi.sshImportKey
import java.io.File
import java.nio.CharBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Timeout before upstream flow collection stops. */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * ViewModel for the SSH key management screen.
 *
 * Bridges the Rust key store (via FFI) with the encrypted
 * [SshDataStore][com.zeroclaw.android.data.ssh.SshDataStore]
 * for metadata persistence.
 *
 * @param application Application context for repository access.
 */
class SshKeyViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val dataStore = app.sshDataStore
    private val keyStore = app.sshKeyStore

    /** Observable list of SSH key entries. */
    val keys: StateFlow<List<SshKeyEntry>> =
        dataStore.keys
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _isLoading = MutableStateFlow(false)

    /** Whether an async operation is in progress. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Last error message, or null. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Clears the current error state. */
    fun clearError() {
        _error.value = null
    }

    /**
     * Generates a new SSH key, encrypts the private bytes at rest, and
     * persists its metadata.
     *
     * @param algorithm Key algorithm to use.
     * @param label User-assigned label.
     */
    @Suppress("TooGenericExceptionCaught")
    fun generateKey(
        algorithm: SshKeyAlgorithm,
        label: String,
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val generated =
                    withContext(Dispatchers.IO) {
                        sshGenerateKey(algorithm, label)
                    }
                storeGeneratedKey(generated)
            } catch (e: Exception) {
                _error.value = "Key generation failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Imports a private key from a SAF URI.
     *
     * Copies the file to app cache via [ContentResolver], passes
     * the path to Rust (which deletes the temp file), then persists
     * metadata.
     *
     * @param uri SAF document URI selected by the user.
     * @param passphrase Optional passphrase as [CharArray], zeroed after use.
     * @param label User-assigned label.
     */
    @Suppress("TooGenericExceptionCaught")
    fun importKey(
        uri: Uri,
        passphrase: CharArray?,
        label: String,
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            var passphraseBytes: ByteArray? = null
            var tempFile: File? = null
            try {
                val dest =
                    withContext(Dispatchers.IO) {
                        File.createTempFile("ssh_import_", ".tmp", app.cacheDir)
                    }
                tempFile = dest
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: error("Cannot read selected file")
                }
                passphraseBytes =
                    passphrase?.let { chars ->
                        val encoder = Charsets.UTF_8.newEncoder()
                        val buf = encoder.encode(CharBuffer.wrap(chars))
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                val generated =
                    withContext(Dispatchers.IO) {
                        sshImportKey(
                            dest.absolutePath,
                            passphraseBytes,
                            label,
                        )
                    }
                storeGeneratedKey(generated)
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            } finally {
                passphrase?.fill('\u0000')
                passphraseBytes?.fill(0)
                tempFile?.delete()
                _isLoading.value = false
            }
        }
    }

    /**
     * Encrypts the private bytes at rest, persists metadata, and adds the
     * key to the running ssh-agent for the current session.
     *
     * The plaintext private buffer is zeroed before returning.
     *
     * @param generated Freshly generated or imported key with private bytes.
     */
    private suspend fun storeGeneratedKey(generated: SshGeneratedKey) {
        val keyId = generated.metadata.keyId
        val privatePem = generated.privatePem
        try {
            withContext(Dispatchers.IO) { keyStore.put(keyId, privatePem) }
            dataStore.addKey(generated.metadata.toEntry())
            // Best-effort: the key is already persisted encrypted, so failing to
            // load it into the live agent self-heals at the next launch (via
            // SshAgentInitializer) instead of losing the key or its metadata.
            runCatching { withContext(Dispatchers.IO) { sshAgentAddIdentity(privatePem) } }
        } finally {
            privatePem.fill(0)
        }
    }

    /**
     * Deletes a key from encrypted storage, metadata, and the running agent.
     *
     * @param keyId UUID of the key to delete.
     */
    @Suppress("TooGenericExceptionCaught")
    fun deleteKey(keyId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    keyStore.delete(keyId)
                    sshAgentReset()
                }
                dataStore.removeKey(keyId)
            } catch (e: Exception) {
                _error.value = "Delete failed: ${e.message}"
            }
        }
    }

    /**
     * Returns the public key in OpenSSH format for clipboard copy.
     *
     * Reads from the metadata store; no FFI call is required.
     *
     * @param keyId UUID of the key.
     * @return OpenSSH public key string, or null if the key is unknown.
     */
    suspend fun getPublicKey(keyId: String): String? =
        dataStore.keys
            .first()
            .firstOrNull { it.keyId == keyId }
            ?.publicKeyOpenssh
}

/**
 * Converts a UniFFI-generated [SshKeyMetadata] to the Kotlin
 * [SshKeyEntry] for DataStore persistence.
 */
private fun com.zeroclaw.ffi.SshKeyMetadata.toEntry() =
    SshKeyEntry(
        keyId = keyId,
        algorithm =
            when (algorithm) {
                SshKeyAlgorithm.ECDSA_P256 -> "ecdsap256"
                SshKeyAlgorithm.ED25519 -> "ed25519"
                SshKeyAlgorithm.RSA4096 -> "rsa4096"
            },
        label = label,
        fingerprintSha256 = fingerprintSha256,
        publicKeyOpenssh = publicKeyOpenssh,
        createdAtEpochMs = createdAtEpochMs,
    )
