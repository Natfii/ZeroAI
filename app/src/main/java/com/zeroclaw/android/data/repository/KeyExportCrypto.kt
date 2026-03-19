/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject

/**
 * Cryptographic utility for encrypting and decrypting API key export payloads.
 *
 * New exports use Argon2id plus AES-256-GCM with explicit versioned metadata
 * so future KDF upgrades remain backward compatible. Legacy PBKDF2 payloads
 * remain decryptable for imports created by older builds.
 */
internal object KeyExportCrypto {
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12
    private const val KEY_LENGTH_BITS = 256
    private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
    private const val LEGACY_PBKDF2_ITERATIONS = 600_000
    private const val ARGON2_MEMORY_KIB = 19 * 1024
    private const val ARGON2_ITERATIONS = 2
    private const val ARGON2_PARALLELISM = 1
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val LEGACY_KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val KEY_ALGORITHM = "AES"
    private const val FORMAT_VERSION = 2
    private const val KDF_NAME = "argon2id"

    /**
     * Minimum number of bytes in a valid legacy encrypted payload after Base64 decoding.
     */
    private const val MIN_LEGACY_PAYLOAD_BYTES = SALT_LENGTH_BYTES + IV_LENGTH_BYTES + 1

    /**
     * Encrypts [plaintext] using Argon2id-derived AES-256-GCM.
     *
     * @param plaintext The data to encrypt.
     * @param passphrase User-provided encryption passphrase.
     * @return Base64-encoded versioned payload.
     * @throws GeneralSecurityException if the platform does not support the
     *   required cryptographic algorithms.
     */
    fun encrypt(
        plaintext: String,
        passphrase: String,
    ): String {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().apply {
            nextBytes(salt)
            nextBytes(iv)
        }

        val secretKey = deriveArgon2idKey(passphrase, salt)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val payload =
            JSONObject()
                .put("version", FORMAT_VERSION)
                .put("cipher", "aes-256-gcm")
                .put(
                    "kdf",
                    JSONObject()
                        .put("name", KDF_NAME)
                        .put("memory_kib", ARGON2_MEMORY_KIB)
                        .put("iterations", ARGON2_ITERATIONS)
                        .put("parallelism", ARGON2_PARALLELISM)
                        .put("salt_b64", Base64.getEncoder().encodeToString(salt)),
                ).put("iv_b64", Base64.getEncoder().encodeToString(iv))
                .put("ciphertext_b64", Base64.getEncoder().encodeToString(ciphertext))

        return Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Decrypts a payload produced by [encrypt] or by the legacy PBKDF2 format.
     *
     * @param encryptedPayload Base64-encoded encrypted data.
     * @param passphrase The passphrase used during encryption.
     * @return The original plaintext string.
     * @throws IllegalArgumentException if the payload is malformed.
     * @throws GeneralSecurityException if decryption fails.
     */
    fun decrypt(
        encryptedPayload: String,
        passphrase: String,
    ): String {
        val payload = Base64.getDecoder().decode(encryptedPayload)
        val asJson = payload.toString(Charsets.UTF_8)
        return if (asJson.startsWith("{")) {
            decryptVersionedPayload(JSONObject(asJson), passphrase)
        } else {
            decryptLegacyPayload(payload, passphrase)
        }
    }

    private fun decryptVersionedPayload(
        payload: JSONObject,
        passphrase: String,
    ): String {
        require(payload.optInt("version", 0) == FORMAT_VERSION) {
            "Unsupported encrypted payload version"
        }
        val kdf = payload.getJSONObject("kdf")
        require(kdf.getString("name") == KDF_NAME) {
            "Unsupported key derivation function"
        }

        val salt = Base64.getDecoder().decode(kdf.getString("salt_b64"))
        val iv = Base64.getDecoder().decode(payload.getString("iv_b64"))
        val ciphertext = Base64.getDecoder().decode(payload.getString("ciphertext_b64"))
        val secretKey =
            deriveArgon2idKey(
                passphrase = passphrase,
                salt = salt,
                memoryKib = kdf.optInt("memory_kib", ARGON2_MEMORY_KIB),
                iterations = kdf.optInt("iterations", ARGON2_ITERATIONS),
                parallelism = kdf.optInt("parallelism", ARGON2_PARALLELISM),
            )

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun decryptLegacyPayload(
        payload: ByteArray,
        passphrase: String,
    ): String {
        require(payload.size >= MIN_LEGACY_PAYLOAD_BYTES) {
            "Encrypted payload is too short to contain valid data"
        }

        val salt = payload.copyOfRange(0, SALT_LENGTH_BYTES)
        val iv = payload.copyOfRange(SALT_LENGTH_BYTES, SALT_LENGTH_BYTES + IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(SALT_LENGTH_BYTES + IV_LENGTH_BYTES, payload.size)
        val secretKey = deriveLegacyPbkdf2Key(passphrase, salt)

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun deriveArgon2idKey(
        passphrase: String,
        salt: ByteArray,
        memoryKib: Int = ARGON2_MEMORY_KIB,
        iterations: Int = ARGON2_ITERATIONS,
        parallelism: Int = ARGON2_PARALLELISM,
    ): SecretKeySpec {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .build(),
        )
        val keyBytes = ByteArray(KEY_LENGTH_BYTES)
        generator.generateBytes(passphrase.toCharArray(), keyBytes)
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    private fun deriveLegacyPbkdf2Key(
        passphrase: String,
        salt: ByteArray,
    ): SecretKeySpec {
        val keySpec =
            PBEKeySpec(
                passphrase.toCharArray(),
                salt,
                LEGACY_PBKDF2_ITERATIONS,
                KEY_LENGTH_BITS,
            )
        val factory = SecretKeyFactory.getInstance(LEGACY_KEY_DERIVATION_ALGORITHM)
        val keyBytes = factory.generateSecret(keySpec).encoded
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }
}
