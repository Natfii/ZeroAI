/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("MagicNumber")

package com.zeroclaw.android.ui.screen.plugins

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.service.SkillsBridge
import com.zeroclaw.android.util.ErrorSanitizer
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Result of a save or fetch operation.
 */
sealed interface SkillBuilderResult {
    /** Operation completed successfully. */
    data object Success : SkillBuilderResult

    /**
     * Operation failed.
     *
     * @property message Human-readable error message.
     */
    data class Error(
        val message: String,
    ) : SkillBuilderResult
}

/** Maximum download size for ClawHub skill packages (5 MB). */
private const val MAX_DOWNLOAD_BYTES = 5L * 1024 * 1024

/** Expected host for ClawHub URLs. */
private const val CLAWHUB_HOST = "clawhub.ai"

/** ClawHub download API base URL. */
private const val CLAWHUB_DOWNLOAD_API =
    "https://wry-manatee-359.convex.site/api/v1/download"

/** OkHttp connection timeout in seconds. */
private const val CONNECT_TIMEOUT_SECONDS = 15L

/** OkHttp read timeout in seconds. */
private const val READ_TIMEOUT_SECONDS = 30L

/**
 * ViewModel for the skill builder screen.
 *
 * Manages state for creating, editing, and fetching community skills.
 *
 * @param application Application context for accessing [SkillsBridge].
 * @param savedStateHandle Saved state handle containing the optional
 *     `skillName` argument from [SkillBuilderRoute].
 */
class SkillBuilderViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val skillsBridge: SkillsBridge =
        (application as ZeroAIApplication).skillsBridge

    private val httpClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private val _name = MutableStateFlow("")

    /** Skill name field. */
    val name: StateFlow<String> = _name.asStateFlow()

    private val _url = MutableStateFlow("")

    /** ClawHub URL field. */
    val url: StateFlow<String> = _url.asStateFlow()

    private val _content = MutableStateFlow("")

    /** Skill markdown content field. */
    val content: StateFlow<String> = _content.asStateFlow()

    private val _isFetching = MutableStateFlow(false)

    /** Whether a fetch operation is in progress. */
    val isFetching: StateFlow<Boolean> = _isFetching.asStateFlow()

    private val _result = MutableStateFlow<SkillBuilderResult?>(null)

    /** Result of the last save or fetch operation. */
    val result: StateFlow<SkillBuilderResult?> = _result.asStateFlow()

    /** Whether this is a new skill (true) or editing an existing one. */
    val isNewSkill: Boolean
        get() = savedStateHandle.get<String>("skillName") == null

    init {
        val existingName = savedStateHandle.get<String>("skillName")
        if (existingName != null) {
            loadExisting(existingName)
        }
    }

    /** Updates the skill name field. */
    fun updateName(value: String) {
        _name.value = value
    }

    /** Updates the ClawHub URL field. */
    fun updateUrl(value: String) {
        _url.value = value
    }

    /** Updates the skill content field. */
    fun updateContent(value: String) {
        _content.value = value
    }

    /** Clears the last operation result. */
    fun clearResult() {
        _result.value = null
    }

    /**
     * Fetches a skill from the ClawHub URL currently in the URL field.
     *
     * Downloads the zip package, extracts SKILL.md, parses the
     * frontmatter name, and populates the name and content fields.
     */
    @Suppress("TooGenericExceptionCaught")
    fun fetchSkill() {
        viewModelScope.launch {
            _isFetching.value = true
            try {
                fetchFromClawHub(_url.value)
            } catch (e: Exception) {
                _result.value =
                    SkillBuilderResult.Error(
                        ErrorSanitizer.sanitizeForUi(e),
                    )
            } finally {
                _isFetching.value = false
            }
        }
    }

    /**
     * Saves the current skill content to the workspace.
     *
     * Calls [SkillsBridge.saveCommunitySkill] with the current name
     * and content values.
     */
    @Suppress("TooGenericExceptionCaught")
    fun saveSkill() {
        viewModelScope.launch {
            try {
                skillsBridge.saveCommunitySkill(
                    _name.value.trim(),
                    _content.value,
                )
                _result.value = SkillBuilderResult.Success
            } catch (e: Exception) {
                _result.value =
                    SkillBuilderResult.Error(
                        ErrorSanitizer.sanitizeForUi(e),
                    )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadExisting(skillName: String) {
        _name.value = skillName
        viewModelScope.launch {
            try {
                val rawContent = skillsBridge.getSkillContent(skillName)
                _content.value = rawContent
            } catch (e: Exception) {
                _result.value =
                    SkillBuilderResult.Error(
                        ErrorSanitizer.sanitizeForUi(e),
                    )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod", "ReturnCount")
    private suspend fun fetchFromClawHub(urlStr: String) {
        val uri = Uri.parse(urlStr)
        if (uri.host != CLAWHUB_HOST) {
            _result.value =
                SkillBuilderResult.Error(
                    "URL must be a clawhub.ai link",
                )
            return
        }
        val segments = uri.pathSegments
        if (segments.size < 2) {
            _result.value =
                SkillBuilderResult.Error(
                    "Invalid ClawHub URL \u2014 expected " +
                        "clawhub.ai/{owner}/{slug}",
                )
            return
        }
        val slug = segments.last()

        val downloadUrl = "$CLAWHUB_DOWNLOAD_API?slug=$slug"
        val request = Request.Builder().url(downloadUrl).build()

        val response =
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

        response.use { resp ->
            if (!resp.isSuccessful) {
                _result.value =
                    SkillBuilderResult.Error(
                        "Download failed: HTTP ${resp.code}",
                    )
                return
            }

            val body =
                resp.body ?: run {
                    _result.value =
                        SkillBuilderResult.Error(
                            "Empty response from ClawHub",
                        )
                    return
                }

            val contentLength = body.contentLength()
            if (contentLength == -1L || contentLength > MAX_DOWNLOAD_BYTES) {
                _result.value =
                    SkillBuilderResult.Error(
                        if (contentLength == -1L) {
                            "Server did not report download size"
                        } else {
                            "Skill package too large (>5 MB)"
                        },
                    )
                return
            }

            val bytes = withContext(Dispatchers.IO) { body.bytes() }
            val skillContent =
                extractSkillMdFromZip(bytes) ?: run {
                    _result.value =
                        SkillBuilderResult.Error(
                            "No SKILL.md found in downloaded package",
                        )
                    return
                }

            val parsedName =
                extractFrontmatterName(skillContent) ?: slug
            _name.value = parsedName
            _content.value = skillContent
        }
    }

    /** Utility functions for zip extraction and frontmatter parsing. */
    companion object {
        private const val FRONTMATTER_DELIMITER_LENGTH = 3

        /**
         * Extracts SKILL.md content from a zip byte array.
         *
         * @param zipBytes Raw zip file bytes.
         * @return SKILL.md content or null if not found.
         */
        internal fun extractSkillMdFromZip(
            zipBytes: ByteArray,
        ): String? {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "SKILL.md" ||
                        name.endsWith("/SKILL.md")
                    ) {
                        return zis.bufferedReader().readText()
                    }
                    entry = zis.nextEntry
                }
            }
            return null
        }

        /**
         * Extracts the `name` field from YAML frontmatter.
         *
         * @param content Full SKILL.md content with frontmatter.
         * @return Name value or null if not found.
         */
        internal fun extractFrontmatterName(
            content: String,
        ): String? {
            val trimmed = content.trimStart()
            if (!trimmed.startsWith("---")) return null
            val afterFirst =
                trimmed.substring(FRONTMATTER_DELIMITER_LENGTH).trimStart('\r', '\n')
            val closing = afterFirst.indexOf("\n---")
            if (closing < 0) return null
            val frontmatter = afterFirst.substring(0, closing)

            for (line in frontmatter.lines()) {
                val trimLine = line.trim()
                if (trimLine.startsWith("name:")) {
                    val raw =
                        trimLine.substringAfter("name:").trim()
                    return if (raw.startsWith("\"") &&
                        raw.endsWith("\"") &&
                        raw.length >= 2
                    ) {
                        raw.substring(1, raw.length - 1)
                    } else {
                        raw
                    }
                }
            }
            return null
        }
    }
}
