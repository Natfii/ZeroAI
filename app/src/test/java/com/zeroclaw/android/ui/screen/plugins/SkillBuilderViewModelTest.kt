/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SkillBuilderViewModel] companion object helpers.
 */
class SkillBuilderViewModelTest {
    @Test
    fun `extractFrontmatterName returns name from valid frontmatter`() {
        val content = "---\nname: my-skill\ndescription: \"Test\"\n---\n\n# Body\n"
        val name = SkillBuilderViewModel.extractFrontmatterName(content)
        assertEquals("my-skill", name)
    }

    @Test
    fun `extractFrontmatterName returns quoted name`() {
        val content = "---\nname: \"quoted-name\"\n---\n\n# Body\n"
        val name = SkillBuilderViewModel.extractFrontmatterName(content)
        assertEquals("quoted-name", name)
    }

    @Test
    fun `extractFrontmatterName returns null when no frontmatter`() {
        val content = "# Just Markdown\n\nNo frontmatter here.\n"
        val name = SkillBuilderViewModel.extractFrontmatterName(content)
        assertNull(name)
    }

    @Test
    fun `extractFrontmatterName returns null when no name field`() {
        val content = "---\ndescription: \"No name\"\n---\n\n# Body\n"
        val name = SkillBuilderViewModel.extractFrontmatterName(content)
        assertNull(name)
    }

    @Test
    fun `extractSkillMdFromZip returns null for empty zip`() {
        // Create a minimal valid empty zip (22 bytes = end of central directory)
        val emptyZip =
            byteArrayOf(
                0x50,
                0x4B,
                0x05,
                0x06,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        val result = SkillBuilderViewModel.extractSkillMdFromZip(emptyZip)
        assertNull(result)
    }
}
