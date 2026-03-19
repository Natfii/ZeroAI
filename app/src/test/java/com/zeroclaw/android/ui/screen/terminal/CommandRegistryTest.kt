/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CommandRegistry].
 *
 * Validates command lookup, prefix matching, and input parsing for
 * slash commands, local actions, and plain chat messages.
 */
@DisplayName("CommandRegistry")
class CommandRegistryTest {
    @Nested
    @DisplayName("find")
    inner class Find {
        @Test
        @DisplayName("returns correct command for known name")
        fun `find returns correct command for known name`() {
            val command = CommandRegistry.find("status")
            assertNotNull(command)
            assertEquals("status", command!!.name)
        }

        @Test
        @DisplayName("returns null for unknown name")
        fun `find returns null for unknown name`() {
            val command = CommandRegistry.find("nonexistent")
            assertNull(command)
        }
    }

    @Nested
    @DisplayName("matches")
    inner class Matches {
        @Test
        @DisplayName("filters by prefix")
        fun `matches filters by prefix`() {
            val results = CommandRegistry.matches("co")
            val names = results.map { it.name }
            assertTrue(names.contains("cost"))
            assertTrue(names.contains("cost daily"))
            assertTrue(names.contains("cost monthly"))
        }

        @Test
        @DisplayName("returns all commands for empty prefix")
        fun `matches returns all commands for empty prefix`() {
            val results = CommandRegistry.matches("")
            assertEquals(CommandRegistry.commands.size, results.size)
        }
    }

    @Nested
    @DisplayName("parseAndTranslate")
    inner class ParseAndTranslate {
        @Test
        @DisplayName("routes slash commands to RhaiExpression")
        fun `parseAndTranslate routes slash commands to RhaiExpression`() {
            val result = CommandRegistry.parseAndTranslate("/status")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals("status()", (result as CommandResult.RhaiExpression).expression)
        }

        @Test
        @DisplayName("routes plain text to ChatMessage")
        fun `parseAndTranslate routes plain text to ChatMessage`() {
            val result = CommandRegistry.parseAndTranslate("hello")
            assertTrue(result is CommandResult.ChatMessage)
            assertEquals("hello", (result as CommandResult.ChatMessage).text)
        }

        @Test
        @DisplayName("routes help to LocalAction")
        fun `parseAndTranslate routes help to LocalAction`() {
            val result = CommandRegistry.parseAndTranslate("/help")
            assertTrue(result is CommandResult.LocalAction)
            assertEquals("help", (result as CommandResult.LocalAction).action)
        }

        @Test
        @DisplayName("routes clear to LocalAction")
        fun `parseAndTranslate routes clear to LocalAction`() {
            val result = CommandRegistry.parseAndTranslate("/clear")
            assertTrue(result is CommandResult.LocalAction)
            assertEquals("clear", (result as CommandResult.LocalAction).action)
        }

        @Test
        @DisplayName("cost daily with args generates correct expression")
        fun `cost daily with args generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/cost daily 2026 2 27")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "cost_daily(2026, 2, 27)",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("memory recall escapes quotes in query")
        fun `memory recall escapes quotes in query`() {
            val result = CommandRegistry.parseAndTranslate("/memory recall he said \"hello\"")
            assertTrue(result is CommandResult.RhaiExpression)
            val expression = (result as CommandResult.RhaiExpression).expression
            assertTrue(expression.contains("\\\"hello\\\""))
        }

        @Test
        @DisplayName("cron add with expression and command")
        fun `cron add with expression and command`() {
            val result = CommandRegistry.parseAndTranslate("/cron add 0/5 echo test")
            assertTrue(result is CommandResult.RhaiExpression)
            val expression = (result as CommandResult.RhaiExpression).expression
            assertEquals("cron_add(\"0/5\", \"echo test\")", expression)
        }

        @Test
        @DisplayName("empty input returns empty ChatMessage")
        fun `empty input returns empty ChatMessage`() {
            val result = CommandRegistry.parseAndTranslate("")
            assertTrue(result is CommandResult.ChatMessage)
            assertEquals("", (result as CommandResult.ChatMessage).text)
        }

        @Test
        @DisplayName("unknown slash command falls through to ChatMessage")
        fun `unknown slash command falls through to ChatMessage`() {
            val result = CommandRegistry.parseAndTranslate("/nonexistent")
            assertTrue(result is CommandResult.ChatMessage)
        }

        @Test
        @DisplayName("version command generates correct expression")
        fun `version command generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/version")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals("version()", (result as CommandResult.RhaiExpression).expression)
        }

        @Test
        @DisplayName("doctor without args calls zero-arg overload")
        fun `doctor without args calls zero-arg overload`() {
            val result = CommandRegistry.parseAndTranslate("/doctor")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals("doctor()", (result as CommandResult.RhaiExpression).expression)
        }

        @Test
        @DisplayName("doctor with args passes config and data dir")
        fun `doctor with args passes config and data dir`() {
            val result = CommandRegistry.parseAndTranslate("/doctor config.toml /data")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "doctor(\"config.toml\", \"/data\")",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("cost daily without args calls zero-arg overload")
        fun `cost daily without args calls zero-arg overload`() {
            val result = CommandRegistry.parseAndTranslate("/cost daily")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals("cost_daily()", (result as CommandResult.RhaiExpression).expression)
        }

        @Test
        @DisplayName("cost monthly without args calls zero-arg overload")
        fun `cost monthly without args calls zero-arg overload`() {
            val result = CommandRegistry.parseAndTranslate("/cost monthly")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals("cost_monthly()", (result as CommandResult.RhaiExpression).expression)
        }

        @Test
        @DisplayName("cost monthly with args generates correct expression")
        fun `cost monthly with args generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/cost monthly 2026 3")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "cost_monthly(2026, 3)",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("config generates correct expression")
        fun `config generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/config")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals("config()", (result as CommandResult.RhaiExpression).expression)
        }

        @Test
        @DisplayName("traces without args uses default limit")
        fun `traces without args uses default limit`() {
            val result = CommandRegistry.parseAndTranslate("/traces")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals("traces(20)", (result as CommandResult.RhaiExpression).expression)
        }

        @Test
        @DisplayName("traces with filter generates filter expression")
        fun `traces with filter generates filter expression`() {
            val result = CommandRegistry.parseAndTranslate("/traces error")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "traces_filter(\"error\", 20)",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("bind with args generates correct expression")
        fun `bind with args generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/bind telegram alice")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "bind(\"telegram\", \"alice\")",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("allowlist generates correct expression")
        fun `allowlist generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/allowlist telegram")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "allowlist(\"telegram\")",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("swap with args generates correct expression")
        fun `swap with args generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/swap anthropic claude-sonnet-4")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "swap_provider(\"anthropic\", \"claude-sonnet-4\")",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("models generates correct expression")
        fun `models generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/models anthropic")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "models(\"anthropic\")",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("auth generates correct expression")
        fun `auth generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/auth")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals("auth_list()", (result as CommandResult.RhaiExpression).expression)
        }

        @Test
        @DisplayName("auth remove with args generates correct expression")
        fun `auth remove with args generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/auth remove openai default")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "auth_remove(\"openai\", \"default\")",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("cron at with args generates correct expression")
        fun `cron at with args generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/cron at 2026-12-31T23:59:59Z echo done")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "cron_add_at(\"2026-12-31T23:59:59Z\", \"echo done\")",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("cron every with args generates correct expression")
        fun `cron every with args generates correct expression`() {
            val result = CommandRegistry.parseAndTranslate("/cron every 60000 echo tick")
            assertTrue(result is CommandResult.RhaiExpression)
            assertEquals(
                "cron_add_every(60000, \"echo tick\")",
                (result as CommandResult.RhaiExpression).expression,
            )
        }

        @Test
        @DisplayName("prefix match includes new commands")
        fun `prefix match includes new commands`() {
            val results = CommandRegistry.matches("tr")
            val names = results.map { it.name }
            assertTrue(names.contains("traces"))
        }

        @Test
        @DisplayName("scripts routes to workspace script listing")
        fun `scripts routes to workspace script listing`() {
            val result = CommandRegistry.parseAndTranslate("/scripts")
            assertTrue(result is CommandResult.WorkspaceScriptCommand)
            assertEquals(
                WorkspaceScriptAction.List,
                (result as CommandResult.WorkspaceScriptCommand).action,
            )
        }

        @Test
        @DisplayName("scripts validate routes to workspace script validation")
        fun `scripts validate routes to workspace script validation`() {
            val result = CommandRegistry.parseAndTranslate("/scripts validate workflows/cleanup.rhai")
            assertTrue(result is CommandResult.WorkspaceScriptCommand)
            assertEquals(
                WorkspaceScriptAction.Validate("workflows/cleanup.rhai"),
                (result as CommandResult.WorkspaceScriptCommand).action,
            )
        }

        @Test
        @DisplayName("scripts run rejects traversal paths")
        fun `scripts run rejects traversal paths`() {
            val result = CommandRegistry.parseAndTranslate("/scripts run ../secrets.rhai")
            assertTrue(result is CommandResult.WorkspaceScriptCommand)
            val action = (result as CommandResult.WorkspaceScriptCommand).action
            assertTrue(action is WorkspaceScriptAction.Invalid)
        }
    }

    @Nested
    @DisplayName("/nano subcommand routing")
    inner class NanoRouting {
        @Test
        @DisplayName("bare /nano routes to NanoCommand with General intent")
        fun `bare nano routes to NanoCommand with General intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.General)
            assertEquals("", (intent as NanoIntent.General).prompt)
        }

        @Test
        @DisplayName("/nano with plain text routes to General intent")
        fun `nano with plain text routes to General intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano hello world")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.General)
            assertEquals("hello world", (intent as NanoIntent.General).prompt)
        }

        @Test
        @DisplayName("/nano summarize routes to Summarize intent")
        fun `nano summarize routes to Summarize intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano summarize this text")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.Summarize)
            assertEquals("this text", (intent as NanoIntent.Summarize).text)
        }

        @Test
        @DisplayName("/nano tldr routes to Summarize intent")
        fun `nano tldr routes to Summarize intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano tldr of the meeting")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.Summarize)
        }

        @Test
        @DisplayName("/nano proofread routes to Proofread intent")
        fun `nano proofread routes to Proofread intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano proofread my essay")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.Proofread)
            assertEquals("my essay", (intent as NanoIntent.Proofread).text)
        }

        @Test
        @DisplayName("/nano fix the grammar routes to Proofread intent")
        fun `nano fix the grammar routes to Proofread intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano fix the grammar in this sentence")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.Proofread)
        }

        @Test
        @DisplayName("/nano rewrite with style routes to Rewrite intent")
        fun `nano rewrite with style routes to Rewrite intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano rewrite formal this email")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.Rewrite)
            assertEquals(RewriteStyle.PROFESSIONAL, (intent as NanoIntent.Rewrite).style)
        }

        @Test
        @DisplayName("/nano make it shorter routes to Rewrite with SHORTEN style")
        fun `nano make it shorter routes to Rewrite with SHORTEN style`() {
            val result = CommandRegistry.parseAndTranslate("/nano make it shorter please")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.Rewrite)
            assertEquals(RewriteStyle.SHORTEN, (intent as NanoIntent.Rewrite).style)
        }

        @Test
        @DisplayName("/nano describe routes to Describe intent")
        fun `nano describe routes to Describe intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano describe")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.Describe)
        }

        @Test
        @DisplayName("/nano what do you see routes to Describe intent")
        fun `nano what do you see routes to Describe intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano what do you see")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.Describe)
        }

        @Test
        @DisplayName("/nano preserves case in user text for General intent")
        fun `nano preserves case in user text for General intent`() {
            val result = CommandRegistry.parseAndTranslate("/nano Tell Me About Kotlin")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.General)
            assertEquals("Tell Me About Kotlin", (intent as NanoIntent.General).prompt)
        }

        @Test
        @DisplayName("/nano with leading spaces trims correctly")
        fun `nano with leading spaces trims correctly`() {
            val result = CommandRegistry.parseAndTranslate("/nano   summarize this")
            assertTrue(result is CommandResult.NanoCommand)
            val intent = (result as CommandResult.NanoCommand).intent
            assertTrue(intent is NanoIntent.Summarize)
        }
    }
}
