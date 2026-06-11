/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.ssh

import kotlinx.serialization.Serializable

/**
 * Metadata for a stored SSH key.
 *
 * Contains only public information — private key material is encrypted at
 * rest in [EncryptedSshKeyStore] and held in the in-app ssh-agent for the
 * session.
 *
 * @property keyId Unique identifier (UUID v4) shared with [EncryptedSshKeyStore].
 * @property algorithm Key algorithm ("ed25519" or "rsa4096").
 * @property label User-assigned label for display.
 * @property fingerprintSha256 SHA-256 fingerprint in `SHA256:<base64>` format.
 * @property publicKeyOpenssh Public key in OpenSSH format.
 * @property createdAtEpochMs Creation timestamp as milliseconds since Unix epoch.
 * @property isHardware Whether the private key is sealed in the Android Keystore
 *   (StrongBox/TEE) and signs inside the secure element, with no exportable bytes.
 * @property keystoreAlias Android Keystore entry alias for a hardware key, or `null`
 *   for a software key whose encrypted bytes live in [EncryptedSshKeyStore].
 */
@Serializable
data class SshKeyEntry(
    val keyId: String,
    val algorithm: String,
    val label: String,
    val fingerprintSha256: String,
    val publicKeyOpenssh: String,
    val createdAtEpochMs: Long,
    val isHardware: Boolean = false,
    val keystoreAlias: String? = null,
)
