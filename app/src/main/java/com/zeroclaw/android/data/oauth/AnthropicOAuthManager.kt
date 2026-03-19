/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.oauth

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Orchestrates the Anthropic OAuth 2.0 authorization code flow with PKCE.
 *
 * This object implements the same PKCE-based OAuth flow used by Claude Code
 * for first-party authentication against Anthropic's OAuth endpoints on
 * `claude.ai`. Unlike OpenAI (which redirects to a localhost callback),
 * Anthropic redirects to its own console page which displays the code.
 *
 * The flow differs from [OpenAiOAuthManager] in several ways:
 * - **Redirect URI**: fixed `https://console.anthropic.com/oauth/code/callback`
 *   (registered with the client ID; localhost is not allowed).
 * - **Token endpoint**: `https://console.anthropic.com/v1/oauth/token`.
 * - **Extra param**: `code=true` in the authorize URL.
 * - **Scopes**: Anthropic's own vocabulary, not OpenID Connect.
 *
 * **Risk**: Anthropic may restrict OAuth tokens obtained outside Claude Code.
 * Sessions may be revoked without notice. Callers should handle token
 * rejection gracefully and prompt the user to re-authenticate when a
 * previously valid token starts returning 401/403.
 *
 * Typical usage:
 * 1. Call [generatePkceState] to create a fresh PKCE state.
 * 2. Call [buildAuthorizeUrl] to get the browser URL.
 * 3. Launch the URL in a Custom Tab or WebView.
 * 4. Intercept the redirect to [REDIRECT_URI] and extract the `code`
 *    query parameter, or present a paste-back field for the user.
 * 5. Call [exchangeCodeForTokens] to trade the code for tokens.
 */
object AnthropicOAuthManager {
    /**
     * Claude Code OAuth application client identifier.
     *
     * This is the official Claude Code CLI client ID registered with
     * Anthropic's OAuth server on `claude.ai`.
     */
    internal const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

    /** Anthropic OAuth authorization endpoint (claude.ai for Max/Pro). */
    private const val AUTHORIZE_URL = "https://claude.ai/oauth/authorize"

    /** Anthropic OAuth token exchange endpoint. */
    private const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"

    /**
     * Registered redirect URI for the Claude Code client ID.
     *
     * Anthropic does NOT allow arbitrary localhost redirect URIs for this
     * client. The console callback page displays the authorization code
     * which can be intercepted by a Custom Tab navigation listener or
     * pasted by the user.
     */
    private const val REDIRECT_URI =
        "https://console.anthropic.com/oauth/code/callback"

    /**
     * OAuth scopes requested during authorization.
     *
     * Anthropic uses its own scope vocabulary, not OpenID Connect.
     * - `user:profile`              -- usage data, plan info
     * - `user:inference`            -- send messages to models
     * - `user:sessions:claude_code` -- Claude Code session access
     * - `user:mcp_servers`          -- MCP server connections
     *
     * See [anthropics/claude-code#20325](https://github.com/anthropics/claude-code/issues/20325).
     */
    private const val SCOPES =
        "user:profile user:inference user:sessions:claude_code user:mcp_servers"

    /** Number of random bytes for the PKCE code verifier. */
    private const val CODE_VERIFIER_BYTE_LENGTH = 64

    /** Number of random bytes for the CSRF state nonce. */
    private const val STATE_NONCE_BYTE_LENGTH = 24

    /** HTTP connection timeout in milliseconds. */
    private const val CONNECT_TIMEOUT_MS = 10_000

    /** HTTP read timeout in milliseconds. */
    private const val READ_TIMEOUT_MS = 15_000

    /** Conversion factor from seconds to milliseconds. */
    private const val MILLIS_PER_SECOND = 1000L

    /** Lower bound of successful HTTP status codes (inclusive). */
    private const val HTTP_OK_START = 200

    /** Upper bound of successful HTTP status codes (inclusive). */
    private const val HTTP_OK_END = 299

    /**
     * The redirect URI prefix used to detect the OAuth callback.
     *
     * Callers should watch for navigation to URLs starting with this
     * prefix and extract the `code` query parameter when detected.
     */
    val redirectUriPrefix: String get() = REDIRECT_URI

    /**
     * Generates a fresh PKCE state with cryptographically random values.
     *
     * The code verifier is [CODE_VERIFIER_BYTE_LENGTH] random bytes
     * encoded as base64url without padding. The code challenge is the
     * SHA-256 digest of the verifier, also base64url-encoded without
     * padding. The state nonce is [STATE_NONCE_BYTE_LENGTH] random bytes
     * encoded as base64url.
     *
     * @return A new [PkceState] ready for use in [buildAuthorizeUrl].
     */
    fun generatePkceState(): PkceState {
        val verifierBytes = ByteArray(CODE_VERIFIER_BYTE_LENGTH)
        SecureRandom().nextBytes(verifierBytes)
        val codeVerifier = base64UrlEncode(verifierBytes)

        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(codeVerifier.toByteArray())
        val codeChallenge = base64UrlEncode(digest)

        val stateBytes = ByteArray(STATE_NONCE_BYTE_LENGTH)
        SecureRandom().nextBytes(stateBytes)
        val state = base64UrlEncode(stateBytes)

        return PkceState(
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
            state = state,
        )
    }

    /**
     * Builds the full Anthropic authorization URL with all required
     * parameters.
     *
     * The returned URL includes `code=true` (Anthropic-specific), the
     * PKCE code challenge, client ID, the console redirect URI, scopes,
     * and the state nonce for CSRF protection.
     *
     * @param pkce PKCE state from [generatePkceState].
     * @return Fully-formed authorization URL to open in a browser or
     *   Custom Tab.
     */
    fun buildAuthorizeUrl(pkce: PkceState): String {
        val params =
            linkedMapOf(
                "code" to "true",
                "client_id" to CLIENT_ID,
                "response_type" to "code",
                "redirect_uri" to REDIRECT_URI,
                "scope" to SCOPES,
                "code_challenge" to pkce.codeChallenge,
                "code_challenge_method" to "S256",
                "state" to pkce.state,
            )

        val query =
            params.entries.joinToString("&") { (key, value) ->
                "${urlEncode(key)}=${urlEncode(value)}"
            }

        return "$AUTHORIZE_URL?$query"
    }

    /**
     * Exchanges an authorization code for access and refresh tokens.
     *
     * Performs an HTTP POST to the Anthropic token endpoint with a JSON
     * request body containing the authorization code and PKCE code
     * verifier. Unlike OpenAI's form-encoded exchange, Anthropic's
     * token endpoint requires `Content-Type: application/json`.
     *
     * Safe to call from the main thread; switches to the provided IO
     * dispatcher internally.
     *
     * @param code Authorization code received from the callback.
     * @param codeVerifier The [PkceState.codeVerifier] used when
     *   building the authorization URL.
     * @param state The state nonce from the callback, for logging.
     * @param ioDispatcher Coroutine dispatcher for the blocking HTTP
     *   call. Defaults to [Dispatchers.IO].
     * @return An [OAuthTokenResult] containing the access token,
     *   refresh token, and expiry timestamp.
     * @throws OAuthExchangeException if the token exchange fails for
     *   any reason (network, HTTP error, malformed response).
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
        state: String = "",
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): OAuthTokenResult =
        withContext(ioDispatcher) {
            // Anthropic may return "code#state" as a combined string.
            // Split on '#' if present and use the embedded state.
            val actualCode: String
            val actualState: String
            if (code.contains("#")) {
                val parts = code.split("#", limit = 2)
                actualCode = parts[0]
                actualState = parts[1]
            } else {
                actualCode = code
                actualState = state
            }

            val jsonBody =
                JSONObject().apply {
                    put("grant_type", "authorization_code")
                    put("code", actualCode)
                    put("state", actualState)
                    put("client_id", CLIENT_ID)
                    put("redirect_uri", REDIRECT_URI)
                    put("code_verifier", codeVerifier)
                }

            val url = URL(TOKEN_URL)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty(
                    "Content-Type",
                    "application/json",
                )
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true

                conn.outputStream.use {
                    it.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                val statusCode = conn.responseCode
                if (statusCode !in HTTP_OK_START..HTTP_OK_END) {
                    val errorBody =
                        try {
                            conn.errorStream?.bufferedReader()?.readText() ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                    throw OAuthExchangeException(
                        "Token exchange failed (HTTP $statusCode): $errorBody",
                        httpStatusCode = statusCode,
                    )
                }

                val responseBody =
                    conn.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)

                val expiresInSeconds = json.optLong("expires_in", 0L)
                OAuthTokenResult(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.getString("refresh_token"),
                    expiresAt =
                        System.currentTimeMillis() +
                            expiresInSeconds * MILLIS_PER_SECOND,
                )
            } catch (e: OAuthExchangeException) {
                throw e
            } catch (e: Exception) {
                throw OAuthExchangeException(
                    "Token exchange failed",
                    cause = e,
                )
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Encodes raw bytes as a base64url string without padding.
     *
     * Uses [java.util.Base64] (not `android.util.Base64`) for JVM unit
     * test compatibility.
     *
     * @param bytes The raw byte array to encode.
     * @return Base64url-encoded string without trailing `=` padding.
     */
    private fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * Percent-encodes a string per RFC 3986.
     *
     * @param value The string to encode.
     * @return URL-encoded string with spaces encoded as `%20`.
     */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
