# Shared Folder Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an official "Shared Folder" tool plugin (Hub > Plugins) that gives the ZeroAI agent SAF-backed read/write access to a user-chosen folder on the device.

**Architecture:** Pure SAF approach following Dolphin Emulator patterns. Three Rust shim tools (`shared_folder_list`, `shared_folder_read`, `shared_folder_write`) registered in the tool registry delegate to Kotlin via a UniFFI callback interface. Kotlin performs all SAF operations through `ContentResolver`. The plugin is an official tool with a `[shared_folder]` TOML config section.

**Tech Stack:** Kotlin/Compose (UI + SAF I/O), Rust (tool registry shims + config schema), UniFFI callback interface, Android SAF (`ACTION_OPEN_DOCUMENT_TREE`, `DocumentsContract`, `ContentResolver`)

**Spec:** `docs/superpowers/specs/2026-03-18-shared-folder-tool-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `app/.../model/OfficialPlugins.kt` | Modify | Add `SHARED_FOLDER` constant + `ALL` set entry |
| `app/.../data/local/SeedData.kt` | Modify | Add seed `PluginEntity` |
| `app/.../model/AppSettings.kt` | Modify | Add `sharedFolderEnabled` + `sharedFolderUri` fields |
| `app/.../data/repository/SettingsRepository.kt` | Modify | Add setter interface methods |
| `app/.../data/repository/DataStoreSettingsRepository.kt` | Modify | Add DataStore keys + setter implementations |
| `app/.../ui/screen/settings/SettingsViewModel.kt` | Modify | Add update methods + `updateOfficialPluginEnabled` branch |
| `app/.../ui/screen/plugins/OfficialPluginSettingsSync.kt` | Modify | Add sync + restore branches |
| `app/.../data/repository/RoomPluginRepository.kt` | Modify | Add upsert logic + sync mapping entry |
| `app/.../service/ConfigTomlBuilder.kt` | Modify | Add `GlobalTomlConfig` field + TOML emit method |
| `zeroclaw/src/config/schema.rs` | Modify | Add `SharedFolderConfig` struct + `Config` field |
| `zeroclaw-android/zeroclaw-ffi/src/shared_folder.rs` | Create | Callback interface + `register_shared_folder_handler` FFI export |
| `zeroclaw-android/zeroclaw-ffi/src/lib.rs` | Modify | Declare `shared_folder` module + re-export |
| `app/.../ui/screen/plugins/OfficialPluginConfigSection.kt` | Modify | Add `SharedFolderConfig` composable + `when` branch |
| `app/.../data/saf/SharedFolderSafHelper.kt` | Create | SAF operations: list, read, write, path traversal |
| `app/.../data/saf/SharedFolderCallbackHandler.kt` | Create | UniFFI callback impl, delegates to `SharedFolderSafHelper` |
| `app/.../service/ZeroAIDaemonService.kt` | Modify | Register callback handler on daemon start |

### Test Files

| File | Action |
|------|--------|
| `app/.../model/OfficialPluginsTest.kt` | Modify — update count to 8, add SHARED_FOLDER |
| `app/.../ui/screen/plugins/OfficialPluginSettingsSyncTest.kt` | Modify — add shared folder cases |
| `app/.../data/saf/SharedFolderSafHelperTest.kt` | Deferred — SAF operations require Android context; test on-device via manual QA. Pure utility functions (`splitPath`, `guessMimeType`, `formatTimestamp`) are private and trivial. |

---

### Task 1: Rust Config Schema

**Files:**
- Modify: `zeroclaw/src/config/schema.rs:224` (after `composio` field on Config)
- Modify: `zeroclaw/src/config/schema.rs:907` (near ComposioConfig for reference)

- [ ] **Step 1: Add `SharedFolderConfig` struct to schema.rs**

After the `ComposioConfig` impl block (around line 930), add:

```rust
// Copyright (c) 2026 @Natfii. All rights reserved.

/// Shared folder tool configuration (`[shared_folder]`).
///
/// When enabled, registers three shim tools (`shared_folder_list`,
/// `shared_folder_read`, `shared_folder_write`) that delegate to the
/// Android host via a UniFFI callback interface. Actual file I/O is
/// performed in Kotlin through the Storage Access Framework.
#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
pub struct SharedFolderConfig {
    /// Enable shared folder shim tools in the tool registry.
    #[serde(default, alias = "enable")]
    pub enabled: bool,
}

impl Default for SharedFolderConfig {
    fn default() -> Self {
        Self { enabled: false }
    }
}
```

- [ ] **Step 2: Add `shared_folder` field to the `Config` struct**

After the `composio` field (line 224), add:

```rust
    /// Shared folder tool configuration (`[shared_folder]`).
    #[serde(default)]
    pub shared_folder: SharedFolderConfig,
```

- [ ] **Step 3: Verify it compiles**

Run: `cd zeroclaw && cargo check --lib 2>&1 | head -5`
Expected: no errors related to `SharedFolderConfig`

- [ ] **Step 4: Commit**

```bash
git add zeroclaw/src/config/schema.rs
git commit -m "feat(config): add SharedFolderConfig to Rust schema"
```

---

### Task 2: Kotlin Plugin Registration (OfficialPlugins + SeedData)

**Files:**
- Modify: `app/src/main/java/com/zeroclaw/android/model/OfficialPlugins.kt:23-47`
- Modify: `app/src/main/java/com/zeroclaw/android/data/local/SeedData.kt:56-77`

- [ ] **Step 1: Update OfficialPluginsTest first (TDD)**

Modify `app/src/test/java/com/zeroclaw/android/model/OfficialPluginsTest.kt`:

Line 12-13: Change `7` to `8`:
```kotlin
    fun `ALL contains exactly 8 official plugin IDs`() {
        assertEquals(8, OfficialPlugins.ALL.size)
    }
```

Line 43: Add after `OfficialPlugins.HTTP_REQUEST,`:
```kotlin
                OfficialPlugins.SHARED_FOLDER,
```

Line 56: Change `7` to `8`:
```kotlin
        val syncMappingCount = 8
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.zeroclaw.android.model.OfficialPluginsTest" 2>&1 | tail -20`
Expected: FAIL — `SHARED_FOLDER` not defined

- [ ] **Step 3: Add SHARED_FOLDER to OfficialPlugins.kt**

After `HTTP_REQUEST` constant (line 23), add:
```kotlin
    /** Shared folder tool for user-accessible file exchange. Maps to `[shared_folder]`. */
    const val SHARED_FOLDER = "official-shared-folder"
```

Add to `ALL` set between `HTTP_REQUEST` and `COMPOSIO`:
```kotlin
            HTTP_REQUEST,
            SHARED_FOLDER,
            COMPOSIO,
```

- [ ] **Step 4: Add seed entity to SeedData.kt**

After the HTTP_REQUEST entity (line 66) and before COMPOSIO (line 67), add:
```kotlin
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.zeroclaw.android.model.OfficialPluginsTest" 2>&1 | tail -20`
Expected: PASS (all 5 tests)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/model/OfficialPlugins.kt \
       app/src/main/java/com/zeroclaw/android/data/local/SeedData.kt \
       app/src/test/java/com/zeroclaw/android/model/OfficialPluginsTest.kt
git commit -m "feat(plugins): register Shared Folder as official plugin"
```

---

### Task 3: Settings Persistence (AppSettings + Repository + DataStore)

**Files:**
- Modify: `app/src/main/java/com/zeroclaw/android/model/AppSettings.kt:198-200`
- Modify: `app/src/main/java/com/zeroclaw/android/data/repository/SettingsRepository.kt:346-364`
- Modify: `app/src/main/java/com/zeroclaw/android/data/repository/DataStoreSettingsRepository.kt`

- [ ] **Step 1: Add fields to AppSettings.kt**

After `composioEntityId` (around line 200), add:
```kotlin
    /** Whether the shared folder tool plugin is enabled. */
    val sharedFolderEnabled: Boolean = false,
    /** Persisted SAF URI for the user-chosen shared folder, or empty if not configured. */
    val sharedFolderUri: String = "",
```

- [ ] **Step 2: Add interface methods to SettingsRepository.kt**

After `setComposioEntityId` (around line 364), add:
```kotlin
    /**
     * Persists whether the shared folder tool plugin is enabled.
     *
     * @param enabled Whether to enable the shared folder tools.
     */
    suspend fun setSharedFolderEnabled(enabled: Boolean)

    /**
     * Persists the SAF URI for the user-chosen shared folder.
     *
     * @param uri The content URI string from [android.content.Intent.getData].
     */
    suspend fun setSharedFolderUri(uri: String)
```

- [ ] **Step 3: Add DataStore implementation**

In `DataStoreSettingsRepository.kt`, add preference keys near the other keys:
```kotlin
private val KEY_SHARED_FOLDER_ENABLED = booleanPreferencesKey("shared_folder_enabled")
private val KEY_SHARED_FOLDER_URI = stringPreferencesKey("shared_folder_uri")
```

Add to the settings flow mapping (where other fields are read from prefs):
```kotlin
sharedFolderEnabled = prefs[KEY_SHARED_FOLDER_ENABLED] ?: false,
sharedFolderUri = prefs[KEY_SHARED_FOLDER_URI] ?: "",
```

Add the setter implementations:
```kotlin
override suspend fun setSharedFolderEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { prefs ->
        prefs[KEY_SHARED_FOLDER_ENABLED] = enabled
    }
}

override suspend fun setSharedFolderUri(uri: String) {
    context.settingsDataStore.edit { prefs ->
        prefs[KEY_SHARED_FOLDER_URI] = uri
    }
}
```

- [ ] **Step 4: Add stubs to TestSettingsRepository**

Check `app/src/test/java/com/zeroclaw/android/ui/screen/settings/TestSettingsRepository.kt` — add the two setter stubs following the existing pattern (update the backing `MutableStateFlow` with a copy of the current settings).

- [ ] **Step 5: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/model/AppSettings.kt \
       app/src/main/java/com/zeroclaw/android/data/repository/SettingsRepository.kt \
       app/src/main/java/com/zeroclaw/android/data/repository/DataStoreSettingsRepository.kt \
       app/src/test/java/com/zeroclaw/android/ui/screen/settings/TestSettingsRepository.kt
git commit -m "feat(settings): add sharedFolderEnabled and sharedFolderUri persistence"
```

---

### Task 4: Settings Sync (OfficialPluginSettingsSync + RoomPluginRepository + SettingsViewModel)

**Files:**
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/plugins/OfficialPluginSettingsSync.kt:32-57`
- Modify: `app/src/main/java/com/zeroclaw/android/data/repository/RoomPluginRepository.kt:92-105`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/settings/SettingsViewModel.kt`

- [ ] **Step 1: Update OfficialPluginSettingsSyncTest first (TDD)**

Modify `app/src/test/java/com/zeroclaw/android/ui/screen/plugins/OfficialPluginSettingsSyncTest.kt`. Add after the existing test:

```kotlin
    @Test
    @DisplayName("restoreDefaults disables shared folder")
    fun `restoreDefaults disables shared folder`() =
        runTest {
            val repository = TestSettingsRepository()
            repository.setSharedFolderEnabled(true)

            OfficialPluginSettingsSync.restoreDefaults(repository)

            val settings = repository.settings.first()
            assertFalse(settings.sharedFolderEnabled)
        }
```

Also update the existing `restoreDefaults` test — add after `repository.setQueryClassificationEnabled(true)`:
```kotlin
            repository.setSharedFolderEnabled(true)
```
And add after `assertFalse(settings.queryClassificationEnabled)`:
```kotlin
            assertFalse(settings.sharedFolderEnabled)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.zeroclaw.android.ui.screen.plugins.OfficialPluginSettingsSyncTest" 2>&1 | tail -20`
Expected: FAIL — `setSharedFolderEnabled` not called in `restoreDefaults`

- [ ] **Step 3: Add SHARED_FOLDER to OfficialPluginSettingsSync.kt**

In `syncPluginEnabledState` when block (line 36, after COMPOSIO):
```kotlin
            OfficialPlugins.SHARED_FOLDER -> settingsRepository.setSharedFolderEnabled(enabled)
```

In `restoreDefaults` (line 56, after `setQueryClassificationEnabled`):
```kotlin
        settingsRepository.setSharedFolderEnabled(false)
```

- [ ] **Step 4: Add to RoomPluginRepository.syncOfficialPluginStates**

In the mapping (line 100, after TRANSCRIPTION):
```kotlin
                OfficialPlugins.SHARED_FOLDER to settings.sharedFolderEnabled,
```

Also add the missing `QUERY_CLASSIFICATION` entry (pre-existing gap):
```kotlin
                OfficialPlugins.QUERY_CLASSIFICATION to settings.queryClassificationEnabled,
```

- [ ] **Step 5: Add upsert logic to RoomPluginRepository**

Add a new method before `syncOfficialPluginStates`:
```kotlin
    /**
     * Inserts any missing official plugin entities from seed data.
     *
     * Called on app start before [syncOfficialPluginStates] to handle
     * upgrades that introduce new official plugins.
     */
    suspend fun upsertMissingOfficialPlugins() {
        val existingIds = dao.getExistingIds(OfficialPlugins.ALL.toList()).toSet()
        val missing = SeedData.seedPlugins()
            .filter { it.id in OfficialPlugins.ALL && it.id !in existingIds }
        if (missing.isNotEmpty()) {
            dao.insertAllIgnoreConflicts(missing)
        }
    }
```

Add the call site in `PluginsViewModel.kt`. Find `syncOfficialPluginStates` calls (lines 60 and 258) and add `upsertMissingOfficialPlugins()` before each:
```kotlin
repository.upsertMissingOfficialPlugins()
repository.syncOfficialPluginStates(settings)
```

Also add `upsertMissingOfficialPlugins` to `PluginRepository.kt` interface:
```kotlin
    /** Inserts any missing official plugin entities from seed data on upgrade. */
    suspend fun upsertMissingOfficialPlugins()
```

- [ ] **Step 6: Add SettingsViewModel update methods**

In `SettingsViewModel.kt`, add (following the `updateComposioEnabled` / `updateComposioApiKey` pattern):
```kotlin
    /** Persists shared folder enabled state and restarts the daemon. */
    fun updateSharedFolderEnabled(enabled: Boolean) {
        updateDaemonSetting { settingsRepository.setSharedFolderEnabled(enabled) }
    }

    /** Persists the shared folder SAF URI and restarts the daemon. */
    fun updateSharedFolderUri(uri: String) {
        updateDaemonSetting { settingsRepository.setSharedFolderUri(uri) }
    }
```

In the `updateOfficialPluginEnabled` method's `when` block, add the SHARED_FOLDER branch (note: calls the ViewModel method, not the repository directly — this triggers `updateDaemonSetting` for daemon restart):
```kotlin
            OfficialPlugins.SHARED_FOLDER -> updateSharedFolderEnabled(enabled)
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.zeroclaw.android.ui.screen.plugins.OfficialPluginSettingsSyncTest" 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/ui/screen/plugins/OfficialPluginSettingsSync.kt \
       app/src/main/java/com/zeroclaw/android/data/repository/RoomPluginRepository.kt \
       app/src/main/java/com/zeroclaw/android/ui/screen/settings/SettingsViewModel.kt \
       app/src/test/java/com/zeroclaw/android/ui/screen/plugins/OfficialPluginSettingsSyncTest.kt
git commit -m "feat(plugins): wire Shared Folder into settings sync pipeline"
```

---

### Task 5: TOML Config Builder

**Files:**
- Modify: `app/src/main/java/com/zeroclaw/android/service/ConfigTomlBuilder.kt`

- [ ] **Step 1: Add `sharedFolderEnabled` to `GlobalTomlConfig`**

Find the `GlobalTomlConfig` data class (around line 181). After `composioEntityId`, add:
```kotlin
    val sharedFolderEnabled: Boolean = false,
```

- [ ] **Step 2: Add `appendSharedFolderSection` method**

After `appendComposioSection` (around line 918), add:
```kotlin
    /**
     * Emits the `[shared_folder]` TOML section.
     *
     * Contains only an `enabled` flag. Actual file I/O is performed in
     * Kotlin via SAF; the TOML section signals the Rust tool registry
     * to register the shim tools.
     */
    private fun StringBuilder.appendSharedFolderSection(config: GlobalTomlConfig) {
        if (!config.sharedFolderEnabled) return
        appendLine()
        appendLine("[shared_folder]")
        appendLine("enabled = true")
    }
```

- [ ] **Step 3: Add call site in `build()` method**

In the `build()` method (around line 580), after `appendComposioSection(config)`, add:
```kotlin
        appendSharedFolderSection(config)
```

- [ ] **Step 4: Wire `sharedFolderEnabled` in the daemon's config constructor**

In `ZeroAIDaemonService.kt`, find the `buildGlobalTomlConfig()` method (around line 485). In the `GlobalTomlConfig(...)` constructor call (around line 536), add after `composioEntityId`:
```kotlin
    sharedFolderEnabled = settings.sharedFolderEnabled,
```

Note: there is also a convenience `build()` overload in `ConfigTomlBuilder.kt` (line 506) that creates a minimal config — that one can use the default value (`false`).

- [ ] **Step 5: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/service/ConfigTomlBuilder.kt
git commit -m "feat(config): emit [shared_folder] TOML section when plugin is enabled"
```

---

### Task 6: SAF Helper (Kotlin)

**Files:**
- Create: `app/src/main/java/com/zeroclaw/android/data/saf/SharedFolderSafHelper.kt`
- Create: `app/src/test/java/com/zeroclaw/android/data/saf/SharedFolderSafHelperTest.kt`

- [ ] **Step 1: Create the SAF helper class**

Create `app/src/main/java/com/zeroclaw/android/data/saf/SharedFolderSafHelper.kt`:

```kotlin
/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * SAF operations for the Shared Folder tool plugin.
 *
 * Performs list, read, and write operations against a user-chosen
 * folder via [DocumentsContract] queries and [ContentResolver] streams.
 * Follows Dolphin Emulator's [ContentHandler](https://github.com/dolphin-emu/dolphin/blob/master/Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/utils/ContentHandler.java)
 * patterns for SAF path traversal and error handling.
 *
 * @param context Android context for [ContentResolver] access.
 */
class SharedFolderSafHelper(private val context: Context) {

    private val json = Json { prettyPrint = false }

    /**
     * Lists immediate children of a path within the shared folder.
     *
     * @param rootUri Persisted SAF tree URI for the shared folder root.
     * @param path Relative path from the root (e.g., `"subfolder"` or `"/"`).
     * @return JSON array of [FolderEntry] objects.
     * @throws IOException if the folder is not accessible.
     */
    fun list(rootUri: Uri, path: String): String {
        val targetUri = resolvePath(rootUri, path)
            ?: return errorJson("Path not found: $path")

        val docId = DocumentsContract.getDocumentId(targetUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, docId)

        val entries = mutableListOf<FolderEntry>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: continue
                        val mimeType = cursor.getString(1) ?: ""
                        val size = cursor.getLong(2)
                        val lastModified = cursor.getLong(3)
                        val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                        entries.add(
                            FolderEntry(
                                name = name,
                                type = if (isDir) "directory" else "file",
                                sizeBytes = if (isDir) 0L else size,
                                lastModified = formatTimestamp(lastModified),
                            ),
                        )
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list $path", e)
            return errorJson("Failed to list folder: ${e.message}")
        }
        return json.encodeToString(entries)
    }

    /**
     * Reads a file from the shared folder.
     *
     * @param rootUri Persisted SAF tree URI.
     * @param path Relative path to the file.
     * @return JSON object with content (text or base64).
     */
    fun read(rootUri: Uri, path: String): String {
        val targetUri = resolvePath(rootUri, path)
            ?: return errorJson("File not found: $path")

        val mimeType = context.contentResolver.getType(targetUri) ?: "application/octet-stream"
        val isText = mimeType.startsWith("text/") ||
            mimeType in TEXT_MIME_TYPES

        try {
            val size = getFileSize(targetUri)
            val maxSize = if (isText) MAX_TEXT_READ_BYTES else MAX_BINARY_READ_BYTES
            if (size > maxSize) {
                val limitMb = maxSize / (1024 * 1024)
                return errorJson("File too large (${size / (1024 * 1024)}MB). Limit: ${limitMb}MB for ${if (isText) "text" else "binary"} files.")
            }

            context.contentResolver.openInputStream(targetUri)?.use { stream ->
                val bytes = stream.readBytes()
                return if (isText) {
                    json.encodeToString(TextReadResult("text", String(bytes, Charsets.UTF_8)))
                } else {
                    json.encodeToString(
                        BinaryReadResult(
                            "binary",
                            mimeType,
                            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                        ),
                    )
                }
            } ?: return errorJson("Cannot open file: $path")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $path", e)
            return errorJson("Failed to read file: ${e.message}")
        }
    }

    /**
     * Writes a file or creates a directory in the shared folder.
     *
     * Uses `"wt"` mode for overwrites (truncate), matching Dolphin's
     * fix for Android's non-truncating `"w"` mode (PR #11670).
     *
     * @param rootUri Persisted SAF tree URI.
     * @param path Relative path for the new file or directory.
     * @param content File content (text or base64), ignored if [mkdir] is true.
     * @param isBase64 Whether [content] is base64-encoded binary.
     * @param mkdir If true, create a directory at [path].
     * @return JSON confirmation with path and bytes written.
     */
    fun write(
        rootUri: Uri,
        path: String,
        content: String?,
        isBase64: Boolean,
        mkdir: Boolean,
    ): String {
        try {
            if (mkdir) {
                val created = createDirectories(rootUri, path)
                    ?: return errorJson("Failed to create directory: $path")
                val name = DocumentsContract.getDocumentId(created)
                return json.encodeToString(WriteResult(path, 0))
            }

            val bytes = if (isBase64 && content != null) {
                android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            } else {
                content?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            }

            if (bytes.size > MAX_WRITE_BYTES) {
                return errorJson("Write too large (${bytes.size / (1024 * 1024)}MB). Limit: ${MAX_WRITE_BYTES / (1024 * 1024)}MB.")
            }

            val segments = splitPath(path)
            val fileName = segments.lastOrNull()
                ?: return errorJson("Invalid path: $path")
            val parentPath = segments.dropLast(1)

            val parentUri = if (parentPath.isEmpty()) {
                documentUriFromTree(rootUri)
            } else {
                createDirectories(rootUri, parentPath.joinToString("/"))
                    ?: return errorJson("Failed to create parent directories for: $path")
            }

            val existingUri = findChild(rootUri, parentUri, fileName)
            val targetUri = if (existingUri != null) {
                existingUri
            } else {
                val mimeType = guessMimeType(fileName)
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    mimeType,
                    fileName,
                ) ?: return errorJson("Failed to create file: $path")
            }

            context.contentResolver.openOutputStream(targetUri, "wt")?.use { stream ->
                stream.write(bytes)
            } ?: return errorJson("Cannot write to: $path")

            return json.encodeToString(WriteResult(path, bytes.size.toLong()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write $path", e)
            return errorJson("Failed to write: ${e.message}")
        }
    }

    /**
     * Resolves a relative path to a SAF document URI by walking each
     * segment through [DocumentsContract] queries.
     *
     * Follows the same segment-by-segment traversal as Dolphin's
     * `unmangle()` + `getChild()` pattern, but without the
     * mangling/unmangling layer since our tools accept clean paths.
     */
    private fun resolvePath(rootUri: Uri, path: String): Uri? {
        val segments = splitPath(path)
        if (segments.isEmpty()) return documentUriFromTree(rootUri)

        var current = documentUriFromTree(rootUri)
        for (segment in segments) {
            current = findChild(rootUri, current, segment) ?: return null
        }
        return current
    }

    /** Finds a child document by display name within a parent. */
    private fun findChild(treeUri: Uri, parentUri: Uri, childName: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (childName == cursor.getString(0)) {
                            val docId = cursor.getString(1)
                            return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "findChild failed for $childName", e)
        }
        return null
    }

    /** Creates directories along a path, returning the URI of the deepest one. */
    private fun createDirectories(rootUri: Uri, path: String): Uri? {
        val segments = splitPath(path)
        var current = documentUriFromTree(rootUri)
        for (segment in segments) {
            val existing = findChild(rootUri, current, segment)
            current = existing ?: DocumentsContract.createDocument(
                context.contentResolver,
                current,
                DocumentsContract.Document.MIME_TYPE_DIR,
                segment,
            ) ?: return null
        }
        return current
    }

    private fun documentUriFromTree(treeUri: Uri): Uri {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    private fun getFileSize(uri: Uri): Long {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }

    private fun splitPath(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() && !it.contains('/') }

    private fun formatTimestamp(millis: Long): String {
        if (millis == 0L) return ""
        val instant = java.time.Instant.ofEpochMilli(millis)
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MIME_MAP[ext] ?: "application/octet-stream"
    }

    private fun errorJson(message: String): String =
        json.encodeToString(ErrorResult(message))

    @Serializable
    private data class FolderEntry(
        val name: String,
        val type: String,
        @kotlinx.serialization.SerialName("size_bytes")
        val sizeBytes: Long,
        @kotlinx.serialization.SerialName("last_modified")
        val lastModified: String,
    )

    @Serializable
    private data class TextReadResult(val type: String, val content: String)

    @Serializable
    private data class BinaryReadResult(
        val type: String,
        @kotlinx.serialization.SerialName("mime_type")
        val mimeType: String,
        @kotlinx.serialization.SerialName("content_base64")
        val contentBase64: String,
    )

    @Serializable
    private data class WriteResult(
        val path: String,
        @kotlinx.serialization.SerialName("bytes_written")
        val bytesWritten: Long,
    )

    @Serializable
    private data class ErrorResult(val error: String)

    companion object {
        private const val TAG = "SharedFolderSaf"
        private const val MAX_TEXT_READ_BYTES = 10L * 1024 * 1024
        private const val MAX_BINARY_READ_BYTES = 2L * 1024 * 1024
        private const val MAX_WRITE_BYTES = 50L * 1024 * 1024

        private val TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-yaml",
            "application/toml",
        )

        private val MIME_MAP = mapOf(
            "txt" to "text/plain",
            "md" to "text/markdown",
            "json" to "application/json",
            "xml" to "application/xml",
            "html" to "text/html",
            "csv" to "text/csv",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "pdf" to "application/pdf",
            "zip" to "application/zip",
            "mp3" to "audio/mpeg",
            "mp4" to "video/mp4",
        )
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/data/saf/SharedFolderSafHelper.kt
git commit -m "feat(saf): add SharedFolderSafHelper for SAF list/read/write operations"
```

---

### Task 7: FFI Callback Interface (Rust + Kotlin)

**Files:**
- Create: `zeroclaw-android/zeroclaw-ffi/src/shared_folder.rs`
- Modify: `zeroclaw-android/zeroclaw-ffi/src/lib.rs`
- Create: `app/src/main/java/com/zeroclaw/android/data/saf/SharedFolderCallbackHandler.kt`

- [ ] **Step 1: Create the Rust callback interface and FFI export**

Create `zeroclaw-android/zeroclaw-ffi/src/shared_folder.rs`:

```rust
// Copyright (c) 2026 @Natfii. All rights reserved.

//! Shared folder tool shims that delegate to Kotlin via UniFFI callback.
//!
//! The three tools (`shared_folder_list`, `shared_folder_read`,
//! `shared_folder_write`) are thin wrappers registered in the Rust tool
//! registry. Actual SAF file I/O is performed by the Kotlin-side
//! [`SharedFolderHandler`] implementation.

use crate::FfiError;
use std::sync::Mutex;

/// Callback interface implemented in Kotlin for SAF operations.
#[uniffi::export(callback_interface)]
pub trait SharedFolderHandler: Send + Sync {
    /// Executes a shared folder tool operation.
    ///
    /// # Arguments
    /// * `tool_name` - One of: `shared_folder_list`, `shared_folder_read`, `shared_folder_write`
    /// * `params_json` - JSON-encoded tool parameters
    ///
    /// # Returns
    /// JSON-encoded result string on success.
    fn execute_shared_folder_tool(
        &self,
        tool_name: String,
        params_json: String,
    ) -> Result<String, FfiError>;
}

static HANDLER: Mutex<Option<Box<dyn SharedFolderHandler>>> = Mutex::new(None);

/// Registers the Kotlin-side shared folder handler.
///
/// Called from `ZeroAIDaemonService` on daemon start when the
/// shared folder plugin is enabled.
#[uniffi::export]
pub fn register_shared_folder_handler(handler: Box<dyn SharedFolderHandler>) {
    let mut guard = HANDLER.lock().unwrap_or_else(|e| e.into_inner());
    *guard = Some(handler);
}

/// Unregisters the shared folder handler.
///
/// Called on daemon stop to release the Kotlin reference.
#[uniffi::export]
pub fn unregister_shared_folder_handler() {
    let mut guard = HANDLER.lock().unwrap_or_else(|e| e.into_inner());
    *guard = None;
}

/// Dispatches a shared folder tool call to the registered handler.
///
/// Returns an error JSON string if no handler is registered.
pub(crate) fn dispatch(tool_name: &str, params_json: &str) -> Result<String, FfiError> {
    let guard = HANDLER.lock().unwrap_or_else(|e| e.into_inner());
    match guard.as_ref() {
        Some(handler) => handler.execute_shared_folder_tool(
            tool_name.to_string(),
            params_json.to_string(),
        ),
        None => Err(FfiError::StateError {
            detail: "Shared folder handler not registered. Enable the Shared Folder plugin in Hub > Plugins.".into(),
        }),
    }
}
```

- [ ] **Step 2: Add module to lib.rs**

In `zeroclaw-android/zeroclaw-ffi/src/lib.rs`, add with the other module declarations:
```rust
mod shared_folder;
```

- [ ] **Step 3: Create the Kotlin callback handler**

Create `app/src/main/java/com/zeroclaw/android/data/saf/SharedFolderCallbackHandler.kt`:

```kotlin
/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.saf

import android.content.Context
import android.net.Uri
import com.zeroclaw.ffi.FfiException
import com.zeroclaw.ffi.SharedFolderHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * UniFFI callback handler that bridges Rust tool dispatch to SAF operations.
 *
 * Implements the [SharedFolderHandler] callback interface generated by UniFFI.
 * Parses tool parameters from JSON, delegates to [SharedFolderSafHelper],
 * and returns JSON result strings.
 *
 * @param context Android context for SAF access.
 * @param getFolderUri Lambda returning the current persisted SAF URI string.
 */
class SharedFolderCallbackHandler(
    private val context: Context,
    private val getFolderUri: () -> String,
) : SharedFolderHandler {

    private val helper = SharedFolderSafHelper(context)
    private val json = Json { ignoreUnknownKeys = true }

    override fun executeSharedFolderTool(
        toolName: String,
        paramsJson: String,
    ): String {
        val uriString = getFolderUri()
        if (uriString.isBlank()) {
            throw FfiException.StateException(
                "No shared folder configured. Pick a folder in Hub > Plugins > Shared Folder.",
            )
        }
        val rootUri = Uri.parse(uriString)

        return when (toolName) {
            "shared_folder_list" -> {
                val params = json.decodeFromString<ListParams>(paramsJson)
                helper.list(rootUri, params.path)
            }
            "shared_folder_read" -> {
                val params = json.decodeFromString<ReadParams>(paramsJson)
                helper.read(rootUri, params.path)
            }
            "shared_folder_write" -> {
                val params = json.decodeFromString<WriteParams>(paramsJson)
                helper.write(rootUri, params.path, params.content, params.isBase64, params.mkdir)
            }
            else -> throw FfiException.InvalidArgumentException("Unknown tool: $toolName")
        }
    }

    @Serializable
    private data class ListParams(val path: String = "/")

    @Serializable
    private data class ReadParams(val path: String)

    @Serializable
    private data class WriteParams(
        val path: String,
        val content: String? = null,
        val isBase64: Boolean = false,
        val mkdir: Boolean = false,
    )
}
```

- [ ] **Step 4: Verify Rust compiles**

Run: `cd zeroclaw-android && cargo check 2>&1 | tail -10`
Expected: no errors in `shared_folder.rs`

- [ ] **Step 5: Commit**

```bash
git add zeroclaw-android/zeroclaw-ffi/src/shared_folder.rs \
       zeroclaw-android/zeroclaw-ffi/src/lib.rs \
       app/src/main/java/com/zeroclaw/android/data/saf/SharedFolderCallbackHandler.kt
git commit -m "feat(ffi): add SharedFolderHandler callback interface and Kotlin bridge"
```

---

### Task 8: Plugin Config UI

**Files:**
- Modify: `app/src/main/java/com/zeroclaw/android/ui/screen/plugins/OfficialPluginConfigSection.kt:59-70`

- [ ] **Step 1: Add `SHARED_FOLDER` branch to the when block**

In the `when` dispatch (line 60-69), add between COMPOSIO and VISION:
```kotlin
            OfficialPlugins.SHARED_FOLDER -> SharedFolderConfig(settings, viewModel)
```

- [ ] **Step 2: Add the SharedFolderConfig composable**

After `ComposioConfig` (around line 333), add:

```kotlin
/**
 * Shared folder plugin configuration.
 *
 * Shows the selected folder display name, a "Change Folder" button
 * that launches the SAF tree picker, and folder status. The
 * [ActivityResultLauncher] is registered internally via
 * [rememberLauncherForActivityResult].
 *
 * Follows Dolphin Emulator's URI canonicalization pattern: the URI
 * is canonicalized via [ContentResolver.canonicalize] before
 * persisting, producing a stable identifier across provider quirks.
 */
@Composable
private fun SharedFolderConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            val canonicalized = context.contentResolver.canonicalize(uri) ?: uri
            viewModel.updateSharedFolderUri(canonicalized.toString())
        }
    }

    if (settings.sharedFolderUri.isNotBlank()) {
        val displayName = remember(settings.sharedFolderUri) {
            getDisplayName(context, Uri.parse(settings.sharedFolderUri))
        }

        OutlinedTextField(
            value = displayName ?: "Unknown folder",
            onValueChange = {},
            readOnly = true,
            label = { Text("Selected folder") },
            enabled = settings.sharedFolderEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Button(
        onClick = { folderPickerLauncher.launch(null) },
        enabled = settings.sharedFolderEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (settings.sharedFolderUri.isBlank()) "Choose Folder" else "Change Folder",
        )
    }

    if (settings.sharedFolderEnabled && settings.sharedFolderUri.isBlank()) {
        Text(
            text = "No folder selected \u2014 tap Choose Folder to pick one",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Extracts the display name from a SAF document URI.
 *
 * @return The folder display name, or null if the URI is stale.
 */
private fun getDisplayName(context: Context, uri: Uri): String? {
    try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri),
        )
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
    } catch (_: Exception) {
        /* URI is stale or permission revoked */
    }
    return null
}
```

Add required imports at the top of the file:
```kotlin
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/ui/screen/plugins/OfficialPluginConfigSection.kt
git commit -m "feat(ui): add Shared Folder config screen with SAF folder picker"
```

---

### Task 9: Daemon Service Integration

**Files:**
- Modify: `app/src/main/java/com/zeroclaw/android/service/ZeroAIDaemonService.kt`

- [ ] **Step 1: Register the callback handler on daemon start**

Find where the daemon is initialized (after config is built and `startDaemon` is called). Add:

```kotlin
if (settings.sharedFolderEnabled) {
    val handler = SharedFolderCallbackHandler(
        context = applicationContext,
        getFolderUri = {
            runBlocking { settingsRepository.settings.first().sharedFolderUri }
        },
    )
    registerSharedFolderHandler(handler)
}
```

Add import:
```kotlin
import com.zeroclaw.android.data.saf.SharedFolderCallbackHandler
import com.zeroclaw.ffi.registerSharedFolderHandler
import com.zeroclaw.ffi.unregisterSharedFolderHandler
```

- [ ] **Step 2: Unregister on daemon stop**

In `ZeroAIDaemonService.kt`, find `handleStop()` (line 804). At the top of the method (before `startJob?.cancel()`), add:
```kotlin
unregisterSharedFolderHandler()
```

This ensures the Kotlin reference is released before the daemon's Rust runtime shuts down.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/service/ZeroAIDaemonService.kt
git commit -m "feat(service): register SharedFolderHandler on daemon start"
```

---

### Task 10: Full Integration Test

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -30`
Expected: All tests pass

- [ ] **Step 2: Run Rust checks**

Run: `cd zeroclaw-android && cargo check 2>&1 | tail -10`
Expected: No errors

- [ ] **Step 3: Run clippy**

Run: `cd zeroclaw-android && cargo clippy -- -D warnings 2>&1 | tail -10`
Expected: No warnings

- [ ] **Step 4: Build debug APK**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Final commit if any fixups needed**

```bash
git add -A
git commit -m "fix: address integration issues from Shared Folder tool"
```
