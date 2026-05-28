/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.util.Log
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.features.EmailContributor
import com.zeroclaw.android.service.features.FeatureContext
import com.zeroclaw.android.service.features.FeatureContributor
import com.zeroclaw.android.service.features.TailscaleContributor
import com.zeroclaw.android.service.features.TwitterContributor
import com.zeroclaw.android.ui.screen.setup.SetupProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for "I changed settings/channels/agents — push it
 * to the daemon."
 *
 * Centralises three previously open-coded patterns:
 *
 *  1. Building the full daemon TOML from the current repository state
 *     (was duplicated between [ZeroAIDaemonService.buildGlobalTomlConfig]
 *     and `ApiKeysViewModel.buildGlobalTomlConfig`).
 *  2. Running [FeatureContributor] contributions (Twitter, Email,
 *     Tailscale, ...) so per-feature TOML sections AND agent-awareness
 *     fragments are applied uniformly.
 *  3. Calling [SetupOrchestrator.runHotReload] when the daemon is
 *     running, falling back to [DaemonServiceBridge.markRestartRequired]
 *     when it isn't.
 *
 * Old behaviour: `ChannelsViewModel.saveChannel` only called
 * `markRestartRequired`, which is a UI banner flag — never restarted the
 * daemon. Result: bot tokens saved to Room but the Rust process never
 * picked them up, so messages to the bot silently dropped on the floor
 * until the user noticed the banner and manually tapped "Restart". That
 * gap is closed by routing every settings-mutating ViewModel through
 * [apply].
 *
 * @param app The [ZeroAIApplication] singleton providing access to all
 *   repositories the daemon TOML depends on.
 */
class DaemonReloader(
    private val app: ZeroAIApplication,
) {
    private val setupOrchestrator: SetupOrchestrator =
        SetupOrchestrator(app.daemonBridge, app.healthBridge)

    /**
     * The default set of feature contributors. Order is preserved when
     * concatenating awareness fragments; later contributors append after
     * earlier ones.
     */
    private val featureContributors: List<FeatureContributor> =
        listOf(
            TwitterContributor(),
            EmailContributor(),
            TailscaleContributor(),
        )

    /** Live progress feed for any UI that wants to surface hot-reload status. */
    val progress: StateFlow<SetupProgress> = setupOrchestrator.progress

    /** Rewinds the progress feed before a fresh apply cycle. */
    fun resetProgress() {
        setupOrchestrator.reset()
    }

    /**
     * Rebuilds the full daemon TOML from the current repository state
     * and either hot-reloads (daemon running) or marks restart-required
     * (daemon stopped).
     *
     * Returns silently — callers that need observable progress should
     * subscribe to [progress]. Failures are logged but never propagated,
     * matching the existing behaviour of `ApiKeysViewModel.triggerHotReload`
     * (the user already sees the partial result on Dashboard / Terminal).
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    suspend fun apply() {
        try {
            Ffi.clearCredentialCacheSafely()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear credential cache: ${e.message}")
        }

        val baseSettings = app.settingsRepository.settings.first()
        val effectiveSettings = resolveEffectiveDefaults(baseSettings)
        val apiKey =
            app.apiKeyRepository.getByProviderFresh(effectiveSettings.defaultProvider)
        // Mirror ZeroAIDaemonService: fall back to the standalone OAuth
        // access token when the user is signed in via Anthropic / OpenAI
        // Codex login and there's no stored API key. Without this fallback,
        // every hot-reload of an OAuth-only install ships an empty
        // `api_key` and silently breaks provider auth.
        val apiKeyValue =
            apiKey?.key?.takeIf { it.isNotBlank() }
                ?: resolveOAuthAccessToken(effectiveSettings.defaultProvider)

        val emailConfig = loadEmailConfig()
        val featureCtx =
            FeatureContext(
                settings = effectiveSettings,
                emailConfig = emailConfig,
                peerAliasLookup = { ip, port -> resolvePeerAlias(ip, port) },
            )
        for (contributor in featureContributors) {
            contributor.contribute(featureCtx)
        }

        val configToml = buildFullToml(effectiveSettings, apiKey, apiKeyValue, featureCtx)

        Log.i(
            TAG,
            "apply provider=${effectiveSettings.defaultProvider} " +
                "model=${effectiveSettings.defaultModel} " +
                "baseUrl=${apiKey?.baseUrl.orEmpty()} " +
                "apiKey=${if (apiKeyValue.isNotBlank()) "present" else "EMPTY"} " +
                "v3=${configToml.contains("[providers.models.")} " +
                "channels=${configToml.contains("[channels_config.")} " +
                "running=${app.daemonBridge.serviceState.value}",
        )

        if (app.daemonBridge.serviceState.value != ServiceState.RUNNING) {
            app.daemonBridge.markRestartRequired()
            return
        }

        val expectedChannels =
            app.channelConfigRepository.channels
                .first()
                .filter { it.isEnabled }
                .map { it.type.tomlKey }
        val validPort =
            if (baseSettings.port in VALID_PORT_RANGE) {
                baseSettings.port
            } else {
                AppSettings.DEFAULT_PORT
            }

        try {
            setupOrchestrator.runHotReload(
                context = app as Context,
                configToml = configToml,
                expectedChannels = expectedChannels,
                port = validPort.toUShort(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Hot-reload failed", e)
        }
    }

    /**
     * Falls back to the OAuth standalone access token for the given
     * provider when no stored API key is available. Mirrors the
     * `ZeroAIDaemonService.resolveOAuthAccessToken` resolver so
     * hot-reload installs that signed in via Claude Code / OpenAI Codex
     * OAuth keep working across daemon restarts. Returns an empty string
     * for providers without an OAuth path (Gemini, Ollama, etc.).
     */
    private fun resolveOAuthAccessToken(provider: String): String =
        when (provider) {
            "anthropic" ->
                runCatching {
                    com.zeroclaw.ffi
                        .getAnthropicAccessTokenStandalone(
                            dataDir = app.filesDir.absolutePath,
                        ).orEmpty()
                }.onFailure {
                    Log.w(TAG, "Anthropic OAuth resolve failed: ${it.message}")
                }.getOrDefault("")
            "openai" ->
                runCatching {
                    com.zeroclaw.ffi
                        .getOpenaiAccessTokenStandalone(
                            dataDir = app.filesDir.absolutePath,
                        ).orEmpty()
                }.onFailure {
                    Log.w(TAG, "OpenAI OAuth resolve failed: ${it.message}")
                }.getOrDefault("")
            else -> ""
        }

    private suspend fun resolveEffectiveDefaults(settings: AppSettings): AppSettings {
        val agents = app.agentRepository.agents.first()
        val authProfiles = AuthProfileStore.listStandaloneOnIo(app)
        return SlotAwareAgentConfig.resolveEffectiveDefaults(settings, agents) { agent ->
            val key = app.apiKeyRepository.getByProvider(agent.provider)
            SlotAwareAgentConfig.hasUsableProviderCredentials(
                provider = agent.provider,
                apiKey = key,
                authProfiles = authProfiles,
            )
        }
    }

    private suspend fun loadEmailConfig() =
        runCatching {
            app.emailConfigRepository.observe().first()
        }.getOrNull()

    /**
     * Lazy-initialised EncryptedSharedPreferences for tailscale peer
     * aliases. The Tink keystore init is ~10-50 ms; without lazy caching
     * a 20-peer tailnet would pay that cost N times inside a single
     * [apply] call.
     */
    private val peerAliasPrefs: android.content.SharedPreferences by lazy {
        val masterKey =
            androidx.security.crypto.MasterKey
                .Builder(app)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            app,
            "tailscale_peer_tokens",
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences
                .PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences
                .PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun resolvePeerAlias(
        ip: String,
        port: Int,
    ): String? {
        val sanitizedIp = ip.replace(Regex("[^a-fA-F0-9.:]"), "")
        return peerAliasPrefs.getString("tailscale_alias_${sanitizedIp}_$port", null)
    }

    /**
     * Composes the global config, channel sections, agent sections, and
     * feature-contributor TOML into a single document. Mirrors
     * [ZeroAIDaemonService]'s assembly order so the daemon receives an
     * identical document whether it boots fresh or via hot-reload.
     */
    private suspend fun buildFullToml(
        settings: AppSettings,
        apiKey: ApiKey?,
        apiKeyValue: String,
        featureCtx: FeatureContext,
    ): String {
        val cloudGlobalConfig =
            DaemonGlobalConfigMapper.toGlobalTomlConfig(
                context = app,
                settings = settings,
                apiKey = apiKey,
                apiKeyValue = apiKeyValue,
                hubAppContext = featureCtx.assembledAwareness(),
            )
        // Hot-reload mirrors the daemon-start substitution: when the
        // on-device-large engine is loaded, point the global config
        // at the loopback LiteRT-LM endpoint so the reload doesn't
        // re-emit cloud credentials and snap routing back to cloud.
        val globalConfig =
            if (app.onDeviceInferenceManager.isLocalActive()) {
                val loadedId = app.onDeviceInferenceManager.loadedModelId().orEmpty()
                cloudGlobalConfig.copy(
                    provider = "custom-openai",
                    model = loadedId,
                    // Match daemon-start: forward the bearer token
                    // so hot-reload keeps the loopback server's
                    // auth check satisfied. See ZeroAIDaemonService
                    // for the full rationale.
                    apiKey = app.onDeviceInferenceManager.getLocalAuthToken(),
                    baseUrl = LOCAL_LITERT_BASE_URL,
                    providerTimeoutSecs = LOCAL_LITERT_TIMEOUT_SECS,
                )
            } else {
                cloudGlobalConfig
            }
        val baseToml = ConfigTomlBuilder.build(globalConfig)
        val channelsToml =
            ConfigTomlBuilder.buildChannelsToml(
                app.channelConfigRepository.getEnabledWithSecrets(),
                app.discordGuildId(),
            )
        val agentsToml = AgentTomlAssembler.assemble(app)
        return baseToml + channelsToml + agentsToml + featureCtx.assembledToml()
    }

    /** Compile-time stub so the credential-cache clear stays optional. */
    private object Ffi {
        fun clearCredentialCacheSafely() {
            try {
                com.zeroclaw.ffi.clearCredentialCache()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "clearCredentialCache failed: ${e.message}")
            }
        }
    }

    /** Constants for [DaemonReloader]. */
    companion object {
        private const val TAG = "DaemonReloader"

        /** Mirror of [ZeroAIDaemonService] localhost base URL constant. */
        private const val LOCAL_LITERT_BASE_URL = "http://127.0.0.1:11434/v1"

        // Placeholder key removed in favour of real bearer token
        // sourced from OnDeviceInferenceManager.getLocalAuthToken().

        /**
         * Mirror of [ZeroAIDaemonService] localhost provider timeout.
         * Keep in lockstep with the daemon-start version so hot-reload
         * doesn't snap routing back to the default 120 s and surface
         * the same "operation timed out" / "Engine busy" 500 cascade.
         */
        private const val LOCAL_LITERT_TIMEOUT_SECS: Long = 600L
        private val VALID_PORT_RANGE = 1..65535
    }
}
