/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.local.dao.AgentDao
import com.zeroclaw.android.data.local.entity.AgentEntity
import com.zeroclaw.android.model.Agent
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Focused tests for slot-aware persistence behavior in [RoomAgentRepository].
 */
@DisplayName("RoomAgentRepository provider slots")
class RoomAgentRepositoryProviderSlotTest {
    @Test
    @DisplayName("ensureProviderSlots inserts all fixed slots as disabled seed rows")
    fun `ensureProviderSlots inserts all fixed slots as disabled seed rows`() =
        runTest {
            val dao = mockk<AgentDao>(relaxUnitFun = true)
            every { dao.observeAll() } returns flowOf(emptyList())
            val repository = RoomAgentRepository(dao)

            repository.ensureProviderSlots()

            coVerify {
                dao.insertIgnore(
                    match { entities ->
                        entities.size == ProviderSlotRegistry.all().size &&
                            entities.all { entity ->
                                entity.id == entity.slotId &&
                                    entity.name.isNotBlank() &&
                                    entity.provider.isNotBlank() &&
                                    !entity.isEnabled
                            }
                    },
                )
            }
        }

    @Test
    @DisplayName("save backfills slotId when a fixed slot row is addressed by id only")
    fun `save backfills slotId when a fixed slot row is addressed by id only`() =
        runTest {
            val dao = mockk<AgentDao>(relaxUnitFun = true)
            every { dao.observeAll() } returns flowOf(emptyList())
            val repository = RoomAgentRepository(dao)
            val slot =
                ProviderSlotRegistry.findById("gemini-api")
                    ?: error("Expected gemini-api slot definition")

            repository.save(
                Agent(
                    id = slot.slotId,
                    name = slot.displayName,
                    provider = slot.providerRegistryId,
                    modelName = "",
                    isEnabled = false,
                ),
            )

            coVerify {
                dao.upsert(
                    match { entity: AgentEntity ->
                        entity.id == slot.slotId &&
                            entity.slotId == slot.slotId &&
                            entity.provider == slot.providerRegistryId
                    },
                )
            }
        }
}
