/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.oauth

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.browser.customtabs.CustomTabsIntent
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.ffi.FfiAuthProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Snapshot of a provider connection used by provider-connection surfaces.
 *
 * @property providerId Canonical [ProviderRegistry] ID.
 * @property displayName Human-readable provider name.
 * @property authProfileProvider Canonical auth-profile provider key.
 * @property profile Connected auth profile when present.
 * @property oauthInProgress Whether an OAuth flow is currently active.
 */
data class ProviderConnectionSnapshot(
    val providerId: String,
    val displayName: String,
    val authProfileProvider: String,
    val profile: FfiAuthProfile?,
    val oauthInProgress: Boolean,
)

/**
 * Shared coordinator for OAuth-backed provider connections.
 *
 * This extracts connection logic from screen-specific ViewModels so the merged Agents detail flow
 * can reuse the same implementation later.
 *
 * @param app Application singleton exposing repositories and bridge dependencies.
 */
class ProviderConnectionCoordinator(
    private val app: ZeroAIApplication,
) {
    /**
     * Loads provider connection snapshots from the standalone auth-profile store.
     *
     * @param oauthInProgressIds Provider IDs currently running an OAuth flow.
     * @return Current provider connection snapshots.
     */
    suspend fun loadSnapshots(oauthInProgressIds: Set<String>): List<ProviderConnectionSnapshot> {
        val profiles =
            withContext(Dispatchers.IO) {
                AuthProfileStore.listStandalone(app)
            }
        return OAUTH_PROVIDER_IDS.mapNotNull { providerId ->
            val info = ProviderRegistry.findById(providerId) ?: return@mapNotNull null
            val authProvider = AuthProfileStore.authProfileProviderFor(providerId).orEmpty()
            val profile = profiles.firstOrNull { it.provider == authProvider }
            ProviderConnectionSnapshot(
                providerId = providerId,
                displayName =
                    when (providerId) {
                        OPENAI_ID -> CHATGPT_DISPLAY_NAME
                        ANTHROPIC_ID -> CLAUDE_CODE_DISPLAY_NAME
                        else -> info.displayName
                    },
                authProfileProvider = authProvider,
                profile = profile,
                oauthInProgress = providerId in oauthInProgressIds,
            )
        }
    }

    /**
     * Starts the provider connection flow for [providerId].
     *
     * For Anthropic, use [startAnthropicFlow] and [completeAnthropicFlow]
     * instead — the paste-back flow requires two phases.
     *
     * @param context Activity context used to launch browser or OAuth UI.
     * @param providerId Canonical provider ID to connect.
     */
    suspend fun connectProvider(
        context: Context,
        providerId: String,
    ) {
        when (providerId) {
            ANTHROPIC_ID -> error("Use startAnthropicFlow/completeAnthropicFlow for Anthropic")
            else -> connectBrowserPkce(context, providerId, OpenAiOAuthManager.generatePkceState())
        }
    }

    /**
     * Phase 1: Generates PKCE state and opens the Anthropic authorize page.
     *
     * The caller stores the returned [PkceState] and shows a paste-back UI.
     * After the user copies the code from the browser and pastes it, call
     * [completeAnthropicFlow] with the code and the stored PKCE state.
     *
     * @param context Activity context for launching the Custom Tab.
     * @return PKCE state to store until the user submits the code.
     */
    fun startAnthropicFlow(context: Context): PkceState {
        val pkce = AnthropicOAuthManager.generatePkceState()
        val url = AnthropicOAuthManager.buildAuthorizeUrl(pkce)
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        return pkce
    }

    /**
     * Phase 2: Exchanges the pasted authorization code for tokens.
     *
     * Calls the Anthropic token endpoint, writes the auth profile on
     * success, and saves managed provider metadata.
     *
     * @param code Authorization code pasted by the user.
     * @param pkce PKCE state from [startAnthropicFlow].
     * @return The [OAuthTokenResult] on success.
     * @throws OAuthExchangeException if the token exchange fails.
     */
    suspend fun completeAnthropicFlow(
        code: String,
        pkce: PkceState,
    ): OAuthTokenResult {
        val tokens =
            withContext(Dispatchers.IO) {
                AnthropicOAuthManager.exchangeCodeForTokens(
                    code = code,
                    codeVerifier = pkce.codeVerifier,
                    state = pkce.state,
                )
            }
        withContext(Dispatchers.IO) {
            AuthProfileWriter.writeAnthropicProfile(
                context = app,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtMs = tokens.expiresAt.takeIf { it > 0L },
            )
            saveManagedProviderMetadata(
                repository = app.apiKeyRepository,
                provider = ANTHROPIC_ID,
                expiresAt = tokens.expiresAt,
            )
        }
        return tokens
    }

    /**
     * Disconnects the stored auth profile for [providerId].
     *
     * @param providerId Canonical provider ID to disconnect.
     */
    suspend fun disconnectProvider(providerId: String) {
        withContext(Dispatchers.IO) {
            when (providerId) {
                OPENAI_ID -> {
                    AuthProfileWriter.removeCodexProfile(app)
                    clearOAuthCookies(OPENAI_COOKIE_DOMAINS)
                }
                ANTHROPIC_ID -> {
                    AuthProfileWriter.removeAnthropicProfile(app)
                    clearOAuthCookies(ANTHROPIC_COOKIE_DOMAINS)
                }
            }
            purgeManagedProviderState(
                provider = providerId,
                keyRepository = app.apiKeyRepository,
                settingsRepository = app.settingsRepository,
                agentRepository = app.agentRepository,
            )
        }
    }

    /**
     * OpenAI OAuth flow using localhost callback.
     *
     * OpenAI allows localhost redirect URIs, so we start a local server
     * and wait for the browser redirect after authorization.
     */
    @Suppress("UnusedParameter")
    private suspend fun connectBrowserPkce(
        context: Context,
        providerId: String,
        pkce: PkceState,
    ) {
        var server: OAuthCallbackServer? = null
        try {
            server = OAuthCallbackServer.startWithFallback()
            val port = server.boundPort
            val url = OpenAiOAuthManager.buildAuthorizeUrl(pkce, port)
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
            val callbackResult =
                server.awaitCallback()
                    ?: error("Login timed out")
            check(callbackResult.state == pkce.state) { "Security validation failed" }
            val tokens =
                withContext(Dispatchers.IO) {
                    OpenAiOAuthManager.exchangeCodeForTokens(
                        code = callbackResult.code,
                        codeVerifier = pkce.codeVerifier,
                        port = port,
                    )
                }
            withContext(Dispatchers.IO) {
                AuthProfileWriter.writeCodexProfile(
                    context = app,
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresAtMs = tokens.expiresAt.takeIf { it > 0L },
                )
                saveManagedProviderMetadata(
                    repository = app.apiKeyRepository,
                    provider = CODEX_PROVIDER_ID,
                    expiresAt = tokens.expiresAt,
                )
            }
        } finally {
            server?.stop()
        }
    }

    private companion object {
        private const val OPENAI_ID = "openai"
        private const val ANTHROPIC_ID = "anthropic"
        private const val CODEX_PROVIDER_ID = "openai-codex"
        private const val CHATGPT_DISPLAY_NAME = "ChatGPT"
        private const val CLAUDE_CODE_DISPLAY_NAME = "Claude Code"

        /** Anthropic OAuth blocked server-side (2026-01); hidden until re-enabled. */
        private val OAUTH_PROVIDER_IDS: List<String> = listOf(OPENAI_ID)

        private val OPENAI_COOKIE_DOMAINS =
            listOf(
                "https://auth.openai.com",
                "https://chatgpt.com",
            )

        private val ANTHROPIC_COOKIE_DOMAINS =
            listOf(
                "https://claude.ai",
                "https://console.anthropic.com",
            )

        /**
         * Clears cookies for the given domains so the next OAuth
         * Custom Tab starts with a fresh session.
         */
        private fun clearOAuthCookies(domains: List<String>) {
            val cookieManager = CookieManager.getInstance() ?: return
            for (domain in domains) {
                val cookies = cookieManager.getCookie(domain) ?: continue
                for (cookie in cookies.split(";")) {
                    val name = cookie.split("=", limit = 2).firstOrNull()?.trim() ?: continue
                    cookieManager.setCookie(domain, "$name=; Max-Age=0")
                }
            }
            cookieManager.flush()
        }
    }
}
