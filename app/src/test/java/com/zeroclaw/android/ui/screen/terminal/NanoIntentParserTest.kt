/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [NanoIntentParser].
 *
 * Validates trigger keyword matching, word-boundary enforcement,
 * text extraction, rewrite style resolution, and fallback behavior.
 */
@DisplayName("NanoIntentParser")
class NanoIntentParserTest {
    @Nested
    @DisplayName("Summarize triggers")
    inner class SummarizeTriggers {
        @ParameterizedTest(name = "trigger \"{0}\" produces Summarize")
        @ValueSource(
            strings = [
                "summarize",
                "sum up",
                "tldr",
                "give me the gist",
                "key points",
                "recap",
                "overview",
                "brief me",
            ],
        )
        @DisplayName("each summarize trigger produces Summarize intent")
        fun `each summarize trigger produces Summarize`(trigger: String) {
            val result = NanoIntentParser.parse(trigger)
            assertTrue(result is NanoIntent.Summarize)
        }

        @Test
        @DisplayName("extracts remaining text after trigger")
        fun `extracts remaining text after trigger`() {
            val result = NanoIntentParser.parse("summarize this paragraph please")
            assertTrue(result is NanoIntent.Summarize)
            assertEquals(
                "this paragraph please",
                (result as NanoIntent.Summarize).text,
            )
        }

        @Test
        @DisplayName("produces empty text when trigger is the entire input")
        fun `produces empty text when trigger is the entire input`() {
            val result = NanoIntentParser.parse("summarize")
            assertTrue(result is NanoIntent.Summarize)
            assertEquals("", (result as NanoIntent.Summarize).text)
        }

        @Test
        @DisplayName("case insensitive matching")
        fun `case insensitive matching`() {
            val result = NanoIntentParser.parse("SUMMARIZE this text")
            assertTrue(result is NanoIntent.Summarize)
            assertEquals(
                "this text",
                (result as NanoIntent.Summarize).text,
            )
        }

        @Test
        @DisplayName("mixed case matching")
        fun `mixed case matching`() {
            val result = NanoIntentParser.parse("TlDr of the meeting")
            assertTrue(result is NanoIntent.Summarize)
            assertEquals(
                "of the meeting",
                (result as NanoIntent.Summarize).text,
            )
        }

        @Test
        @DisplayName("give me the gist extracts remaining text")
        fun `give me the gist extracts remaining text`() {
            val result = NanoIntentParser.parse("give me the gist of this email")
            assertTrue(result is NanoIntent.Summarize)
            assertEquals(
                "of this email",
                (result as NanoIntent.Summarize).text,
            )
        }

        @Test
        @DisplayName("key points extracts remaining text")
        fun `key points extracts remaining text`() {
            val result = NanoIntentParser.parse("key points from the article")
            assertTrue(result is NanoIntent.Summarize)
            assertEquals(
                "from the article",
                (result as NanoIntent.Summarize).text,
            )
        }
    }

    @Nested
    @DisplayName("Proofread triggers")
    inner class ProofreadTriggers {
        @ParameterizedTest(name = "trigger \"{0}\" produces Proofread")
        @ValueSource(
            strings = [
                "proofread",
                "fix grammar",
                "fix the grammar",
                "check spelling",
                "correct this",
                "grammar check",
                "spell check",
            ],
        )
        @DisplayName("each proofread trigger produces Proofread intent")
        fun `each proofread trigger produces Proofread`(trigger: String) {
            val result = NanoIntentParser.parse(trigger)
            assertTrue(result is NanoIntent.Proofread)
        }

        @Test
        @DisplayName("extracts remaining text after trigger")
        fun `extracts remaining text after trigger`() {
            val result = NanoIntentParser.parse("proofread this sentence is bad")
            assertTrue(result is NanoIntent.Proofread)
            assertEquals(
                "this sentence is bad",
                (result as NanoIntent.Proofread).text,
            )
        }

        @Test
        @DisplayName("fix the grammar extracts remaining text")
        fun `fix the grammar extracts remaining text`() {
            val result = NanoIntentParser.parse("fix the grammar in this text")
            assertTrue(result is NanoIntent.Proofread)
            assertEquals(
                "in this text",
                (result as NanoIntent.Proofread).text,
            )
        }

        @Test
        @DisplayName("case insensitive matching")
        fun `case insensitive matching`() {
            val result = NanoIntentParser.parse("PROOFREAD my essay")
            assertTrue(result is NanoIntent.Proofread)
            assertEquals(
                "my essay",
                (result as NanoIntent.Proofread).text,
            )
        }
    }

    @Nested
    @DisplayName("Rewrite triggers")
    inner class RewriteTriggers {
        @ParameterizedTest(name = "trigger \"{0}\" produces Rewrite")
        @ValueSource(
            strings = [
                "rewrite",
                "rephrase",
                "make it",
                "make this",
                "change tone",
                "sound more",
                "tone",
            ],
        )
        @DisplayName("each rewrite trigger produces Rewrite intent")
        fun `each rewrite trigger produces Rewrite`(trigger: String) {
            val result = NanoIntentParser.parse(trigger)
            assertTrue(result is NanoIntent.Rewrite)
        }

        @Test
        @DisplayName("extracts remaining text after trigger")
        fun `extracts remaining text after trigger`() {
            val result = NanoIntentParser.parse("rewrite this paragraph better")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                "this paragraph better",
                (result as NanoIntent.Rewrite).text,
            )
        }

        @Test
        @DisplayName("defaults to REPHRASE when no style keyword")
        fun `defaults to REPHRASE when no style keyword`() {
            val result = NanoIntentParser.parse("rewrite this sentence")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                RewriteStyle.REPHRASE,
                (result as NanoIntent.Rewrite).style,
            )
        }

        @Test
        @DisplayName("case insensitive matching")
        fun `case insensitive matching`() {
            val result = NanoIntentParser.parse("REWRITE this text")
            assertTrue(result is NanoIntent.Rewrite)
        }
    }

    @Nested
    @DisplayName("Rewrite style resolution")
    inner class RewriteStyleResolution {
        @ParameterizedTest(name = "keyword \"{0}\" resolves to PROFESSIONAL")
        @ValueSource(
            strings = [
                "professional",
                "formal",
                "business",
            ],
        )
        @DisplayName("PROFESSIONAL style keywords")
        fun `professional style keywords`(keyword: String) {
            val result = NanoIntentParser.parse("make it $keyword")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                RewriteStyle.PROFESSIONAL,
                (result as NanoIntent.Rewrite).style,
            )
        }

        @ParameterizedTest(name = "keyword \"{0}\" resolves to FRIENDLY")
        @ValueSource(
            strings = [
                "friendly",
                "casual",
                "conversational",
                "friendlier",
            ],
        )
        @DisplayName("FRIENDLY style keywords")
        fun `friendly style keywords`(keyword: String) {
            val result = NanoIntentParser.parse("make it $keyword")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                RewriteStyle.FRIENDLY,
                (result as NanoIntent.Rewrite).style,
            )
        }

        @ParameterizedTest(name = "keyword \"{0}\" resolves to SHORTEN")
        @ValueSource(
            strings = [
                "shorter",
                "shorten",
                "concise",
                "brief",
            ],
        )
        @DisplayName("SHORTEN style keywords")
        fun `shorten style keywords`(keyword: String) {
            val result = NanoIntentParser.parse("make it $keyword")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                RewriteStyle.SHORTEN,
                (result as NanoIntent.Rewrite).style,
            )
        }

        @ParameterizedTest(name = "keyword \"{0}\" resolves to ELABORATE")
        @ValueSource(
            strings = [
                "elaborate",
                "expand",
                "longer",
                "more detail",
                "detail",
            ],
        )
        @DisplayName("ELABORATE style keywords")
        fun `elaborate style keywords`(keyword: String) {
            val result = NanoIntentParser.parse("rewrite with $keyword")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                RewriteStyle.ELABORATE,
                (result as NanoIntent.Rewrite).style,
            )
        }

        @ParameterizedTest(name = "keyword \"{0}\" resolves to REPHRASE")
        @ValueSource(
            strings = [
                "rephrase",
                "different words",
                "alternative",
            ],
        )
        @DisplayName("REPHRASE style keywords")
        fun `rephrase style keywords`(keyword: String) {
            val result = NanoIntentParser.parse("rewrite with $keyword")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                RewriteStyle.REPHRASE,
                (result as NanoIntent.Rewrite).style,
            )
        }

        @ParameterizedTest(name = "keyword \"{0}\" resolves to EMOJIFY")
        @ValueSource(
            strings = [
                "emoji",
                "emojify",
            ],
        )
        @DisplayName("EMOJIFY style keywords")
        fun `emojify style keywords`(keyword: String) {
            val result = NanoIntentParser.parse("rewrite with $keyword")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                RewriteStyle.EMOJIFY,
                (result as NanoIntent.Rewrite).style,
            )
        }

        @Test
        @DisplayName("sound more professional resolves style")
        fun `sound more professional resolves style`() {
            val result = NanoIntentParser.parse("sound more professional")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                RewriteStyle.PROFESSIONAL,
                (result as NanoIntent.Rewrite).style,
            )
        }

        @Test
        @DisplayName("change tone to casual resolves style")
        fun `change tone to casual resolves style`() {
            val result = NanoIntentParser.parse("change tone to casual")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                RewriteStyle.FRIENDLY,
                (result as NanoIntent.Rewrite).style,
            )
        }
    }

    @Nested
    @DisplayName("Describe triggers")
    inner class DescribeTriggers {
        @ParameterizedTest(name = "trigger \"{0}\" produces Describe")
        @ValueSource(
            strings = [
                "describe",
                "what's in this",
                "what do you see",
                "what is this image",
            ],
        )
        @DisplayName("each describe trigger produces Describe intent")
        fun `each describe trigger produces Describe`(trigger: String) {
            val result = NanoIntentParser.parse(trigger)
            assertEquals(NanoIntent.Describe, result)
        }

        @Test
        @DisplayName("case insensitive matching")
        fun `case insensitive matching`() {
            val result = NanoIntentParser.parse("DESCRIBE this photo")
            assertEquals(NanoIntent.Describe, result)
        }
    }

    @Nested
    @DisplayName("General fallback")
    inner class GeneralFallback {
        @Test
        @DisplayName("unrecognized input falls through to General")
        fun `unrecognized input falls through to General`() {
            val result = NanoIntentParser.parse("what is the capital of France")
            assertTrue(result is NanoIntent.General)
            assertEquals(
                "what is the capital of France",
                (result as NanoIntent.General).prompt,
            )
        }

        @Test
        @DisplayName("empty input produces General with empty prompt")
        fun `empty input produces General with empty prompt`() {
            val result = NanoIntentParser.parse("")
            assertTrue(result is NanoIntent.General)
            assertEquals("", (result as NanoIntent.General).prompt)
        }

        @Test
        @DisplayName("whitespace-only input produces General with empty prompt")
        fun `whitespace-only input produces General with empty prompt`() {
            val result = NanoIntentParser.parse("   ")
            assertTrue(result is NanoIntent.General)
            assertEquals("", (result as NanoIntent.General).prompt)
        }
    }

    @Nested
    @DisplayName("Word boundary enforcement")
    inner class WordBoundaryEnforcement {
        @Test
        @DisplayName("summary does not match summarize trigger")
        fun `summary does not match summarize trigger`() {
            val result = NanoIntentParser.parse("what is a summary")
            assertTrue(result is NanoIntent.General)
        }

        @Test
        @DisplayName("professional as topic does not trigger Rewrite")
        fun `professional as topic does not trigger Rewrite`() {
            val result = NanoIntentParser.parse("what does professional mean")
            assertTrue(result is NanoIntent.General)
        }

        @Test
        @DisplayName("write a description of dogs does not trigger Describe")
        fun `write a description of dogs does not trigger Describe`() {
            val result = NanoIntentParser.parse("write a description of dogs")
            assertTrue(result is NanoIntent.General)
        }

        @Test
        @DisplayName("reproof does not match proofread trigger")
        fun `reproof does not match proofread trigger`() {
            val result = NanoIntentParser.parse("reproof the document")
            assertTrue(result is NanoIntent.General)
        }

        @Test
        @DisplayName("rewritten does not match rewrite trigger")
        fun `rewritten does not match rewrite trigger`() {
            val result = NanoIntentParser.parse("the rewritten version was better")
            assertTrue(result is NanoIntent.General)
        }

        @Test
        @DisplayName("described does not match describe trigger")
        fun `described does not match describe trigger`() {
            val result = NanoIntentParser.parse("he described the scene")
            assertTrue(result is NanoIntent.General)
        }

        @Test
        @DisplayName("summarized does not match summarize trigger")
        fun `summarized does not match summarize trigger`() {
            val result = NanoIntentParser.parse("she summarized the report")
            assertTrue(result is NanoIntent.General)
        }

        @Test
        @DisplayName("overviewing does not match overview trigger")
        fun `overviewing does not match overview trigger`() {
            val result = NanoIntentParser.parse("overviewing the project")
            assertTrue(result is NanoIntent.General)
        }
    }

    @Nested
    @DisplayName("Text extraction")
    inner class TextExtraction {
        @Test
        @DisplayName("preserves original casing in extracted text")
        fun `preserves original casing in extracted text`() {
            val result = NanoIntentParser.parse("summarize Hello World")
            assertTrue(result is NanoIntent.Summarize)
            assertEquals(
                "Hello World",
                (result as NanoIntent.Summarize).text,
            )
        }

        @Test
        @DisplayName("trims whitespace around extracted text")
        fun `trims whitespace around extracted text`() {
            val result = NanoIntentParser.parse("  proofread   some text here  ")
            assertTrue(result is NanoIntent.Proofread)
            assertEquals(
                "some text here",
                (result as NanoIntent.Proofread).text,
            )
        }

        @Test
        @DisplayName("rewrite extracts text after trigger")
        fun `rewrite extracts text after trigger`() {
            val result =
                NanoIntentParser.parse("rewrite the opening paragraph")
            assertTrue(result is NanoIntent.Rewrite)
            assertEquals(
                "the opening paragraph",
                (result as NanoIntent.Rewrite).text,
            )
        }
    }
}
