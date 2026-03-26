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
import com.zeroclaw.ffi.SshKeyAlgorithm
import com.zeroclaw.ffi.sshDeleteKey
import com.zeroclaw.ffi.sshExportPublicKey
import com.zeroclaw.ffi.sshGenerateKey
import com.zeroclaw.ffi.sshImportKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * Generates a new SSH key and persists its metadata.
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
                val meta =
                    withContext(Dispatchers.IO) {
                        sshGenerateKey(algorithm, label)
                    }
                dataStore.addKey(meta.toEntry())
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
            try {
                val tempFile =
                    withContext(Dispatchers.IO) {
                        val dest = File(app.cacheDir, "ssh_import_temp")
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: error("Cannot read selected file")
                        dest
                    }
                passphraseBytes =
                    passphrase?.let { chars ->
                        val encoder = Charsets.UTF_8.newEncoder()
                        val buf = encoder.encode(java.nio.CharBuffer.wrap(chars))
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                val meta =
                    withContext(Dispatchers.IO) {
                        sshImportKey(
                            tempFile.absolutePath,
                            passphraseBytes,
                            label,
                        )
                    }
                dataStore.addKey(meta.toEntry())
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            } finally {
                passphrase?.fill('\u0000')
                passphraseBytes?.fill(0)
                _isLoading.value = false
            }
        }
    }

    /**
     * Deletes a key from both Rust storage and the DataStore.
     *
     * @param keyId UUID of the key to delete.
     */
    @Suppress("TooGenericExceptionCaught")
    fun deleteKey(keyId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { sshDeleteKey(keyId) }
                dataStore.removeKey(keyId)
            } catch (e: Exception) {
                _error.value = "Delete failed: ${e.message}"
            }
        }
    }

    /**
     * Returns the public key in OpenSSH format for clipboard copy.
     *
     * @param keyId UUID of the key.
     * @return OpenSSH public key string.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun getPublicKey(keyId: String): String? =
        try {
            withContext(Dispatchers.IO) { sshExportPublicKey(keyId) }
        } catch (e: Exception) {
            _error.value = "Export failed: ${e.message}"
            null
        }
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
                SshKeyAlgorithm.ED25519 -> "ed25519"
                SshKeyAlgorithm.RSA4096 -> "rsa4096"
            },
        label = label,
        fingerprintSha256 = fingerprintSha256,
        publicKeyOpenssh = publicKeyOpenssh,
        createdAtEpochMs = createdAtEpochMs,
    )
