/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
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
    }
}
