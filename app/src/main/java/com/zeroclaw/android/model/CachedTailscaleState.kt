/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

import kotlinx.serialization.Serializable

/**
 * Cached representation of a tailnet peer and its discovered services.
 *
 * Serialized to JSON and stored in [AppSettings.tailscaleCachedDiscovery].
 * These are Kotlin-side mirror types, separate from UniFFI-generated types.
 *
 * @property hostname Machine hostname (e.g., "natal-pc").
 * @property ip Tailscale IP or user-entered address.
 * @property isManual True if this peer was manually added, false if auto-discovered.
 * @property services Services found on this peer during the last scan.
 */
@Serializable
data class CachedTailscalePeer(
    val hostname: String = "",
    val ip: String,
    val isManual: Boolean,
    val services: List<CachedTailscaleService> = emptyList(),
)

/**
 * A single service found on a tailnet peer.
 *
 * @property kind Service type identifier ("ollama" or "zeroclaw").
 * @property port TCP port the service was found on.
 * @property version Version or info string, if available.
 * @property healthy Whether the service responded successfully.
 */
@Serializable
data class CachedTailscaleService(
    val kind: String,
    val port: Int,
    val version: String? = null,
    val healthy: Boolean = false,
)
