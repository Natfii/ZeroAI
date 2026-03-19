/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [KeyExportCrypto].
 */
@DisplayName("KeyExportCrypto")
class KeyExportCryptoTest {
    @Test
    @DisplayName("encrypt round trips with versioned Argon2id payload")
    fun `encrypt round trips with versioned Argon2id payload`() {
        val plaintext = "{\"provider\":\"anthropic\",\"key\":\"sk-ant-secret\"}"

        val encrypted = KeyExportCrypto.encrypt(plaintext, PASSPHRASE)
        val decodedJson = String(Base64.getDecoder().decode(encrypted), Charsets.UTF_8)
        val payload = JSONObject(decodedJson)

        assertEquals(2, payload.getInt("version"))
        assertEquals("aes-256-gcm", payload.getString("cipher"))
        assertEquals("argon2id", payload.getJSONObject("kdf").getString("name"))
        assertEquals(plaintext, KeyExportCrypto.decrypt(encrypted, PASSPHRASE))
    }

    @Test
    @DisplayName("decrypt accepts legacy PBKDF2 payloads")
    fun `decrypt accepts legacy PBKDF2 payloads`() {
        val plaintext = "legacy export payload"
        val encrypted = encryptLegacyPayload(plaintext, PASSPHRASE)

        assertEquals(plaintext, KeyExportCrypto.decrypt(encrypted, PASSPHRASE))
    }

    @Test
    @DisplayName("decrypt rejects unsupported versioned payload")
    fun `decrypt rejects unsupported versioned payload`() {
        val unsupported =
            Base64.getEncoder().encodeToString(
                JSONObject()
                    .put("version", 99)
                    .put("cipher", "aes-256-gcm")
                    .put("kdf", JSONObject().put("name", "argon2id").put("salt_b64", Base64.getEncoder().encodeToString(ByteArray(16))))
                    .put("iv_b64", Base64.getEncoder().encodeToString(ByteArray(12)))
                    .put("ciphertext_b64", Base64.getEncoder().encodeToString(ByteArray(16)))
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            )

        assertThrows(IllegalArgumentException::class.java) {
            KeyExportCrypto.decrypt(unsupported, PASSPHRASE)
        }
    }

    private fun encryptLegacyPayload(
        plaintext: String,
        passphrase: String,
    ): String {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().apply {
            nextBytes(salt)
            nextBytes(iv)
        }

        val keySpec =
            PBEKeySpec(
                passphrase.toCharArray(),
                salt,
                LEGACY_PBKDF2_ITERATIONS,
                KEY_LENGTH_BITS,
            )
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(salt.size + iv.size + ciphertext.size)
        System.arraycopy(salt, 0, payload, 0, salt.size)
        System.arraycopy(iv, 0, payload, salt.size, iv.size)
        System.arraycopy(ciphertext, 0, payload, salt.size + iv.size, ciphertext.size)
        return Base64.getEncoder().encodeToString(payload)
    }

    companion object {
        private const val PASSPHRASE = "correct horse battery staple"
        private const val SALT_LENGTH_BYTES = 16
        private const val IV_LENGTH_BYTES = 12
        private const val KEY_LENGTH_BITS = 256
        private const val LEGACY_PBKDF2_ITERATIONS = 600_000
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
