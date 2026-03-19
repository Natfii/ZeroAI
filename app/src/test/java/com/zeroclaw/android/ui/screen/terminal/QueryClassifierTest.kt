/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [QueryClassifier].
 *
 * Validates the heuristic scoring system that decides between on-device
 * Gemini Nano and cloud provider routing. Tests cover each scoring
 * signal in isolation and in combination, plus edge cases.
 *
 * Scoring reference (from the spec):
 * - Short query (< 50 tokens): nano +3
 * - Medium query (50-150 tokens): nano +1, cloud +1
 * - Long query (> 150 tokens): cloud +3
 * - Simple question pattern: nano +2
 * - Complex task pattern: cloud +2
 * - Image attachment: cloud +3
 * - Multi-step indicators: cloud +2
 * - Code keywords: cloud +2
 * - Ties go to Cloud.
 * - Token estimate: ~3.5 chars/token, so 50 tokens ~ 175 chars.
 */
@DisplayName("QueryClassifier")
class QueryClassifierTest {
    /**
     * Generates a padding string to push a query into the medium token
     * bucket (50-150 tokens, approximately 175-525 characters).
     *
     * @param prefix The meaningful query prefix.
     * @return A padded version of the query that falls in the medium
     *   token range.
     */
    private fun mediumLength(prefix: String): String {
        val target = 200
        if (prefix.length >= target) return prefix
        val padding = " " + "context ".repeat((target - prefix.length) / 8)
        return prefix + padding.take(target - prefix.length)
    }

    @Nested
    @DisplayName("Token length signal")
    inner class TokenLengthSignal {
        @Test
        @DisplayName("short query routes to Local")
        fun `short query routes to Local`() {
            val result = QueryClassifier.classify("hello there")
            assertTrue(result is RoutingDecision.Local) {
                "Short query should route locally, got: $result"
            }
        }

        @Test
        @DisplayName("long query routes to Cloud")
        fun `long query routes to Cloud`() {
            val longQuery = "a ".repeat(300)
            val result = QueryClassifier.classify(longQuery)
            assertTrue(result is RoutingDecision.Cloud) {
                "Long query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("medium query alone ties and routes to Cloud")
        fun `medium query alone ties and routes to Cloud`() {
            val mediumQuery = "word ".repeat(40)
            val result = QueryClassifier.classify(mediumQuery)
            assertTrue(result is RoutingDecision.Cloud) {
                "Medium query with no other signals ties (1:1), got: $result"
            }
        }
    }

    @Nested
    @DisplayName("Simple question signal")
    inner class SimpleQuestionSignal {
        @ParameterizedTest(name = "\"{0}\" routes to Local")
        @ValueSource(
            strings = [
                "what is a monad",
                "define recursion",
                "translate hello to French",
                "how do you spell receive",
                "meaning of serendipity",
                "who is Ada Lovelace",
                "when was the moon landing",
                "where is Tokyo",
            ],
        )
        @DisplayName("simple question patterns route to Local")
        fun `simple question patterns route to Local`(query: String) {
            val result = QueryClassifier.classify(query)
            assertTrue(result is RoutingDecision.Local) {
                "Simple question '$query' should route locally, got: $result"
            }
        }
    }

    @Nested
    @DisplayName("Complex task signal")
    inner class ComplexTaskSignal {
        @Test
        @DisplayName("explain why routes to Cloud with medium length")
        fun `explain why routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("explain why the sky is blue and what causes"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("compare routes to Cloud with medium length")
        fun `compare routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("compare React and Vue frameworks in detail"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("analyze routes to Cloud with medium length")
        fun `analyze routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("analyze the time complexity of quicksort"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("write a routes to Cloud with medium length")
        fun `write a routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("write a REST API in Python with full docs"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("create a routes to Cloud with medium length")
        fun `create a routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("create a landing page design with sections"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("build a routes to Cloud with medium length")
        fun `build a routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("build a CI pipeline for this project now"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("design a routes to Cloud with medium length")
        fun `design a routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("design a schema for an e-commerce app here"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("implement routes to Cloud with medium length")
        fun `implement routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("implement a binary search tree from scratch"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("how would you routes to Cloud with medium length")
        fun `how would you routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("how would you restructure this architecture"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("pros and cons routes to Cloud with medium length")
        fun `pros and cons routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength(
                        "what are the pros and cons of microservices",
                    ),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex task should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName(
            "short complex task with code keyword stacks to Cloud",
        )
        fun `short complex task with code keyword stacks to Cloud`() {
            val result =
                QueryClassifier.classify(
                    "write a function that sorts an array",
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Stacked complex+code signals should beat short bonus, got: $result"
            }
        }
    }

    @Nested
    @DisplayName("Code keyword signal")
    inner class CodeKeywordSignal {
        @Test
        @DisplayName("debug function routes to Cloud with medium length")
        fun `debug function routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("debug this function that fails on edge case"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Code query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("refactor class routes to Cloud with medium length")
        fun `refactor class routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("refactor the UserRepository class to use DI"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Code query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName(
            "runtime exception routes to Cloud with medium length",
        )
        fun `runtime exception routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("fix this runtime exception in production now"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Code query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("api endpoint routes to Cloud with medium length")
        fun `api endpoint routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("create an api endpoint for user registration"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Code query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("database migration routes to Cloud with medium")
        fun `database migration routes to Cloud with medium`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("write a database migration for the new table"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Code query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("compile routes to Cloud with medium length")
        fun `compile routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("compile this code and fix the warnings inside"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Code query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("short query with stacked code signals routes Cloud")
        fun `short query with stacked code signals routes Cloud`() {
            val result =
                QueryClassifier.classify(
                    "write a function that queries the database",
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Complex+code on short query should beat short bonus, got: $result"
            }
        }
    }

    @Nested
    @DisplayName("Multi-step signal")
    inner class MultiStepSignal {
        @Test
        @DisplayName("step by step routes to Cloud with medium length")
        fun `step by step routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("explain step by step how to bake sourdough"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Multi-step query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("first then routes to Cloud with medium length")
        fun `first then routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("first open the file then edit the config here"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Multi-step query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("walk me through routes to Cloud with medium length")
        fun `walk me through routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("walk me through setting up Docker and compose"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Multi-step query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("break down routes to Cloud with medium length")
        fun `break down routes to Cloud with medium length`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("break down this algorithm into smaller parts"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Multi-step query should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("numbered list pattern routes to Cloud")
        fun `numbered list pattern routes to Cloud`() {
            val result =
                QueryClassifier.classify(
                    mediumLength("1. open the app 2. tap settings to configure"),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Numbered list should route to cloud, got: $result"
            }
        }
    }

    @Nested
    @DisplayName("Image attachment signal")
    inner class ImageAttachmentSignal {
        @Test
        @DisplayName("any query with image attachment routes to Cloud")
        fun `any query with image attachment routes to Cloud`() {
            val result =
                QueryClassifier.classify(
                    query = "tell me about this picture please",
                    hasImageAttachment = true,
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Query with image attachment should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("medium query with image routes to Cloud")
        fun `medium query with image routes to Cloud`() {
            val result =
                QueryClassifier.classify(
                    query = mediumLength("describe the contents of this photo"),
                    hasImageAttachment = true,
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Medium query with image should route to cloud, got: $result"
            }
        }
    }

    @Nested
    @DisplayName("Tie-breaking")
    inner class TieBreaking {
        @Test
        @DisplayName("equal scores route to Cloud")
        fun `equal scores route to Cloud`() {
            val result =
                QueryClassifier.classify(
                    mediumLength(
                        "what is the analyze approach for this topic",
                    ),
                )
            assertTrue(result is RoutingDecision.Cloud) {
                "Tied scores should route to cloud, got: $result"
            }
        }
    }

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCases {
        @Test
        @DisplayName("empty input routes to Cloud")
        fun `empty input routes to Cloud`() {
            val result = QueryClassifier.classify("")
            assertTrue(result is RoutingDecision.Cloud) {
                "Empty input should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("single word routes to Local")
        fun `single word routes to Local`() {
            val result = QueryClassifier.classify("hello")
            assertTrue(result is RoutingDecision.Local) {
                "Single word should route locally, got: $result"
            }
        }

        @Test
        @DisplayName("whitespace-only input routes to Cloud")
        fun `whitespace-only input routes to Cloud`() {
            val result = QueryClassifier.classify("   ")
            assertTrue(result is RoutingDecision.Cloud) {
                "Whitespace-only input should route to cloud, got: $result"
            }
        }

        @Test
        @DisplayName("case insensitivity is respected")
        fun `case insensitivity is respected`() {
            val result = QueryClassifier.classify("WHAT IS a monad")
            assertTrue(result is RoutingDecision.Local) {
                "Case-insensitive simple question should route locally, got: $result"
            }
        }
    }

    @Nested
    @DisplayName("Reason string")
    inner class ReasonString {
        @Test
        @DisplayName("reason includes signal scores")
        fun `reason includes signal scores`() {
            val result = QueryClassifier.classify("what is a cat")
            assertTrue(result is RoutingDecision.Local)
            val reason = (result as RoutingDecision.Local).reason
            assertTrue(reason.startsWith("nano=")) {
                "Reason should start with nano score, got: $reason"
            }
            assertTrue(reason.contains("cloud=")) {
                "Reason should contain cloud score, got: $reason"
            }
        }

        @Test
        @DisplayName("reason includes signal descriptions")
        fun `reason includes signal descriptions`() {
            val result = QueryClassifier.classify("what is a dog")
            assertTrue(result is RoutingDecision.Local)
            val reason = (result as RoutingDecision.Local).reason
            assertTrue(reason.contains("short query")) {
                "Reason should mention 'short query', got: $reason"
            }
            assertTrue(reason.contains("simple question pattern")) {
                "Reason should mention 'simple question pattern', got: $reason"
            }
        }
    }
}
