/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.ssh

import android.util.Log
import com.zeroclaw.ffi.FfiHardwareSshSigner

/**
 * Bridges the Rust ssh-agent's hardware sign requests to the Android
 * Keystore.
 *
 * Registered once at agent bring-up via `sshAgentRegisterHwSigner`. The
 * Rust agent invokes [sign] from a background thread for every
 * `SIGN_REQUEST` that targets a hardware identity; the FFI contract is
 * that an empty array signals failure, which the agent answers with an
 * agent-protocol failure so the SSH client falls through to its next
 * authentication method.
 *
 * @param store Keystore-backed key operations used to produce
 *   signatures.
 */
class HardwareSshSigner(
    private val store: HardwareSshKeyStore,
) : FfiHardwareSshSigner {
    /**
     * Signs [data] with the Keystore key under [keystoreAlias].
     *
     * Never throws across the FFI: any Keystore failure (locked device,
     * invalidated or missing key) is logged and reported as an empty
     * array.
     *
     * @param keystoreAlias Alias of the hardware key to sign with.
     * @param data Raw bytes to sign with `SHA256withECDSA`.
     * @return DER-encoded ECDSA signature, or an empty array on failure.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun sign(
        keystoreAlias: String,
        data: ByteArray,
    ): ByteArray =
        try {
            store.signSha256Ecdsa(keystoreAlias, data)
        } catch (e: Exception) {
            Log.w(TAG, "Hardware SSH sign failed for $keystoreAlias: ${e.message}")
            ByteArray(0)
        }

    /** Constants for [HardwareSshSigner]. */
    private companion object {
        private const val TAG = "HardwareSshSigner"
    }
}
