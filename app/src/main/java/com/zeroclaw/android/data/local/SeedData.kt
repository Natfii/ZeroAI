/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local

import com.zeroclaw.android.data.local.entity.PluginEntity
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.model.PluginCategory

/**
 * Provides seed data for first-install database population.
 *
 * These functions return the same sample data previously defined in the
 * in-memory repositories, ensuring a seamless migration experience.
 */
object SeedData {
    /**
     * Returns all seed plugin entities (currently just the official
     * built-ins; community/vaporware channels removed).
     *
     * @return List of pre-configured [PluginEntity] instances.
     */
    @Suppress("LongMethod")
    fun seedPlugins(): List<PluginEntity> = officialPluginEntities()

    @Suppress("LongMethod")
    private fun officialPluginEntities(): List<PluginEntity> =
        listOf(
            PluginEntity(
                id = OfficialPlugins.WEB_SEARCH,
                name = "Web Search",
                description = "Search the web via Brave or Google.",
                version = "1.0.0",
                author = "ZeroAI",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.WEB_FETCH,
                name = "Web Fetch",
                description = "Fetch and read web page content.",
                version = "1.0.0",
                author = "ZeroAI",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.HTTP_REQUEST,
                name = "HTTP Request",
                description = "Make HTTP calls to external APIs.",
                version = "1.0.0",
                author = "ZeroAI",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.SHARED_FOLDER,
                name = "Shared Folder",
                description = "Read and write files to a shared folder on your device.",
                version = "1.0.0",
                author = "ZeroAI",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.COMPOSIO,
                name = "Composio",
                description = "Third-party tool integrations via Composio.",
                version = "1.0.0",
                author = "ZeroAI",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.VISION,
                name = "Vision",
                description = "Process images for multimodal queries.",
                version = "1.0.0",
                author = "ZeroAI",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = true,
                configJson = "{}",
            ),
        )
}
