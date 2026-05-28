/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the [OnboardingRepository] contract.
 *
 * Uses an in-memory implementation to verify completion state
 * without requiring Android DataStore.
 */
@DisplayName("OnboardingRepository")
class OnboardingRepositoryTest {
    @Test
    @DisplayName("initial state is not completed")
    fun `initial state is not completed`() =
        runTest {
            val repo = InMemoryOnboardingRepository()
            assertEquals(false, repo.isCompleted.first())
        }

    @Test
    @DisplayName("markComplete sets completed to true")
    fun `markComplete sets completed to true`() =
        runTest {
            val repo = InMemoryOnboardingRepository()
            repo.markComplete()
            assertEquals(true, repo.isCompleted.first())
        }

    @Test
    @DisplayName("reset sets completed back to false")
    fun `reset sets completed back to false`() =
        runTest {
            val repo = InMemoryOnboardingRepository()
            repo.markComplete()
            assertEquals(true, repo.isCompleted.first())
            repo.reset()
            assertEquals(false, repo.isCompleted.first())
        }

    @Test
    @DisplayName("multiple markComplete calls are idempotent")
    fun `multiple markComplete calls are idempotent`() =
        runTest {
            val repo = InMemoryOnboardingRepository()
            repo.markComplete()
            repo.markComplete()
            assertEquals(true, repo.isCompleted.first())
        }

    @Test
    @DisplayName("saveDraft round-trips a draft with nested sections")
    fun `saveDraft round-trips a non-default draft`() =
        runTest {
            val repo = InMemoryOnboardingRepository()
            val draft =
                OnboardingDraft(
                    provider =
                        ProviderSection(
                            providerId = "anthropic",
                            slotId = "anthropic-api",
                            model = "claude-sonnet-4",
                        ),
                    identity = IdentitySection(agentName = "Zero"),
                )
            repo.saveDraft(draft)
            assertEquals(draft, repo.savedDraft.first())
        }

    @Test
    @DisplayName("savedDraft is null until the first saveDraft call")
    fun `savedDraft is null until first save`() =
        runTest {
            val repo = InMemoryOnboardingRepository()
            assertEquals(null, repo.savedDraft.first())
        }

    @Test
    @DisplayName("reset clears the saved draft back to null")
    fun `reset clears the saved draft`() =
        runTest {
            val repo = InMemoryOnboardingRepository()
            repo.saveDraft(OnboardingDraft(provider = ProviderSection(providerId = "openai")))
            repo.reset()
            assertEquals(null, repo.savedDraft.first())
        }

    @Test
    @DisplayName("decodeDraft returns null for absent or malformed input")
    fun `decodeDraft handles absent and malformed input`() {
        assertEquals(null, decodeDraft(null))
        assertEquals(null, decodeDraft("{not-json"))
        assertEquals(null, decodeDraft(""))
    }

    @Test
    @DisplayName("decodeDraft round-trips a draft with nested sections")
    fun `decodeDraft round-trips a draft`() {
        val original =
            OnboardingDraft(
                provider = ProviderSection(providerId = "anthropic", model = "claude-sonnet-4"),
                identity = IdentitySection(agentName = "Zero", timezone = "UTC"),
            )
        val codec =
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        val encoded = codec.encodeToString(OnboardingDraft.serializer(), original)
        assertEquals(original, decodeDraft(encoded))
    }

    @Test
    @DisplayName("decodeDraft tolerates unknown fields (forward-compat)")
    fun `decodeDraft tolerates unknown fields`() {
        val payload =
            """{"provider":{"providerId":"openai","futureNewField":42}}"""
        val draft = decodeDraft(payload)
        assertEquals("openai", draft?.provider?.providerId)
    }
}

/**
 * In-memory [OnboardingRepository] for testing.
 *
 * Stores state in a [MutableStateFlow] without requiring
 * Android context or DataStore infrastructure.
 */
private class InMemoryOnboardingRepository : OnboardingRepository {
    private val _isCompleted = MutableStateFlow(false)
    override val isCompleted = _isCompleted

    private val _savedStep = MutableStateFlow(0)
    override val savedStep = _savedStep

    private val _savedDraft = MutableStateFlow<OnboardingDraft?>(null)
    override val savedDraft = _savedDraft

    override suspend fun markComplete() {
        _isCompleted.value = true
    }

    override suspend fun saveStep(step: Int) {
        _savedStep.value = step
    }

    override suspend fun saveDraft(draft: OnboardingDraft) {
        _savedDraft.value = draft
    }

    override suspend fun reset() {
        _isCompleted.value = false
        _savedStep.value = 0
        _savedDraft.value = null
    }
}
