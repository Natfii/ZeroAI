/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.oauth.AuthProfileWriter
import com.zeroclaw.android.data.oauth.OAuthCallbackServer
import com.zeroclaw.android.data.oauth.OAuthExchangeException
import com.zeroclaw.android.data.oauth.OpenAiOAuthManager
import com.zeroclaw.android.data.oauth.PkceState
import com.zeroclaw.android.data.oauth.ProviderConnectionCoordinator
import com.zeroclaw.android.data.oauth.purgeManagedProviderState
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.data.validation.ValidationResult
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.ZeroAIDaemonService
import com.zeroclaw.android.ui.screen.onboarding.state.ProviderStepState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the OAuth side of onboarding: Anthropic paste-back sheet,
 * OpenAI Codex loopback flow, foreground-service hold to prevent
 * process freezing during Custom Tab interaction, and the disconnect /
 * cleanup paths. Extracted from `OnboardingCoordinator` so the
 * coordinator no longer carries ~285 lines of OAuth plumbing.
 *
 * @property providerState The shared provider-step state flow the
 *   handler mutates on login completion / disconnect.
 * @property apiKeyRepository Used to scrub stale API key entries after
 *   OAuth completes.
 * @property agentRepository Used to migrate `"openai"` agents to
 *   `"openai-codex"` after ChatGPT OAuth succeeds.
 * @property settingsRepository Used by [purgeManagedProviderState] when
 *   the user disconnects an OAuth provider.
 * @property scope Coroutine scope (the coordinator's `viewModelScope`).
 * @property application Application context for OAuth managers and
 *   profile writers.
 */
@Suppress("LongParameterList", "OutdatedDocumentation")
internal class OnboardingOAuthHandler(
    private val providerState: MutableStateFlow<ProviderStepState>,
    private val apiKeyRepository: ApiKeyRepository,
    private val agentRepository: AgentRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val application: ZeroAIApplication,
) {
    private val _anthropicSheetVisible = MutableStateFlow(false)

    /** Whether the Anthropic paste-back sheet is currently displayed. */
    val anthropicSheetVisible: StateFlow<Boolean> = _anthropicSheetVisible.asStateFlow()

    private val _anthropicSheetLoading = MutableStateFlow(false)

    /** Whether the Anthropic code exchange is in progress. */
    val anthropicSheetLoading: StateFlow<Boolean> = _anthropicSheetLoading.asStateFlow()

    private val _anthropicSheetError = MutableStateFlow<String?>(null)

    /** Last error from the Anthropic code exchange, or `null` if none. */
    val anthropicSheetError: StateFlow<String?> = _anthropicSheetError.asStateFlow()

    private var anthropicPkce: PkceState? = null

    /**
     * Starts the provider-appropriate OAuth flow. Anthropic uses paste-back
     * via [submitAnthropicCode]; OpenAI uses a loopback callback server.
     * Gemini is API-key-only and short-circuits with an explanatory message.
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    fun startOAuthLogin(context: Context) {
        val providerId = providerState.value.providerId
        val isGemini = providerId == GEMINI_PROVIDER
        val isAnthropic = providerId == ANTHROPIC_PROVIDER
        scope.launch {
            providerState.update { it.copy(isOAuthInProgress = true) }
            if (isGemini) {
                providerState.update {
                    it.copy(
                        isOAuthInProgress = false,
                        validationResult =
                            ValidationResult.Offline("Gemini model access now uses API keys."),
                    )
                }
                return@launch
            }
            if (isAnthropic) {
                holdForegroundForOAuth(context)
                val coordinator = ProviderConnectionCoordinator(application)
                anthropicPkce = coordinator.startAnthropicFlow(context)
                _anthropicSheetVisible.value = true
                return@launch
            }
            val pkce = OpenAiOAuthManager.generatePkceState()
            var server: OAuthCallbackServer? = null
            try {
                holdForegroundForOAuth(context)
                server = OAuthCallbackServer.startWithFallback()
                val port = server.boundPort
                val url = OpenAiOAuthManager.buildAuthorizeUrl(pkce, port)
                CustomTabsIntent
                    .Builder()
                    .build()
                    .launchUrl(context, Uri.parse(url))
                handleOAuthCallback(server, pkce, port, context)
            } catch (e: Exception) {
                providerState.update {
                    it.copy(
                        isOAuthInProgress = false,
                        validationResult =
                            ValidationResult.Offline(e.message ?: "OAuth login failed"),
                    )
                }
            } finally {
                server?.stop()
                releaseOAuthHold(context)
            }
        }
    }

    /**
     * Submits a pasted Anthropic authorization code for token exchange.
     *
     * @param code Cleaned authorization code from the paste-back sheet.
     */
    fun submitAnthropicCode(code: String) {
        val pkce = anthropicPkce ?: return
        _anthropicSheetLoading.value = true
        _anthropicSheetError.value = null
        scope.launch {
            try {
                val coordinator = ProviderConnectionCoordinator(application)
                coordinator.completeAnthropicFlow(code, pkce)
                providerState.update {
                    it.copy(
                        isOAuthInProgress = false,
                        oauthEmail = "Claude Login",
                        validationResult = ValidationResult.Success("Claude Code connected"),
                    )
                }
                dismissAnthropicSheet()
            } catch (e: OAuthExchangeException) {
                _anthropicSheetError.value =
                    "Invalid or expired code — please try again (HTTP ${e.httpStatusCode})"
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                _anthropicSheetError.value =
                    "Connection failed — ${e.message ?: "unknown error"}"
            } finally {
                _anthropicSheetLoading.value = false
            }
        }
    }

    /** Dismisses the Anthropic paste-back sheet and clears related state. */
    fun dismissAnthropicSheet() {
        _anthropicSheetVisible.value = false
        _anthropicSheetLoading.value = false
        _anthropicSheetError.value = null
        anthropicPkce = null
        providerState.update { it.copy(isOAuthInProgress = false) }
        releaseOAuthHold(application)
    }

    /**
     * Clears all OAuth-related fields from the provider state and removes
     * the appropriate auth profile.
     *
     * Gemini is included here even though it has no OAuth path — the
     * onboarding UI exposes a single Disconnect action and this is the
     * entry point. For Gemini the call is a no-op for profile writers
     * and just clears the provider-state OAuth fields back to defaults.
     */
    fun disconnectOAuth() {
        val providerId = providerState.value.providerId
        val isAnthropic = providerId == ANTHROPIC_PROVIDER
        val isGemini = providerId == GEMINI_PROVIDER
        when {
            isAnthropic -> AuthProfileWriter.removeAnthropicProfile(application)
            isGemini -> Unit
            else -> AuthProfileWriter.removeCodexProfile(application)
        }
        scope.launch {
            purgeManagedProviderState(
                provider = providerId,
                keyRepository = apiKeyRepository,
                settingsRepository = settingsRepository,
                agentRepository = agentRepository,
            )
        }
        val resetProviderId =
            when {
                isAnthropic -> ANTHROPIC_PROVIDER
                isGemini -> GEMINI_PROVIDER
                else -> OPENAI_PROVIDER
            }
        providerState.update {
            it.copy(
                providerId = resetProviderId,
                apiKey = "",
                oauthExpiresAt = 0L,
                oauthEmail = "",
                validationResult = ValidationResult.Idle,
            )
        }
    }

    @Suppress("LongMethod")
    private suspend fun handleOAuthCallback(
        server: OAuthCallbackServer,
        pkce: PkceState,
        port: Int,
        context: Context,
    ) {
        val callbackResult = server.awaitCallback()
        bringAppToForeground(context)
        if (callbackResult == null) {
            providerState.update {
                it.copy(
                    isOAuthInProgress = false,
                    validationResult = ValidationResult.Failure("Login timed out"),
                )
            }
            return
        }

        if (callbackResult.state != pkce.state) {
            providerState.update {
                it.copy(
                    isOAuthInProgress = false,
                    validationResult = ValidationResult.Failure("Security validation failed"),
                )
            }
            return
        }

        val tokens =
            OpenAiOAuthManager.exchangeCodeForTokens(
                code = callbackResult.code,
                codeVerifier = pkce.codeVerifier,
                port = port,
            )
        AuthProfileWriter.writeCodexProfile(
            context = application,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAtMs = tokens.expiresAt.takeIf { it > 0L },
        )
        cleanupStaleOpenAiEntries()
        migrateAgentsToCodex()
        providerState.update {
            it.copy(
                providerId = CODEX_PROVIDER,
                oauthExpiresAt = tokens.expiresAt,
                oauthEmail = "ChatGPT Login",
                validationResult = ValidationResult.Success("OAuth login successful"),
                isOAuthInProgress = false,
            )
        }
    }

    /**
     * Starts the daemon service in OAuth-hold mode to prevent process
     * freezing while the user authenticates in a Custom Tab or 2FA app.
     */
    private fun holdForegroundForOAuth(context: Context) {
        val intent =
            Intent(context, ZeroAIDaemonService::class.java).apply {
                action = ZeroAIDaemonService.ACTION_OAUTH_HOLD
            }
        context.startForegroundService(intent)
    }

    /** Stops the OAuth-hold foreground service if the daemon is not running. */
    private fun releaseOAuthHold(context: Context) {
        if (application.daemonBridge.serviceState.value != ServiceState.RUNNING) {
            val intent =
                Intent(context, ZeroAIDaemonService::class.java).apply {
                    action = ZeroAIDaemonService.ACTION_STOP
                }
            context.startService(intent)
        }
    }

    /**
     * Brings the app to the foreground to dismiss the Custom Tab overlay.
     * Uses `CLEAR_TOP | SINGLE_TOP` so the existing MainActivity receives
     * `onNewIntent` instead of being recreated (which would reset onboarding).
     */
    private fun bringAppToForeground(context: Context) {
        val intent =
            context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ?: return
        context.startActivity(intent)
    }

    private suspend fun cleanupStaleOpenAiEntries() {
        val allKeys = apiKeyRepository.keys.first()
        allKeys
            .filter { it.provider == OPENAI_PROVIDER && it.key.isBlank() }
            .forEach { apiKeyRepository.delete(it.id) }
    }

    private suspend fun migrateAgentsToCodex() {
        val agents = agentRepository.agents.first()
        agents
            .filter { it.provider == OPENAI_PROVIDER }
            .forEach { agent ->
                agentRepository.save(agent.copy(provider = CODEX_PROVIDER))
            }
    }

    /** Constants shared with [OnboardingCoordinator] for provider IDs. */
    companion object {
        /** OpenAI provider id (direct API key). */
        const val OPENAI_PROVIDER: String = "openai"

        /** OpenAI Codex provider id (OAuth-backed). */
        const val CODEX_PROVIDER: String = "openai-codex"

        /** Anthropic provider id. */
        const val ANTHROPIC_PROVIDER: String = "anthropic"

        /** Gemini provider id. */
        const val GEMINI_PROVIDER: String = "google-gemini"
    }
}
