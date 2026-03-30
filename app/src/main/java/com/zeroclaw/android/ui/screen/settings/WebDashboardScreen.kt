/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.util.rememberPowerSaveMode

/**
 * Full-screen WebView pointing at the gateway's React SPA on localhost.
 *
 * Displays the daemon's web dashboard for full engine configuration.
 * JavaScript is enabled for the SPA; navigation is restricted to
 * `127.0.0.1` to prevent the WebView from loading external URLs.
 *
 * @param webDashboardViewModel ViewModel providing gateway connection data.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun WebDashboardScreen(
    webDashboardViewModel: WebDashboardViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by webDashboardViewModel.uiState.collectAsStateWithLifecycle()

    when (val currentState = state) {
        is WebDashboardViewModel.UiState.Loading -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }

        is WebDashboardViewModel.UiState.Error -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudOff,
                    contentDescription = "Service not running",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = currentState.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { webDashboardViewModel.retry() }) {
                    Text("Retry")
                }
            }
        }

        is WebDashboardViewModel.UiState.Content -> {
            val data = currentState.data
            val url = "http://127.0.0.1:${data.port}/_app/"
            val isPowerSave = rememberPowerSaveMode()

            @Suppress("SetJavaScriptEnabled")
            AndroidView(
                modifier = modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false

                        webViewClient =
                            object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val requestUrl = request?.url?.toString() ?: return true
                                    return !requestUrl.startsWith("http://127.0.0.1")
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?,
                                ) {
                                    view?.evaluateJavascript(
                                        "window.__ZERO_POWER_SAVE = $isPowerSave;",
                                        null,
                                    )
                                }
                            }

                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setCookie(
                                "http://127.0.0.1:${data.port}",
                                "token=${data.token}; HttpOnly",
                            )
                            flush()
                        }

                        loadUrl(url)
                    }
                },
                update = { webView ->
                    webView.evaluateJavascript(
                        "window.__ZERO_POWER_SAVE = $isPowerSave;",
                        null,
                    )
                },
            )
        }
    }
}
