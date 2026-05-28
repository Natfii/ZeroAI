/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.features

import com.zeroclaw.android.service.GlobalTomlConfig

/**
 * Twitter/X read-only integration.
 *
 * Emits the upstream `[twitter_browse]` TOML section when the toggle is on
 * so the daemon registers the `twitter_read_profile` tool. Auth is not
 * required — the tool uses `syndication.twitter.com` which serves public
 * profile timelines anonymously.
 */
class TwitterContributor : FeatureContributor {
    override val key: String = "twitter"

    override suspend fun contribute(ctx: FeatureContext) {
        val s = ctx.settings
        if (!s.twitterBrowseEnabled) return

        ctx.emitToml {
            appendLine("[twitter_browse]")
            appendLine("enabled = true")
            if (s.twitterBrowseMaxItems != GlobalTomlConfig.DEFAULT_TWITTER_BROWSE_MAX_ITEMS) {
                appendLine("max_items = ${s.twitterBrowseMaxItems.coerceAtLeast(0L)}")
            }
            if (s.twitterBrowseTimeoutSecs != GlobalTomlConfig.DEFAULT_TWITTER_BROWSE_TIMEOUT_SECS) {
                appendLine("timeout_secs = ${s.twitterBrowseTimeoutSecs.coerceAtLeast(0L)}")
            }
        }
        ctx.appendAwareness(
            "- X (Twitter): Enabled. Use twitter_read_profile to read recent public " +
                "tweets from any account by handle. Read-only and public-only — protected " +
                "accounts return empty.",
        )
    }
}
