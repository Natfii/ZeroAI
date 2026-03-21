// Copyright 2026 @Natfii, MIT License

package com.zeroclaw.android.service

import com.zeroclaw.ffi.CapabilityGrantInfo
import com.zeroclaw.ffi.FfiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge between the Android UI layer and the Rust capability grants FFI.
 *
 * Wraps the two capability-grant UniFFI-generated functions in coroutine-safe
 * suspend functions dispatched to [Dispatchers.IO].
 *
 * @param dataDir Absolute path to the app's files directory (from `Context.filesDir`).
 *   Passed to the native layer as the workspace root where `capability_grants.json` lives.
 * @param ioDispatcher Dispatcher for blocking FFI calls. Defaults to [Dispatchers.IO].
 */
class CapabilityGrantsBridge(
    private val dataDir: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Lists all persisted capability grants from the workspace grants file.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @return List of all [CapabilityGrantInfo] records, possibly empty.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun listGrants(): List<CapabilityGrantInfo> =
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.listCapabilityGrants(dataDir)
        }

    /**
     * Revokes a single persisted capability grant.
     *
     * If the grant does not exist, this is a no-op. Safe to call from the main
     * thread; the underlying blocking FFI call is dispatched to [ioDispatcher].
     *
     * @param skillName Name of the skill whose grant is being revoked.
     * @param capability The capability string to revoke (e.g. `"tools.call"`).
     * @throws FfiException if the grants file cannot be written.
     */
    @Throws(FfiException::class)
    suspend fun revokeGrant(
        skillName: String,
        capability: String,
    ) {
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.revokeCapabilityGrant(dataDir, skillName, capability)
        }
    }
}
