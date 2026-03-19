/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.data.local.dao.SkillExecutionDao
import com.zeroclaw.android.data.local.entity.SkillExecutionEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Tracks skill tool executions by listening to FFI event JSON.
 *
 * Inserts `"running"` rows on `tool_call_start` events that carry
 * a `skill_name`, and updates them on `tool_call` completion.
 *
 * @param dao Room DAO for execution history persistence
 * @param scope coroutine scope for database operations
 */
class SkillExecutionTracker(
    private val dao: SkillExecutionDao,
    private val scope: CoroutineScope,
) {
    /** Maps composite keys (`skillName::toolName`) to their running execution row IDs. */
    private val runningExecutions = ConcurrentHashMap<String, Long>()

    /**
     * Processes a raw event JSON string from the FFI event listener.
     *
     * Only acts on `tool_call_start` and `tool_call` events that
     * include a `skill_name` field.
     *
     * @param eventJson raw JSON from the FFI event listener
     */
    fun onEvent(eventJson: String) {
        val json = runCatching { JSONObject(eventJson) }.getOrNull() ?: return
        val kind = json.optString("kind", "")
        val data = json.optJSONObject("data") ?: return
        val skillName = data.optString("skill_name", "").ifEmpty { return }
        val toolName = data.optString("tool", "")

        when (kind) {
            "tool_call_start" -> onToolCallStart(skillName, toolName)
            "tool_call" -> onToolCallComplete(skillName, toolName, data)
        }
    }

    private fun onToolCallStart(
        skillName: String,
        toolName: String,
    ) {
        val key = "$skillName::$toolName"
        scope.launch(Dispatchers.IO) {
            val id =
                dao.insert(
                    SkillExecutionEntity(
                        skillName = skillName,
                        toolName = toolName,
                        status = "running",
                        startedAt = System.currentTimeMillis(),
                    ),
                )
            runningExecutions[key] = id
        }
    }

    private fun onToolCallComplete(
        skillName: String,
        toolName: String,
        data: JSONObject,
    ) {
        val key = "$skillName::$toolName"
        val rowId = runningExecutions.remove(key)
        val success = data.optBoolean("success", false)
        val durationMs = data.optLong("duration_ms", 0L)
        val now = System.currentTimeMillis()
        val status = if (success) "success" else "failed"
        val errorMessage = if (success) null else "Tool call failed"

        scope.launch(Dispatchers.IO) {
            if (rowId != null) {
                dao.updateCompletion(
                    id = rowId,
                    status = status,
                    outputSummary = null,
                    errorMessage = errorMessage,
                    completedAt = now,
                    durationMs = durationMs,
                )
            } else {
                dao.insert(
                    SkillExecutionEntity(
                        skillName = skillName,
                        toolName = toolName,
                        status = status,
                        startedAt = now - durationMs,
                        completedAt = now,
                        durationMs = durationMs,
                    ),
                )
            }
        }
    }

    /**
     * Prunes old execution records for all skills.
     *
     * @param skills list of installed skill names
     * @param retainCount number of records to keep per skill
     */
    fun pruneOnStartup(
        skills: List<String>,
        retainCount: Int = 500,
    ) {
        scope.launch(Dispatchers.IO) {
            for (skill in skills) {
                dao.pruneOldest(skill, retainCount)
            }
        }
    }
}
