/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.data.ProviderSlot
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.local.dao.AgentDao
import com.zeroclaw.android.data.local.entity.toEntity
import com.zeroclaw.android.data.local.entity.toModel
import com.zeroclaw.android.model.Agent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [AgentRepository] implementation.
 *
 * Delegates all persistence operations to [AgentDao] and maps between
 * entity and domain model layers.
 *
 * @param dao The data access object for agent operations.
 */
class RoomAgentRepository(
    private val dao: AgentDao,
) : AgentRepository {
    override val agents: Flow<List<Agent>> =
        dao.observeAll().map { entities -> entities.map { it.toModel() } }

    override suspend fun getById(id: String): Agent? = dao.getById(id)?.toModel()

    override suspend fun save(agent: Agent) {
        dao.upsert(normalizeSlotAgent(agent).toEntity())
    }

    override suspend fun ensureProviderSlots() {
        dao.insertIgnore(ProviderSlotRegistry.all().map { slot -> slot.seedAgent().toEntity() })
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    override suspend fun toggleEnabled(id: String) {
        dao.toggleEnabled(id)
    }

    /**
     * Preserves stable slot IDs when existing save callers still address slot rows by [Agent.id].
     *
     * Older code paths may know the seeded row ID but not yet propagate [Agent.slotId].
     * This adapter keeps those writes slot-backed until the rest of the stack becomes
     * slot-aware.
     *
     * @param agent Agent model about to be persisted.
     * @return Agent with [Agent.slotId] populated when the row ID matches a known provider slot.
     */
    private fun normalizeSlotAgent(agent: Agent): Agent {
        if (agent.slotId.isNotBlank()) {
            return agent
        }
        val slot = ProviderSlotRegistry.findById(agent.id) ?: return agent
        return agent.copy(slotId = slot.slotId)
    }

    /**
     * Converts a fixed slot definition to its seed [Agent] row.
     *
     * @receiver Slot definition to persist.
     * @return Disabled placeholder agent row for the slot.
     */
    private fun ProviderSlot.seedAgent(): Agent =
        Agent(
            id = slotId,
            name = displayName,
            provider = providerRegistryId,
            modelName = "",
            isEnabled = false,
            slotId = slotId,
        )
}
