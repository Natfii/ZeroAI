/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.features

import com.zeroclaw.android.service.ConfigTomlBuilder

/**
 * IMAP / SMTP email integration.
 *
 * Active when the email repository reports an enabled configuration
 * with a non-blank address. Emits the upstream `[email]` TOML section
 * so the daemon spins up its inbox-polling cron and exposes the email
 * tool surface to the agent.
 *
 * The email address is also threaded into the awareness fragment so
 * the agent knows which account it's looking at.
 */
class EmailContributor : FeatureContributor {
    override val key: String = "email"

    override suspend fun contribute(ctx: FeatureContext) {
        val cfg = ctx.emailConfig ?: return
        if (!cfg.isEnabled || cfg.address.isBlank()) return

        ctx.emitToml {
            appendLine("[email]")
            appendLine("enabled = true")
            appendLine("imap_host = ${ConfigTomlBuilder.tomlString(cfg.imapHost)}")
            appendLine("imap_port = ${cfg.imapPort}")
            appendLine("smtp_host = ${ConfigTomlBuilder.tomlString(cfg.smtpHost)}")
            appendLine("smtp_port = ${cfg.smtpPort}")
            appendLine("address = ${ConfigTomlBuilder.tomlString(cfg.address)}")
            appendLine("password = ${ConfigTomlBuilder.tomlString(cfg.password)}")
            if (cfg.checkTimes.isNotEmpty()) {
                val times =
                    cfg.checkTimes.joinToString(", ") { ConfigTomlBuilder.tomlString(it) }
                appendLine("check_times = [$times]")
            }
        }
        ctx.appendAwareness(
            "- Email: Connected as ${cfg.address}. Use email tools to check, read, search, and compose email.",
        )
    }
}
