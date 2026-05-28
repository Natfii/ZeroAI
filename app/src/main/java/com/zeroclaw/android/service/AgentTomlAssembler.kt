/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.model.Agent
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for `[agents.<name>]` TOML emission.
 *
 * Both daemon cold-start ([ZeroAIDaemonService]) and hot-reload
 * ([DaemonReloader]) need identical agent sections. Before this object
 * existed, each call site hand-rolled its own loop and silently
 * disagreed on which credential predicate to use — cold-start used the
 * stricter [SlotAwareAgentConfig.hasUsableDaemonProviderCredentials],
 * hot-reload used the looser [SlotAwareAgentConfig.hasUsableProviderCredentials].
 * That meant an agent gated only by a ChatGPT managed-login (which the
 * daemon cannot yet route) could appear in the hot-reloaded TOML and
 * vanish at the next cold-start. Centralising on the strict daemon
 * predicate eliminates that drift.
 *
 * **Routing assumption**: the primary agent — the first entry returned
 * by [SlotAwareAgentConfig.orderedConfiguredAgents] with usable daemon
 * credentials — claims every enabled channel via its `channels` field.
 * Secondary agents emit `channels = []`. Until the Agent edit screen
 * exposes per-agent channel selection, this is the only way inbound
 * Telegram/Discord traffic gets routed to a concrete agent. Add a real
 * selector before shipping multi-agent setups, otherwise second-and-later
 * agents will never receive inbound messages.
 */
object AgentTomlAssembler {
    /**
     * Assemble the `[agents.<name>]` TOML sections for the current
     * configured agents.
     *
     * @return TOML string with one `[agents.<name>]` section per agent
     *   whose provider has usable daemon credentials, or empty string
     *   if no agent qualifies.
     */
    suspend fun assemble(app: ZeroAIApplication): String {
        val authProfiles = AuthProfileStore.listStandaloneOnIo(app)
        val allAgents = app.agentRepository.agents.first()
        val channelRefs =
            app.channelConfigRepository.channels
                .first()
                .filter { it.isEnabled }
                .map { "${it.type.tomlKey}.default" }

        // When the on-device-large agent is the active route AND the
        // engine is loaded, emit a single agent block claiming all
        // channels. The global config (built by ZeroAIDaemonService)
        // already points at http://127.0.0.1:11434/v1 via the
        // custom-openai provider, so the daemon's agent loop hits
        // the local LiteRT-LM server. Tool calls still flow through
        // the daemon's existing Tool trait — they just won't fire
        // from the local model until LiteRT-LM exposes tool support.
        if (app.onDeviceInferenceManager.isLocalActive()) {
            val entry =
                AgentTomlEntry(
                    name = ON_DEVICE_AGENT_NAME,
                    modelProviderRef = "custom.default",
                    maxDepth = Agent.DEFAULT_MAX_DEPTH,
                    channels = channelRefs,
                )
            return ConfigTomlBuilder.buildAgentsToml(listOf(entry))
        }
        val entries =
            SlotAwareAgentConfig
                .orderedConfiguredAgents(allAgents)
                .mapIndexedNotNull { index, agent ->
                    val agentKey = app.apiKeyRepository.getByProviderFresh(agent.provider)
                    if (
                        !SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
                            provider = agent.provider,
                            apiKey = agentKey,
                            authProfiles = authProfiles,
                        )
                    ) {
                        return@mapIndexedNotNull null
                    }
                    val resolvedType =
                        ConfigTomlBuilder.stripColonUrl(
                            ConfigTomlBuilder.resolveProvider(
                                SlotAwareAgentConfig.configProvider(agent),
                                agentKey?.baseUrl.orEmpty(),
                            ),
                        )
                    AgentTomlEntry(
                        name = SlotAwareAgentConfig.configName(agent),
                        modelProviderRef =
                            if (resolvedType.isBlank()) "" else "$resolvedType.default",
                        maxDepth = agent.maxDepth,
                        channels = primaryAgentChannels(index, channelRefs),
                    )
                }
        return ConfigTomlBuilder.buildAgentsToml(entries)
    }

    /**
     * Named replacement for the previous `if (index == 0) channelRefs else emptyList()`
     * line so the "primary agent owns all channels" rule is explicit at
     * the call site rather than buried in a positional condition.
     */
    private fun primaryAgentChannels(
        index: Int,
        channelRefs: List<String>,
    ): List<String> = if (index == 0) channelRefs else emptyList()

    /**
     * Agent name used for the single block emitted when the on-device
     * large model is the active route. Picked to be human-readable in
     * logs and the daemon's `[agents.<name>]` section.
     */
    private const val ON_DEVICE_AGENT_NAME = "on-device"
}
