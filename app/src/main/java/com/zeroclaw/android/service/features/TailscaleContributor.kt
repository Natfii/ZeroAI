/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.features

import com.zeroclaw.android.model.CachedTailscalePeer
import com.zeroclaw.android.service.ConfigTomlBuilder
import com.zeroclaw.android.service.PeerTomlEntry
import com.zeroclaw.android.tailscale.PeerMessageRouter
import com.zeroclaw.android.tailscale.isAgentKind
import com.zeroclaw.android.tailscale.normalizeKind
import kotlinx.serialization.json.Json

/**
 * Tailscale tailnet integration.
 *
 * Two responsibilities collapsed into one contributor:
 *
 *  1. Emits `[[tailscale_peers.entries]]` TOML blocks for every cached
 *     agent peer (zeroclaw / openclaw kinds) so the daemon's peer
 *     message router can address them.
 *  2. Tells the agent — via the awareness fragment — which peers are
 *     reachable and what services (Ollama, LM Studio, vLLM, LocalAI,
 *     zeroclaw daemon) each one exposes.
 *
 * Both streams read from the same `tailscaleCachedDiscovery` JSON blob
 * the discovery sweep writes back into settings, so adding a peer
 * shows up in both the routing surface AND the agent's mental model
 * within a single daemon restart.
 *
 * Per-peer user-customised aliases are resolved through
 * [FeatureContext.peerAliasLookup] — the daemon owns the encrypted
 * store; the contributor stays Android-`Context`-free.
 */
class TailscaleContributor : FeatureContributor {
    override val key: String = "tailscale"

    override suspend fun contribute(ctx: FeatureContext) {
        val s = ctx.settings
        if (s.tailscaleCachedDiscovery.isBlank()) return

        val peers =
            try {
                Json.decodeFromString<List<CachedTailscalePeer>>(s.tailscaleCachedDiscovery)
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                return
            }

        emitPeerEntries(ctx, peers)
        if (s.tailscaleAwarenessEnabled) {
            appendAwareness(ctx, peers)
        }
    }

    /**
     * Walks the cached peers, narrows to agent-kind services, resolves
     * per-peer aliases (saved override > router-defaulted alias), and
     * emits one `[[tailscale_peers.entries]]` block per surviving
     * service.
     */
    private fun emitPeerEntries(
        ctx: FeatureContext,
        peers: List<CachedTailscalePeer>,
    ) {
        val rawEntries =
            peers.flatMap { peer ->
                peer.services
                    .filter { svc -> isAgentKind(svc.kind) }
                    .map { svc -> Triple(peer, svc, normalizeKind(svc.kind)) }
            }
        if (rawEntries.isEmpty()) return

        val defaults = PeerMessageRouter.resolveAliasConflicts(rawEntries.map { it.third })

        val entries =
            rawEntries
                .mapIndexed { i, (peer, svc, _) ->
                    val savedAlias = ctx.peerAliasLookup(peer.ip, svc.port)
                    PeerTomlEntry(
                        ip = peer.ip,
                        hostname = peer.hostname,
                        kind = svc.kind,
                        port = svc.port,
                        alias = savedAlias ?: defaults[i],
                        authRequired = svc.authRequired,
                        enabled = true,
                    )
                }.filter { it.isValid() }
        if (entries.isEmpty()) return

        val toml = ConfigTomlBuilder.buildTailscalePeersToml(entries)
        if (toml.isBlank()) return
        ctx.emitToml { append(toml) }
    }

    /**
     * Renders the natural-language fragment that ends up in
     * `[system_prompt] hub_app_context`. Skipped when no peer has a
     * healthy service — the agent shouldn't be told about a dead tailnet.
     */
    private fun appendAwareness(
        ctx: FeatureContext,
        peers: List<CachedTailscalePeer>,
    ) {
        val peersWithServices = peers.filter { it.services.any { svc -> svc.healthy } }
        if (peersWithServices.isEmpty()) return

        val fragment =
            buildString {
                append("- Tailscale: Connected to tailnet.")
                peersWithServices.forEach { peer ->
                    val name = peer.hostname.ifEmpty { peer.ip }
                    append(" Peer \"$name\" (${peer.ip}) has:")
                    peer.services.filter { it.healthy }.forEach { svc ->
                        val label =
                            when (svc.kind) {
                                "ollama" -> "Ollama"
                                "lm_studio" -> "LM Studio"
                                "vllm" -> "vLLM"
                                "local_ai" -> "LocalAI"
                                "zeroclaw" -> "zeroclaw daemon"
                                // OpenClaw's `/v1/chat/completions` is
                                // disabled by default on the peer side;
                                // flag the caveat so the agent doesn't
                                // assume it can route messages there
                                // without operator config.
                                "open_claw", "openclaw" ->
                                    "OpenClaw daemon (chat API may be disabled)"
                                "hermes" -> "Hermes Agent (Nous Research)"
                                else -> svc.kind
                            }
                        val ver = if (svc.version != null) " (${svc.version})" else ""
                        append(" $label$ver on port ${svc.port};")
                    }
                }
            }.trimEnd(';').plus(".")
        ctx.appendAwareness(fragment)
    }
}
