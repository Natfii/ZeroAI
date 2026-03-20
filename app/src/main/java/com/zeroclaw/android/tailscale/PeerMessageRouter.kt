/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.tailscale

/**
 * Lightweight peer route entry used for alias matching.
 *
 * @property alias The @mention prefix (lowercased).
 * @property ip Tailscale IP address.
 * @property port Agent gateway port.
 * @property kind Agent type: `"zeroclaw"` or `"openclaw"`.
 */
data class PeerRouteEntry(
    val alias: String,
    val ip: String,
    val port: Int,
    val kind: String,
)

/**
 * Result of a successful alias match.
 *
 * @property alias The matched alias (lowercased).
 * @property strippedMessage The message with the `@alias` prefix removed.
 * @property peer The matched peer route entry.
 */
data class PeerMatchResult(
    val alias: String,
    val strippedMessage: String,
    val peer: PeerRouteEntry,
)

/**
 * Returns `true` if a cached service kind string represents an agent.
 *
 * Handles both UniFFI-generated casing (`open_claw`) and canonical
 * lowercase (`openclaw`, `zeroclaw`).
 *
 * @param kind Service kind string from [CachedTailscaleService.kind].
 * @return `true` for zeroclaw and openclaw variants.
 */
fun isAgentKind(kind: String): Boolean = kind == "zeroclaw" || kind == "openclaw" || kind == "open_claw"

/**
 * Normalizes a cached service kind to its canonical alias form.
 *
 * Maps `"open_claw"` to `"openclaw"`, passes others through unchanged.
 *
 * @param kind Service kind string.
 * @return Normalized kind suitable for use as an alias.
 */
fun normalizeKind(kind: String): String = if (kind == "open_claw") "openclaw" else kind

/**
 * Routes messages to peer agents based on `@alias` prefix matching.
 *
 * Matching rules:
 * - Only prefix-position `@alias` triggers (followed by space or end-of-string)
 * - Case-insensitive
 * - Mid-message mentions are ignored
 */
object PeerMessageRouter {
    /** Maximum alias length in characters. */
    private const val MAX_ALIAS_LENGTH = 32

    private val ALIAS_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$")

    /**
     * Checks if a message starts with `@alias` for any enabled peer.
     *
     * @param message The raw input message.
     * @param peers List of enabled peer route entries.
     * @return A [PeerMatchResult] if matched, `null` otherwise.
     */
    fun matchAlias(
        message: String,
        peers: List<PeerRouteEntry>,
    ): PeerMatchResult? {
        if (!message.startsWith("@")) return null

        for (peer in peers) {
            val prefix = "@${peer.alias}"
            if (message.equals(prefix, ignoreCase = true)) {
                return PeerMatchResult(
                    alias = peer.alias,
                    strippedMessage = "",
                    peer = peer,
                )
            }
            if (message.startsWith("$prefix ", ignoreCase = true)) {
                return PeerMatchResult(
                    alias = peer.alias,
                    strippedMessage = message.substring(prefix.length + 1),
                    peer = peer,
                )
            }
        }
        return null
    }

    /**
     * Validates an alias string.
     *
     * Aliases must be ASCII alphanumeric plus `-` and `_`, start with
     * a letter or digit, and be at most 32 characters.
     *
     * @param alias The alias to validate.
     * @return `true` if valid, `false` otherwise.
     */
    fun isValidAlias(alias: String): Boolean {
        if (alias.isEmpty() || alias.length > MAX_ALIAS_LENGTH) return false
        return alias.matches(ALIAS_PATTERN)
    }

    /**
     * Resolves alias conflicts by appending `_2`, `_3`, etc. to duplicates.
     *
     * First instance keeps the base name.
     *
     * @param aliases List of aliases that may contain duplicates.
     * @return List with conflicts resolved via numeric suffixes.
     */
    fun resolveAliasConflicts(aliases: List<String>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return aliases.map { alias ->
            val normalized = alias.lowercase()
            val count = (seen[normalized] ?: 0) + 1
            seen[normalized] = count
            if (count > 1) "${alias}_$count" else alias
        }
    }
}
