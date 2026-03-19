/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.twitter

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView

private const val LOGIN_URL = "https://x.com/i/flow/login"
private const val COOKIE_DOMAIN = "https://x.com"

/**
 * Full-screen dialog that loads the X/Twitter login page in a WebView.
 *
 * After the user completes login (including any 2FA or CAPTCHA), the composable
 * detects `ct0` and `auth_token` cookies via [CookieManager] and calls
 * [onCookiesExtracted] with the full cookie string.
 *
 * @param onCookiesExtracted Called with the full cookie string once both required cookies are found.
 * @param onDismiss Called when the user dismisses the login sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TwitterLoginSheet(
    onCookiesExtracted: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to X") },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Close X login"
                            },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    CookieManager.getInstance().apply {
                        removeAllCookies(null)
                        flush()
                    }
                    WebView(context).apply {
                        @Suppress("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?,
                                ) {
                                    super.onPageFinished(view, url)
                                    checkForLoginCookies(onCookiesExtracted)
                                }
                            }
                        loadUrl(LOGIN_URL)
                    }
                },
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
    }
}

/**
 * Checks [CookieManager] for the required `ct0` and `auth_token` cookies on x.com.
 *
 * If both are present, calls [onExtracted] with the full cookie string.
 */
private fun checkForLoginCookies(onExtracted: (String) -> Unit) {
    val cookieString = CookieManager.getInstance().getCookie(COOKIE_DOMAIN) ?: return
    val cookies =
        cookieString.split(";").associate { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to ""
        }
    val hasCt0 = cookies.containsKey("ct0") && cookies["ct0"]?.isNotBlank() == true
    val hasAuthToken =
        cookies.containsKey("auth_token") && cookies["auth_token"]?.isNotBlank() == true
    if (hasCt0 && hasAuthToken) {
        onExtracted(cookieString)
    }
}
