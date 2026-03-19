/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.Skill
import com.zeroclaw.ffi.FfiException
import com.zeroclaw.ffi.FfiSkill
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge between the Android UI layer and the Rust skills FFI.
 *
 * Wraps the skills-related UniFFI-generated functions in coroutine-safe
 * suspend functions dispatched to [Dispatchers.IO].
 *
 * @param ioDispatcher Dispatcher for blocking FFI calls. Defaults to [Dispatchers.IO].
 */
class SkillsBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Lists all skills loaded from the workspace's skills directory.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @return List of all [Skill] instances.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun listSkills(): List<Skill> =
        withContext(ioDispatcher) {
            com.zeroclaw.ffi
                .listSkills()
                .map { it.toModel() }
        }

    /**
     * Installs a skill from a URL or local path.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @param source URL or local filesystem path to the skill source.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun installSkill(source: String) {
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.installSkill(source)
        }
    }

    /**
     * Removes an installed skill by name.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @param name Name of the skill to remove.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun removeSkill(name: String) {
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.removeSkill(name)
        }
    }

    /**
     * Saves a community skill's SKILL.md content to the workspace.
     *
     * Safe to call from the main thread; dispatched to [ioDispatcher].
     *
     * @param name Skill directory name.
     * @param content Full SKILL.md content including YAML frontmatter.
     * @throws FfiException.ConfigException if the name is empty, contains path traversal, or
     *   is a Windows reserved filename.
     * @throws FfiException.StateException if the daemon is not running.
     * @throws FfiException.SpawnException if the directory cannot be created or the file write fails.
     * @throws FfiException.InternalPanic if the native layer panics internally.
     */
    @Throws(FfiException::class)
    suspend fun saveCommunitySkill(
        name: String,
        content: String,
    ) {
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.saveCommunitySkill(name, content)
        }
    }

    /**
     * Toggles a community skill between enabled and disabled.
     *
     * Safe to call from the main thread; dispatched to [ioDispatcher].
     *
     * @param name Skill directory name.
     * @param enabled `true` to enable, `false` to disable.
     * @throws FfiException.ConfigException if the name is empty or contains path traversal.
     * @throws FfiException.InvalidArgument if the skill is not found.
     * @throws FfiException.StateException if the daemon is not running.
     * @throws FfiException.SpawnException if the file rename fails.
     * @throws FfiException.InternalPanic if the native layer panics internally.
     */
    @Throws(FfiException::class)
    suspend fun toggleCommunitySkill(
        name: String,
        enabled: Boolean,
    ) {
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.toggleCommunitySkill(name, enabled)
        }
    }

    /**
     * Reads the raw SKILL.md content of a community skill.
     *
     * Safe to call from the main thread; dispatched to [ioDispatcher].
     *
     * @param name Skill directory name.
     * @return Full file content including YAML frontmatter.
     * @throws FfiException.ConfigException if the name is empty or contains path traversal.
     * @throws FfiException.InvalidArgument if the skill is not found.
     * @throws FfiException.StateException if the daemon is not running.
     * @throws FfiException.SpawnException if the file cannot be read.
     * @throws FfiException.InternalPanic if the native layer panics internally.
     */
    @Throws(FfiException::class)
    suspend fun getSkillContent(name: String): String =
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.getSkillContent(name)
        }
}

/**
 * Converts an FFI skill record to the domain model.
 *
 * @receiver FFI-generated [FfiSkill] record from the native layer.
 * @return Domain [Skill] model with identical field values.
 */
private fun FfiSkill.toModel(): Skill =
    Skill(
        name = name,
        description = description,
        version = version,
        author = author,
        tags = tags,
        toolCount = toolCount.toInt(),
        toolNames = toolNames,
        isCommunity = isCommunity,
        isEnabled = isEnabled,
        sourceUrl = sourceUrl,
        emoji = emoji,
        category = category,
        apiBase = apiBase,
    )
