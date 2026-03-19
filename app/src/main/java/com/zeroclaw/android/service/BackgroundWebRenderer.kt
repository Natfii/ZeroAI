/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.zeroclaw.ffi.WebRenderer
import com.zeroclaw.ffi.WebRendererException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless [WebView]-based implementation of the UniFFI [WebRenderer] callback interface.
 *
 * Manages a single [WebView] on a dedicated [HandlerThread] to render web pages that
 * require JavaScript execution (e.g. Cloudflare challenges, SPA content). The Rust engine
 * calls [renderPage] from a background thread; this class posts the WebView work to the
 * dedicated looper and blocks the caller with a [CountDownLatch] until rendering completes
 * or the timeout elapses.
 *
 * Security hardening applied to the WebView:
 * - No file access (file://, content://)
 * - No JavaScript bridges ([WebView.addJavascriptInterface] is never called)
 * - No geolocation
 * - No autofill
 * - Mixed content blocked ([WebSettings.MIXED_CONTENT_NEVER_ALLOW])
 * - Default cache mode ([WebSettings.LOAD_DEFAULT])
 * - Cookies persist in-memory for the daemon session (cleared on shutdown)
 *
 * @param context Application context used to create the [WebView]. Must be the application
 *   context (not an Activity context) to avoid memory leaks.
 */
class BackgroundWebRenderer(
    private val context: Context,
) : WebRenderer {
    private val handlerThread = HandlerThread("WebRenderer").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var webView: WebView? = null
    private var customUserAgent: String? = null

    /**
     * Sets a custom User-Agent string for the [WebView].
     *
     * Called from [DaemonServiceBridge] with a device-authentic UA that has the
     * `wv` WebView marker stripped. Must be called before [renderPage].
     *
     * @param ua The User-Agent string to use.
     */
    fun setUserAgent(ua: String) {
        customUserAgent = ua
    }

    /**
     * JavaScript snippet that strips non-content elements and returns the visible page text.
     *
     * Removes script, style, noscript, nav, footer, header, ARIA landmarks,
     * iframes, ad containers (class/id matching `ad-`, `ads-`, `sponsor`),
     * Google AdSense `ins` elements, cookie banners, and consent dialogs,
     * then returns `document.body.innerText`.
     */
    private val extractionJs: String =
        """
        (function() {
            var removes = document.querySelectorAll(
                'script, style, noscript, nav, footer, header, ' +
                '[role="banner"], [role="navigation"], ' +
                'iframe, ins, [class*="ad-"], [class*="ads-"], ' +
                '[id*="ad-"], [class*="sponsor"], [data-ad], ' +
                'ins.adsbygoogle, [class*="cookie-banner"], [class*="consent"]'
            );
            removes.forEach(function(el) { el.remove(); });
            return document.body ? document.body.innerText : '';
        })()
        """.trimIndent()

    /**
     * Renders a URL in a headless [WebView] and returns the extracted page text.
     *
     * Loads the given [url] on the dedicated WebView looper thread, waits for
     * [WebViewClient.onPageFinished] plus an additional settling delay for JavaScript
     * execution, then injects extraction JavaScript and returns the resulting text.
     *
     * **Thread safety:** This method is safe to call from any thread (including Rust
     * background threads via FFI). It blocks the calling thread with a [CountDownLatch]
     * until the result is available or the timeout elapses. The [WebView] operations
     * are always posted to the dedicated [HandlerThread] looper.
     *
     * @param url The URL to render. Must be an HTTP or HTTPS URL.
     * @param timeoutMs Maximum time in milliseconds to wait for the page to render.
     *   Includes page load time, JS settling delay, and text extraction.
     * @return Extracted visible text content of the rendered page.
     * @throws WebRendererException.Render if the page fails to load, the WebView
     *   encounters an error, or the operation times out. UniFFI marshals this back to
     *   `WebRendererError::Render` on the Rust side.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun renderPage(
        url: String,
        timeoutMs: ULong,
    ): String {
        val validatedUrl = normalizeRenderableUrl(url)
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<String?>(null)
        val errorRef = AtomicReference<String?>(null)

        handler.post {
            try {
                renderOnLooper(validatedUrl, timeoutMs, resultRef, errorRef, latch)
            } catch (e: Exception) {
                errorRef.set("WebView setup failed: ${e.message}")
                latch.countDown()
            }
        }

        val completed = latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!completed) {
            handler.post { cleanUpWebView() }
            throw WebRendererException.Render(
                "WebView render timed out after ${timeoutMs}ms for $url",
            )
        }

        val error = errorRef.get()
        if (error != null) {
            throw WebRendererException.Render(error)
        }

        return resultRef.get().orEmpty()
    }

    /**
     * Performs the actual WebView load and extraction on the dedicated looper thread.
     *
     * This method must only be called from the [handler] thread. It creates or resets
     * the WebView, configures security settings, loads the URL, and sets up the
     * [WebViewClient] that triggers extraction after the page finishes loading.
     *
     * @param url The URL to load.
     * @param timeoutMs The timeout in milliseconds (used for logging only here;
     *   the [CountDownLatch] in the caller enforces the actual timeout).
     * @param resultRef Atomic reference to store the extracted text on success.
     * @param errorRef Atomic reference to store an error message on failure.
     * @param latch Latch to count down when the result or error is ready.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun renderOnLooper(
        url: String,
        @Suppress("UnusedParameter") timeoutMs: ULong,
        resultRef: AtomicReference<String?>,
        errorRef: AtomicReference<String?>,
        latch: CountDownLatch,
    ) {
        val pollingStarted = AtomicBoolean(false)
        val wv = ensureWebView()
        configureWebViewSettings(wv)

        wv.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView,
                    finishedUrl: String,
                ) {
                    if (!pollingStarted.compareAndSet(false, true)) return
                    handler.postDelayed(
                        {
                            pollForStability(
                                view,
                                resultRef,
                                errorRef,
                                latch,
                                previousLength = 0,
                                elapsed = 0L,
                            )
                        },
                        POLL_INITIAL_DELAY_MS,
                    )
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        errorRef.set(
                            "WebView error ${error.errorCode}: ${error.description}" +
                                " for $url",
                        )
                        latch.countDown()
                    }
                }
            }

        wv.loadUrl(url)
    }

    /**
     * Injects the extraction JavaScript into the WebView and stores the result.
     *
     * @param view The WebView to extract text from.
     * @param resultRef Atomic reference to store the extracted text.
     * @param errorRef Atomic reference to store an error message if extraction fails.
     * @param latch Latch to count down when extraction completes.
     */
    private fun extractText(
        view: WebView,
        resultRef: AtomicReference<String?>,
        errorRef: AtomicReference<String?>,
        latch: CountDownLatch,
    ) {
        view.evaluateJavascript(extractionJs) { rawResult ->
            try {
                val text = unescapeJsResult(rawResult)
                resultRef.set(text)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                errorRef.set("JS extraction failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
    }

    /**
     * Polls the page text length until it stabilises, then extracts content.
     *
     * Measures `document.body.innerText.length` via JavaScript. When the length
     * changes by less than [STABILITY_THRESHOLD] (5%) between consecutive polls,
     * or [POLL_MAX_TOTAL_MS] elapses, triggers [extractText].
     *
     * @param view The [WebView] to poll.
     * @param resultRef Atomic reference to store the extracted text.
     * @param errorRef Atomic reference to store an error message.
     * @param latch Latch to count down when extraction completes.
     * @param previousLength Text length from the previous poll (0 on first call).
     * @param elapsed Total milliseconds spent polling so far.
     */
    @Suppress("LongParameterList")
    private fun pollForStability(
        view: WebView,
        resultRef: AtomicReference<String?>,
        errorRef: AtomicReference<String?>,
        latch: CountDownLatch,
        previousLength: Int,
        elapsed: Long,
    ) {
        view.evaluateJavascript("document.body ? document.body.innerText.length : 0") { raw ->
            val currentLength = raw?.toIntOrNull() ?: 0
            val change =
                if (previousLength == 0) {
                    1.0
                } else {
                    Math.abs(currentLength - previousLength).toDouble() / previousLength
                }
            if (change < STABILITY_THRESHOLD || elapsed >= POLL_MAX_TOTAL_MS) {
                extractText(view, resultRef, errorRef, latch)
            } else {
                handler.postDelayed(
                    {
                        pollForStability(
                            view,
                            resultRef,
                            errorRef,
                            latch,
                            currentLength,
                            elapsed + POLL_INTERVAL_MS,
                        )
                    },
                    POLL_INTERVAL_MS,
                )
            }
        }
    }

    /**
     * Ensures a [WebView] instance exists on the dedicated looper thread.
     *
     * Creates a new WebView if one has not been created yet, using the application
     * [context]. The WebView is retained across calls for reuse.
     *
     * @return The existing or newly created [WebView].
     */
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context)
        webView = wv
        return wv
    }

    /**
     * Applies security-hardened settings to the [WebView].
     *
     * Enables JavaScript (required for SPA rendering and text extraction) but
     * disables all other potentially dangerous features.
     *
     * @param wv The [WebView] to configure.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewSettings(wv: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        wv.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            databaseEnabled = false
            domStorageEnabled = true
            setSupportZoom(false)
            @Suppress("DEPRECATION")
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            blockNetworkImage = false
            customUserAgent?.let { userAgentString = it }
        }
    }

    /**
     * Destroys the WebView and releases its resources.
     *
     * Called when the renderer encounters an error or is being shut down.
     */
    private fun cleanUpWebView() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearCache(true)
            destroy()
        }
        webView = null
    }

    /**
     * Stops the background [HandlerThread] and destroys the [WebView].
     *
     * After calling this method, the renderer is no longer usable. Must be called
     * when the foreground service is stopping or when the renderer is no longer needed.
     */
    fun shutdown() {
        handler.post {
            CookieManager.getInstance().removeAllCookies(null)
            cleanUpWebView()
        }
        handlerThread.quitSafely()
    }

    /** Constants for [BackgroundWebRenderer]. */
    companion object {
        @Suppress("UnusedPrivateProperty")
        private const val TAG = "BackgroundWebRenderer"

        /** Initial delay after [WebViewClient.onPageFinished] before first stability poll. */
        private const val POLL_INITIAL_DELAY_MS = 500L

        /** Interval between consecutive text-length stability polls. */
        private const val POLL_INTERVAL_MS = 500L

        /** Maximum total polling time before forced extraction. */
        private const val POLL_MAX_TOTAL_MS = 8000L

        /** Fractional change threshold below which the page is considered stable. */
        private const val STABILITY_THRESHOLD = 0.05
    }
}

@Suppress("ThrowsCount")
internal fun normalizeRenderableUrl(url: String): String {
    val parsed =
        try {
            java.net.URI(url)
        } catch (_: java.net.URISyntaxException) {
            throw WebRendererException.Render("WebView render rejected malformed URL")
        }
    val scheme = parsed.scheme?.lowercase()
    val host = parsed.host?.trim().orEmpty()
    val rejection =
        when {
            scheme != "http" && scheme != "https" ->
                "WebView render rejected unsupported URL scheme"
            host.isBlank() -> "WebView render rejected URL without a host"
            else -> null
        }
    if (rejection != null) {
        throw WebRendererException.Render(rejection)
    }
    return parsed.normalize().toString()
}

/**
 * Unescapes a JavaScript result string returned by [WebView.evaluateJavascript].
 *
 * The WebView returns JS string results wrapped in double quotes with internal
 * characters escaped (e.g. `\"`, `\n`, `\t`). This function strips the surrounding
 * quotes and unescapes the content. Returns an empty string for null or "null" results.
 *
 * @param raw The raw string returned by [WebView.evaluateJavascript].
 * @return The unescaped text content.
 */
private fun unescapeJsResult(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    var text = raw
    if (text.startsWith("\"") && text.endsWith("\"")) {
        text = text.substring(1, text.length - 1)
    }
    return text
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
