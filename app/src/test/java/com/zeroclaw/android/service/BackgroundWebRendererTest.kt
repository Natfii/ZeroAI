/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.ffi.WebRendererException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Unit tests for the WebView URL allowlist policy. */
class BackgroundWebRendererTest {
    @Test
    fun `normalizeRenderableUrl accepts http and https`() {
        assertEquals(
            "https://example.com/path?q=1",
            normalizeRenderableUrl("https://example.com/path?q=1"),
        )
        assertEquals(
            "http://localhost:8080/health",
            normalizeRenderableUrl("http://localhost:8080/health"),
        )
    }

    @Test
    fun `normalizeRenderableUrl rejects javascript scheme`() {
        assertThrows<WebRendererException.Render> {
            normalizeRenderableUrl("javascript:alert(1)")
        }
    }

    @Test
    fun `normalizeRenderableUrl rejects data scheme`() {
        assertThrows<WebRendererException.Render> {
            normalizeRenderableUrl("data:text/html,hello")
        }
    }

    @Test
    fun `normalizeRenderableUrl rejects content scheme`() {
        assertThrows<WebRendererException.Render> {
            normalizeRenderableUrl("content://example/path")
        }
    }

    @Test
    fun `normalizeRenderableUrl rejects file scheme`() {
        assertThrows<WebRendererException.Render> {
            normalizeRenderableUrl("file:///tmp/index.html")
        }
    }

    @Test
    fun `normalizeRenderableUrl rejects intent scheme`() {
        assertThrows<WebRendererException.Render> {
            normalizeRenderableUrl("intent://scan/#Intent;scheme=zxing;package=com.example;end")
        }
    }

    @Test
    fun `normalizeRenderableUrl rejects blank host`() {
        assertThrows<WebRendererException.Render> {
            normalizeRenderableUrl("https:///missing-host")
        }
    }
}
