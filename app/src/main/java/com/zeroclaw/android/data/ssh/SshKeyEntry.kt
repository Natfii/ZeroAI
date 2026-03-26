/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.ssh

import kotlinx.serialization.Serializable

/**
 * Metadata for a stored SSH key.
 *
 * Contains only public information — private key material is stored
 * exclusively in Rust under `filesDir/ssh/keys/`.
 *
 * @property keyId Unique identifier (UUID v4) matching the Rust-side filename.
 * @property algorithm Key algorithm ("ed25519" or "rsa4096").
 * @property label User-assigned label for display.
 * @property fingerprintSha256 SHA-256 fingerprint in `SHA256:<base64>` format.
 * @property publicKeyOpenssh Public key in OpenSSH format.
 * @property createdAtEpochMs Creation timestamp as milliseconds since Unix epoch.
 */
@Serializable
data class SshKeyEntry(
    val keyId: String,
    val algorithm: String,
    val label: String,
    val fingerprintSha256: String,
    val publicKeyOpenssh: String,
    val createdAtEpochMs: Long,
)
