/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.ssh

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import com.zeroclaw.ffi.SshKeyMetadata
import com.zeroclaw.ffi.sshHardwareKeyMetadata
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Generates and operates hardware-backed SSH keys sealed in the Android
 * Keystore.
 *
 * The private key is created non-exportable inside StrongBox when the
 * device has one (falling back to the TEE otherwise) and can never be
 * read out — signing happens inside the secure element via
 * [signSha256Ecdsa]. Keys are session-gated with
 * `setUnlockedDeviceRequired`, so they cannot sign while the device is
 * locked, which preserves the agent-holds-keys ⟺ app-unlocked invariant
 * at the hardware level.
 *
 * Only ECDSA P-256 is supported: it is the one algorithm both the
 * Android Keystore (including StrongBox) and the SSH `ecdsa-sha2-nistp256`
 * wire format share at minSdk 28.
 */
class HardwareSshKeyStore {
    /**
     * A freshly generated hardware key.
     *
     * @property metadata Public metadata built by the Rust key store from
     *   the key's EC public point; the private half never leaves the
     *   Keystore.
     * @property keystoreAlias Android Keystore alias the key was generated
     *   under, used for signing and deletion.
     */
    data class GeneratedHardwareKey(
        val metadata: SshKeyMetadata,
        val keystoreAlias: String,
    )

    /**
     * Generates a new EC P-256 keypair in the Android Keystore and returns
     * its SSH metadata.
     *
     * Attempts StrongBox first and falls back to the TEE when the device
     * has no secure element. On metadata-construction failure the orphaned
     * Keystore entry is deleted before rethrowing, so a failed generate
     * leaves no residue.
     *
     * @param label User-assigned label carried into the SSH metadata.
     * @return The generated key's metadata and Keystore alias.
     * @throws java.security.GeneralSecurityException if the Keystore
     *   rejects generation (e.g. no secure lock screen configured).
     */
    @Suppress("TooGenericExceptionCaught")
    fun generate(label: String): GeneratedHardwareKey {
        val keyId = UUID.randomUUID().toString()
        val alias = "$ALIAS_PREFIX$keyId"

        val publicKey =
            try {
                generateKeyPair(alias, strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                Log.i(TAG, "StrongBox unavailable, generating SSH key in TEE: ${e.message}")
                generateKeyPair(alias, strongBox = false)
            }

        try {
            val metadata = sshHardwareKeyMetadata(encodeSec1Point(publicKey), keyId, label)
            return GeneratedHardwareKey(metadata, alias)
        } catch (e: Exception) {
            delete(alias)
            throw e
        }
    }

    /**
     * Signs [data] with the Keystore key under [alias] using
     * `SHA256withECDSA`.
     *
     * The data is hashed by the Keystore as part of the operation; callers
     * pass the raw bytes to sign. The result is a DER-encoded
     * `ECDSA-Sig-Value`, which the Rust agent re-encodes into SSH wire
     * format.
     *
     * @param alias Keystore alias of the signing key.
     * @param data Raw bytes to sign.
     * @return DER-encoded ECDSA signature.
     * @throws java.security.GeneralSecurityException if the key is
     *   missing, the device is locked, or the key has been invalidated.
     */
    fun signSha256Ecdsa(
        alias: String,
        data: ByteArray,
    ): ByteArray {
        val key =
            loadKeyStore().getKey(alias, null) as? PrivateKey
                ?: error("No Keystore key under alias $alias")
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(key)
            update(data)
            sign()
        }
    }

    /**
     * Returns whether a Keystore entry exists under [alias].
     *
     * A `false` for an alias that metadata still references means the key
     * was invalidated outside the app (device wipe, screen-lock removal on
     * some OEMs); callers should skip the identity and surface the loss.
     *
     * @param alias Keystore alias to check.
     */
    fun contains(alias: String): Boolean = runCatching { loadKeyStore().containsAlias(alias) }.getOrDefault(false)

    /**
     * Deletes the Keystore entry under [alias].
     *
     * Idempotent: deleting an absent alias is a no-op.
     *
     * @param alias Keystore alias to delete.
     */
    fun delete(alias: String) {
        runCatching { loadKeyStore().deleteEntry(alias) }
            .onFailure { Log.w(TAG, "Failed to delete Keystore entry $alias: ${it.message}") }
    }

    /**
     * Returns a user-facing security-level label for the key under
     * [alias].
     *
     * @param alias Keystore alias to inspect.
     * @return `"StrongBox"`, `"TEE"`, or `null` when the key is missing or
     *   reports software-only protection (which should not occur for keys
     *   this store generated).
     */
    fun securityLabel(alias: String): String? {
        val keyInfo =
            runCatching {
                val key =
                    loadKeyStore().getKey(alias, null) as? PrivateKey ?: return null
                KeyFactory
                    .getInstance(key.algorithm, KEYSTORE_PROVIDER)
                    .getKeySpec(key, KeyInfo::class.java)
            }.getOrNull() ?: return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> LABEL_STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
                -> LABEL_TEE
                else -> null
            }
        } else {
            @Suppress("DEPRECATION")
            if (keyInfo.isInsideSecureHardware) LABEL_TEE else null
        }
    }

    /**
     * Generates the EC P-256 keypair under [alias] and returns its public
     * key.
     *
     * @param alias Keystore alias to generate under.
     * @param strongBox Whether to request StrongBox backing.
     * @return The generated [ECPublicKey].
     */
    private fun generateKeyPair(
        alias: String,
        strongBox: Boolean,
    ): ECPublicKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUnlockedDeviceRequired(true)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
        val generator =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        generator.initialize(spec)
        return generator.generateKeyPair().public as ECPublicKey
    }

    /**
     * Encodes [publicKey] as an uncompressed SEC1 point:
     * `0x04 ‖ X(32) ‖ Y(32)`.
     *
     * @param publicKey EC public key from the Keystore.
     * @return 65-byte uncompressed point for the Rust metadata builder.
     */
    private fun encodeSec1Point(publicKey: ECPublicKey): ByteArray {
        val x = publicKey.w.affineX.toFixedWidth()
        val y = publicKey.w.affineY.toFixedWidth()
        return byteArrayOf(SEC1_UNCOMPRESSED) + x + y
    }

    /**
     * Converts a positive coordinate to exactly [COORDINATE_BYTES] bytes,
     * stripping `BigInteger`'s sign byte and left-padding with zeros.
     *
     * @receiver P-256 affine coordinate.
     * @return Fixed-width big-endian representation.
     */
    private fun BigInteger.toFixedWidth(): ByteArray {
        val raw = toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(raw.size <= COORDINATE_BYTES) { "EC coordinate wider than P-256" }
        return ByteArray(COORDINATE_BYTES - raw.size) + raw
    }

    /**
     * Opens and loads the Android Keystore.
     *
     * @return Loaded [KeyStore] instance.
     */
    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    /** Constants for [HardwareSshKeyStore]. */
    companion object {
        private const val TAG = "HardwareSshKeyStore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val EC_CURVE = "secp256r1"
        private const val SEC1_UNCOMPRESSED: Byte = 0x04
        private const val COORDINATE_BYTES = 32
        private const val LABEL_STRONGBOX = "StrongBox"
        private const val LABEL_TEE = "TEE"

        /** Keystore alias prefix for SSH keys: `ssh_<keyId>`. */
        const val ALIAS_PREFIX = "ssh_"
    }
}
