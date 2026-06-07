/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LiteRtModelCatalog")
class LiteRtModelCatalogTest {
    @Test
    fun `all lists E2B then E4B in lightest-first order`() {
        assertEquals(
            listOf("gemma-4-e2b-it", "gemma-4-e4b-it"),
            LiteRtModelCatalog.all.map { it.id },
        )
    }

    @Test
    fun `catalog is ordered lightest-to-heaviest by working memory`() {
        val mems = LiteRtModelCatalog.all.map { it.workingMemoryBytes }
        assertEquals(mems.sorted(), mems, "Picker shows lightest first; order must match")
    }

    @Test
    fun `findById resolves every catalog id back to its model`() {
        LiteRtModelCatalog.all.forEach { model ->
            assertEquals(model, LiteRtModelCatalog.findById(model.id))
        }
    }

    @Test
    fun `findById returns null for unknown and blank ids`() {
        assertNull(LiteRtModelCatalog.findById("does-not-exist"))
        assertNull(LiteRtModelCatalog.findById(""))
    }

    @Test
    fun `catalog ids are unique`() {
        val ids = LiteRtModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Catalog ids must be unique")
    }

    @Test
    fun `E2B is the comfortable default with the verified file size`() {
        val e2b = LiteRtModelCatalog.Gemma4E2B
        assertEquals("gemma-4-e2b-it", e2b.id)
        assertEquals(LiteRtRisk.Comfortable, e2b.risk)
        assertEquals(2_588_147_712L, e2b.fileBytes)
        assertEquals(32_000, e2b.contextTokens)
    }

    @Test
    fun `E4B is heavy with a capped context and the verified file size`() {
        val e4b = LiteRtModelCatalog.Gemma4E4B
        assertEquals("gemma-4-e4b-it", e4b.id)
        assertEquals(LiteRtRisk.Heavy, e4b.risk)
        assertEquals(3_659_530_240L, e4b.fileBytes)
        assertEquals(24_000, e4b.contextTokens)
        assertTrue(e4b.contextTokens < 32_000, "E4B context must be capped below native 32K")
    }

    @Test
    fun `every download url targets a litert-community litertlm artifact`() {
        LiteRtModelCatalog.all.forEach { model ->
            assertTrue(
                model.downloadUrl.startsWith("https://huggingface.co/litert-community/"),
                "Unexpected host for ${model.id}: ${model.downloadUrl}",
            )
            assertTrue(
                model.downloadUrl.endsWith(".litertlm"),
                "Expected a .litertlm artifact for ${model.id}",
            )
        }
    }
}
