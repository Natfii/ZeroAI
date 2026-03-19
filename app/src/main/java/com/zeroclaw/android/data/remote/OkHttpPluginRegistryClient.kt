/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import com.zeroclaw.android.model.RemotePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OkHttp-backed [PluginRegistryClient] implementation.
 *
 * Performs HTTP GET requests on [Dispatchers.IO] and parses the
 * response JSON via `kotlinx.serialization`.
 *
 * @param client OkHttp client instance (shared for connection pooling).
 */
class OkHttpPluginRegistryClient(
    private val client: OkHttpClient = OkHttpClient(),
) : PluginRegistryClient {
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("InjectDispatcher")
    override suspend fun fetchPlugins(registryUrl: String): List<RemotePlugin> =
        withContext(Dispatchers.IO) {
            require(registryUrl.startsWith("https://")) {
                "Registry URL must use HTTPS: $registryUrl"
            }
            val request =
                Request
                    .Builder()
                    .url(registryUrl)
                    .get()
                    .build()
            val response = client.newCall(request).execute()
            val body =
                response.use { resp ->
                    check(resp.isSuccessful) {
                        "Registry fetch failed: HTTP ${resp.code}"
                    }
                    val source =
                        resp.body?.source()
                            ?: error("Empty response body from registry")
                    source.request(MAX_RESPONSE_BYTES + 1)
                    check(source.buffer.size <= MAX_RESPONSE_BYTES) {
                        "Registry response exceeds ${MAX_RESPONSE_BYTES / BYTES_PER_KB} KB limit"
                    }
                    source.buffer.readUtf8()
                }
            json.decodeFromString<List<RemotePlugin>>(body)
        }

    private companion object {
        /** Maximum registry response body size (1 MB). */
        const val MAX_RESPONSE_BYTES = 1L * 1024 * 1024

        /** Bytes per kilobyte for display formatting. */
        const val BYTES_PER_KB = 1024
    }
}
