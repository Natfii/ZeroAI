# Shared Folder Tool — Design Spec

**Date**: 2026-03-18
**Status**: Approved
**Author**: Claude (brainstorm with Natali)

## Summary

A new official tool plugin in Hub > Plugins that gives the ZeroAI agent read-write access to a user-chosen folder on the device via Android's Storage Access Framework (SAF). The user picks any folder through the system directory picker; the agent can then list, read, and write files there on demand. The folder is fully visible in file managers and accessible by other apps — functioning as a shared workspace between the user, the agent, and any other app on the device.

## Motivation

The agent currently has no way to exchange files with the user or other apps. App-specific storage (`Android/data/`) is hidden from users on Android 11+. A user-chosen shared folder (like how DuckStation, Dolphin, and RetroArch let users pick ROM/save directories) gives the agent a visible, accessible place to drop outputs and pick up inputs.

## Approach: Pure SAF

**Why SAF over alternatives:**

| Approach | Verdict |
|----------|---------|
| `MANAGE_EXTERNAL_STORAGE` | Google Play rejects it for non-file-manager apps |
| MediaStore (Downloads) | One-directional — app can write, but can't read files others drop in |
| FileProvider | Only works for intentional sharing via intents, not passive browsing |
| App-specific storage | Hidden from users on Android 11+ |
| **SAF (`ACTION_OPEN_DOCUMENT_TREE`)** | **User-controlled, Play-safe, folder visible everywhere** |

**SAF performance note:** SAF has a 25-50x overhead vs `File` I/O for directory traversal (two IPC hops per call). Mitigations:
- Use `DocumentsContract` queries directly, never `DocumentFile.findFile()`
- Stream large files via `ContentResolver`, never load entirely into memory
- 10MB read guard prevents the agent from dumping huge files into its context

## Plugin Identity

- **ID**: `official-shared-folder`
- **Name**: Shared Folder
- **Description**: Read and write files to a shared folder on your device.
- **Category**: `TOOL`
- **Author**: ZeroAI
- **Version**: 1.0.0
- **Default state**: Installed, disabled
- **Position**: 4th in `officialPluginEntities()` list, between HTTP Request and Composio
- **Sort order**: Displayed in list insertion order (same as existing plugins)

Registered in `OfficialPlugins.kt`, seeded in `SeedData.kt`, config section in `OfficialPluginConfigSection.kt`.

## Storage & Permissions

### SAF Flow

1. User enables the plugin toggle
2. If no folder URI is stored, `ACTION_OPEN_DOCUMENT_TREE` picker launches immediately
3. User picks or creates a folder (e.g. "ZeroAI Shared")
4. App calls `takePersistableUriPermission()` with `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION`
5. URI string is persisted in `AppSettings` via DataStore (follows official plugin pattern — settings live in `AppSettings`, not in the plugin's generic `configFields`)
6. If the user cancels the picker on first enable, the switch reverts to off

### Permission Lifecycle

- URI permission persists across reboots via `takePersistableUriPermission()`
- Permissions are automatically revoked on app uninstall
- If the folder is moved/deleted externally, tool calls fail gracefully with an error message
- Disabling the plugin just deactivates the tools — the stored URI is retained for re-enable
- No cleanup of user files on disable or uninstall

## Tool Dispatch Architecture

This is the first official plugin whose tools execute in Kotlin rather than Rust. The Rust agent loop owns tool discovery and dispatch, so we need a bridge.

### Approach: Rust Shim Tools + FFI Callback

1. **Three Rust `Tool` trait implementations** are registered in the Rust tool registry (`shared_folder_list`, `shared_folder_read`, `shared_folder_write`). These are thin shims — they serialize the tool parameters into JSON and call a single FFI callback function.

2. **FFI callback**: A new `#[uniffi::export(callback_interface)]` trait `SharedFolderHandler` with one method:
   ```rust
   fn execute_shared_folder_tool(tool_name: String, params_json: String) -> Result<String, FfiError>;
   ```
   Kotlin implements this interface, receives the call, performs the SAF operation via `ContentResolver`, and returns the JSON result string.

3. **Registration**: On daemon start, if the shared folder plugin is enabled, Kotlin passes its `SharedFolderHandler` implementation to Rust via a `register_shared_folder_handler(handler)` FFI function. The Rust shim tools check for a registered handler and return an error if none is present.

4. **System prompt**: Because the tools are registered as real `Tool` trait objects in Rust, they automatically appear in the tool list the LLM sees. Tool schemas (parameter definitions) are hardcoded in the Rust shim implementations.

5. **TOML section**: A minimal `[shared_folder]` section with `enabled = true` signals to the Rust daemon that the shared folder tools should be registered. This preserves the `OfficialPlugins` contract that every official plugin maps to a TOML section. The `OfficialPlugins.kt` KDoc will be updated to note that Shared Folder's execution is Kotlin-side via callback, unlike other tools.

### Why Not Pure Kotlin Dispatch

The Rust agent loop constructs the system prompt with tool schemas and parses tool calls from model responses. Intercepting tool calls in Kotlin before they reach Rust would require duplicating prompt construction and response parsing logic. The shim approach keeps the Rust loop as the single owner of tool dispatch.

## Tool Definitions

Three tools registered in the Rust tool registry as shim implementations that delegate to Kotlin via FFI callback. SAF operations require `ContentResolver` which needs an Android `Context`, so actual I/O executes in Kotlin.

### `shared_folder_list`

Lists contents of a path within the shared folder.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `path` | string | no | `"/"` | Path relative to the shared folder root |

**Returns:** JSON array of entries:
```json
[
  {
    "name": "notes.md",
    "type": "file",
    "size_bytes": 2048,
    "last_modified": "2026-03-18T14:30:00Z"
  },
  {
    "name": "agent-output",
    "type": "directory",
    "size_bytes": 0,
    "last_modified": "2026-03-18T12:00:00Z"
  }
]
```

Non-recursive — lists immediate children only.

**SAF path traversal:** SAF operates on `Uri` objects, not filesystem paths. To resolve a relative path like `"subfolder/notes.md"`, the implementation must walk each segment by querying `DocumentsContract.buildChildDocumentsUriUsingTree()` at each level. Path resolution is case-sensitive (matching SAF display name behavior on most providers). Names containing `/` are not supported and should be rejected. `size_bytes` for directories is always reported as `0` (SAF does not provide directory sizes).

### `shared_folder_read`

Reads a file from the shared folder.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | yes | Path relative to the shared folder root |

**Returns:** JSON object:
- Text files (detected by MIME type): `{"type": "text", "content": "file contents here"}`
- Binary files: `{"type": "binary", "mime_type": "image/png", "content_base64": "iVBOR..."}`

**Guards:**
- Text files: refuses over 10MB
- Binary files: refuses over 2MB (base64 expands to ~2.7MB which is manageable in LLM context; larger binaries should be referenced by path, not read into context)
- Returns clear error if path doesn't exist or points to a directory

### `shared_folder_write`

Writes a file or creates a directory in the shared folder.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `path` | string | yes | — | Path relative to the shared folder root |
| `content` | string | no | — | File content to write |
| `is_base64` | bool | no | `false` | Whether `content` is base64-encoded binary |
| `mkdir` | bool | no | `false` | If true, create directory at `path` (ignores `content`) |

**Returns:** JSON object: `{"path": "subfolder/output.txt", "bytes_written": 1234}`

Creates parent directories as needed. Overwrites existing files at the same path.

**SAF overwrite note:** `DocumentsContract.createDocument()` does not overwrite — it deduplicates names (e.g. `notes (1).md`). To achieve true overwrite semantics, the implementation must first resolve the existing document URI via path traversal, then open it with `ContentResolver.openOutputStream(uri, "wt")` (truncate mode). Only if the file does not exist should `createDocument()` be used.

**Write size guard:** Refuses writes over 50MB to prevent the agent from filling storage with a single operation.

### No Delete Tool

File deletion is intentionally excluded. The user manages cleanup through their device's file manager. This keeps the tool safe — the agent can create but not destroy.

## UI

### Plugin Card (Plugins Tab)

Standard `PluginListItem` card matching existing official tools:
- Name: "Shared Folder"
- Description: "Read and write files to a shared folder on your device."
- Category badge: TOOL
- Official badge: yes
- Enable/disable switch
- Warning icon next to switch if stored folder URI is stale/inaccessible

### Plugin Detail Screen (Config Section)

Added as a new branch in `OfficialPluginConfigSection`:

- **Selected folder** — display name extracted from the document URI (e.g. "ZeroAI Shared"), not the raw `content://` string
- **"Change Folder" button** — re-launches the SAF picker, replaces the stored URI
- **Folder status** — one-liner: "12 files, 3 folders" or "Folder not accessible" in error color if URI is stale
- No other config fields

**ActivityResultLauncher hosting:** The `SharedFolderConfig` composable registers a `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())` internally. The launcher result callback updates the URI via `SettingsViewModel`. This keeps the launcher scoped to the config section — no changes needed to `PluginDetailScreen` or `OfficialPluginConfigSection` signatures.

### Error States

| State | Card behavior | Detail screen behavior |
|-------|---------------|----------------------|
| No folder selected | Switch off, picker launches on enable | "No folder selected" prompt |
| Folder accessible | Normal switch | Display name + file count |
| Folder stale/deleted | Warning icon on card | "Folder not accessible" error text + "Change Folder" button |
| Permission lost (reinstall) | Same as stale | Same as stale, re-pick resolves it |

## Non-Goals

These are explicitly out of scope and can be revisited later:

- **File deletion tool** — user manages via file manager
- **File watching / auto-detection** — agent only checks when asked
- **Custom DocumentsProvider** — no need for other apps to discover ZeroAI files through the system picker sidebar
- **MediaStore output mirroring** — everything stays in the one shared folder
- **Rust-side file operations** — SAF requires Android Context, stays in Kotlin
- **Size quotas or file type restrictions** — user's device, user's responsibility
- **Polling or reactive file monitoring** — on-demand only

## Database Migration

Adding a new seed plugin to `SeedData.kt` only affects fresh installs. Existing users need the new `official-shared-folder` entity inserted on upgrade. The existing `RoomPluginRepository.syncOfficialPluginStates()` only syncs enabled state for known IDs — it does not insert missing rows. We need to add an "upsert missing official plugins" step: on app start, check `OfficialPlugins.ALL` against existing plugin IDs in Room, and insert any missing seed entities from `SeedData.officialPluginEntities()`. This logic belongs in `RoomPluginRepository` and should run before `syncOfficialPluginStates()`.

No Room version bump is needed — this is a data-layer insert, not a schema change.

## Files to Create or Modify

### New Kotlin Files
- `SharedFolderConfig` composable in `OfficialPluginConfigSection.kt` (new branch in the `when` block)
- `SharedFolderSafHelper.kt` — SAF URI resolution, permission management, `DocumentsContract` path traversal, list/read/write operations
- `SharedFolderHandler.kt` — implements the `SharedFolderHandler` UniFFI callback interface, delegates to `SharedFolderSafHelper`

### New Rust Files
- `shared_folder.rs` in `zeroclaw-ffi/src/` — three `Tool` trait shim implementations + `SharedFolderHandler` callback interface definition + `register_shared_folder_handler` FFI export

### Modified Kotlin Files
- `OfficialPlugins.kt` — add `SHARED_FOLDER` constant and include in `ALL` set
- `SeedData.kt` — add seed `PluginEntity` for `official-shared-folder` (4th position, between HTTP Request and Composio)
- `AppSettings.kt` — add `sharedFolderUri: String` and `sharedFolderEnabled: Boolean` fields
- `SettingsViewModel.kt` — add `updateSharedFolderUri()` and `updateSharedFolderEnabled()` methods, add `SHARED_FOLDER` branch to `updateOfficialPluginEnabled()`
- `DataStoreSettingsRepository.kt` — persist the shared folder URI and enabled state
- `OfficialPluginSettingsSync.kt` — add `SHARED_FOLDER` branch to `syncPluginEnabledState()` and `restoreDefaults()`
- `OfficialPluginConfigSection.kt` — add `OfficialPlugins.SHARED_FOLDER ->` branch
- `RoomPluginRepository.kt` — add upsert logic for missing official plugins on startup, add `SHARED_FOLDER` to `syncOfficialPluginStates()` mapping
- `ConfigTomlBuilder.kt` — emit `[shared_folder]\nenabled = true` when plugin is enabled
- `ZeroAIDaemonService.kt` — register `SharedFolderHandler` callback on daemon start when plugin is enabled

### Modified Rust Files
- `zeroclaw/src/config/schema.rs` — add `SharedFolderConfig` struct (`enabled: bool`, `#[serde(default)]`) and `shared_folder: SharedFolderConfig` field on `Config`
- `zeroclaw-ffi/src/lib.rs` — export `register_shared_folder_handler` function
- Tool registry — register the three shim tools when `config.shared_folder.enabled == true`

### TOML Config Details (Verified by TOML Reviewer)

The `Config` struct in `schema.rs` does **not** use `deny_unknown_fields` at the top level, so emitting `[shared_folder]` before the Rust struct exists would be silently ignored (safe, but tools wouldn't register). The Rust struct must be added for the tool registry to read `config.shared_folder.enabled`.

Rust side:
```rust
/// Shared folder tool configuration (SAF-backed, executes in Kotlin via FFI callback).
#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
pub struct SharedFolderConfig {
    /// Enable shared folder shim tools in the tool registry.
    #[serde(default)]
    pub enabled: bool,
}

impl Default for SharedFolderConfig {
    fn default() -> Self {
        Self { enabled: false }
    }
}
```

Kotlin side (`ConfigTomlBuilder.kt`):
```kotlin
private fun StringBuilder.appendSharedFolderSection(config: GlobalTomlConfig) {
    if (!config.sharedFolderEnabled) return
    appendLine()
    appendLine("[shared_folder]")
    appendLine("enabled = true")
}
```

Emit pattern matches existing official plugins (e.g., `appendWebSearchSection`). Emit unquoted `true` — not `tomlString()` — to match Rust `bool` deserialization.

---

## Appendix: Prior Art — Dolphin Emulator SAF Implementation

ZeroAI's Shared Folder tool follows patterns established by **Dolphin Emulator** ([dolphin-emu/dolphin](https://github.com/dolphin-emu/dolphin)), the most mature SAF integration in an open-source Android app with a native (C++) backend. Dolphin faced the same core challenge: bridging Android's content URI system to native code that expects filesystem paths.

### Folder Picker Flow

Dolphin's folder picker lives in [`MainPresenter.kt`](https://github.com/dolphin-emu/dolphin/blob/master/Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/ui/main/MainPresenter.kt):

```kotlin
// Launch the system folder picker
val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
requestDirectory.launch(intent)

// Handle result — canonicalize URI, then persist permission
private val requestDirectory = activity.registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        var uri = result.data!!.data!!
        val canonicalizedUri = contentResolver.canonicalize(uri)
        if (canonicalizedUri != null) uri = canonicalizedUri
        val takeFlags = result.data!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)
        dirToAdd = uri.toString()
    }
}
```

Key detail: Dolphin **canonicalizes the URI** (`contentResolver.canonicalize()`) before persisting it. This resolves provider-specific URI quirks and produces a stable identifier. ZeroAI should do the same.

The URI string is then stored in Dolphin's INI config via native code (`NativeConfig.save()`). ZeroAI stores it in `AppSettings` via DataStore instead — same concept, different persistence layer.

Settings-level file pickers follow the same pattern in [`SettingsActivityResultLaunchers.kt`](https://github.com/dolphin-emu/dolphin/blob/master/Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/features/settings/ui/SettingsActivityResultLaunchers.kt), always canonicalizing before calling `takePersistableUriPermission()`.

### Content URI ↔ Native Bridge

Dolphin's [`ContentHandler.java`](https://github.com/dolphin-emu/dolphin/blob/master/Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/utils/ContentHandler.java) is a static utility class with `@Keep`-annotated methods called from C++ via JNI:

| Method | Returns | On failure |
|--------|---------|-----------|
| `openFd(uri, mode)` | Raw file descriptor (`detachFd()`) | `-1` |
| `getSizeAndIsDirectory(uri)` | File size, or `-2` for directory | `-1` |
| `getDisplayName(uri)` | Display name string | `null` |
| `getChildNames(uri, recursive)` | String array of child names | Empty array |
| `delete(uri)` | `true` on success | `false` |

The `openFd()` flow: content URI → `unmangle()` → `ContentResolver.openFileDescriptor()` → `detachFd()` → return raw int fd to native. Native C++ wraps this fd with `fdopen()`.

ZeroAI's approach is simpler: instead of passing raw file descriptors across the FFI boundary, the Kotlin `SharedFolderHandler` performs the entire SAF operation and returns a JSON result string. This avoids the complexity of managing file descriptors across Rust/Kotlin and the need for an `unmangle()` layer.

### URI Path Resolution (Unmangle)

Dolphin's C++ code appends filenames to content URIs with `/` separators (treating them like filesystem paths). The `unmangle()` method recursively resolves these back to proper `DocumentsContract` URIs:

```java
// Simplified — the actual code handles edge cases around % encoding
public static Uri unmangle(String uri) throws FileNotFoundException {
    // Base case: URI has no appended segments
    if (lastComponentStart == 0) return Uri.parse(uri);
    // Recursive: resolve parent, then find child by display name
    Uri parentUri = unmangle(uri.substring(0, lastComponentStart));
    String childName = uri.substring(lastComponentStart, lastComponentEnd);
    return getChild(parentUri, childName);  // DocumentsContract query
}
```

Each `getChild()` call issues a `DocumentsContract.buildChildDocumentsUriUsingTree()` query to find a child document by display name. For a path like `games/roms/game.iso`, this is 3 cursor queries.

ZeroAI uses the same segment-by-segment traversal strategy in `SharedFolderSafHelper`, but without the mangling/unmangling layer — our tools accept clean relative paths from the start.

### Write Mode Translation

Dolphin discovered that Android's `ContentResolver.openFileDescriptor(uri, "w")` does **not** truncate the file (unlike POSIX `O_WRONLY|O_TRUNC`). The fix ([PR #11670](https://github.com/dolphin-emu/dolphin/pull/11670)) maps C++ `"w"` to Android `"wt"` (write + truncate):

| Intent | Android mode |
|--------|-------------|
| Read | `"r"` |
| Write (overwrite) | `"wt"` |
| Append | `"wa"` |
| Read-write (overwrite) | `"rwt"` |

ZeroAI's spec already specifies `"wt"` mode for overwrites in the SAF overwrite note above.

### Error Handling

From the class-level comment in `ContentHandler.java`:

> *We use a lot of "catch (Exception e)" in this class. This is for two reasons: (1) We don't want any exceptions to escape to native code, as this leads to nasty crashes that often don't have stack traces that make sense. (2) The sheer number of different exceptions, both documented and undocumented: FileNotFoundException, IllegalArgumentException, SecurityException, UnsupportedOperationException...*

Dolphin returns sentinel values (`-1`, `null`, empty array) instead of throwing across the JNI boundary. ZeroAI's UniFFI callback returns `Result<String, FfiError>`, which provides the same safety guarantee through Rust's type system rather than sentinel values.

### Performance: What Dolphin Learned

Dolphin's SAF experience across several years and PRs established the performance baseline ZeroAI's design accounts for:

1. **SAF only for user-selected paths** ([PR #9696](https://github.com/dolphin-emu/dolphin/pull/9696)): *"Making our C++ code support the Storage Access Framework for everything stored in the user directory would both be a very big undertaking and would likely lead to severe performance problems."* Saves, settings, and state files use app-specific storage with direct file I/O. Only game folders use SAF.

2. **Never call `File::Exists` on SAF paths** ([PR #14142](https://github.com/dolphin-emu/dolphin/pull/14142)): Eliminated redundant existence checks that each triggered a full `unmangle()` chain. *"A notable performance improvement for game list scanning due to SAF and Dolphin's 'unmangling' being bad for reasons that unfortunately are entirely predictable."*

3. **Move unmangle off the UI thread** ([PR #11248](https://github.com/dolphin-emu/dolphin/pull/11248)): Cover path resolution was blocking the main thread during game list display.

4. **Batch traversal over individual lookups** ([commit d78277c](https://github.com/dolphin-emu/dolphin/commit/d78277c063)): `doFileSearch()` walks the tree once with `DocumentsContract.buildChildDocumentsUriUsingTree()` rather than unmangling each path individually.

5. **No caching layer**: Despite the performance overhead, Dolphin does not cache SAF metadata. Every operation goes through `ContentResolver`. This simplifies consistency but means each tool call has SAF overhead.

ZeroAI's on-demand-only approach (agent checks the folder only when explicitly asked) sidesteps the worst performance scenarios — we never scan hundreds of files for a game list or resolve cover art paths on the UI thread. A single `shared_folder_list` call on a typical user folder with dozens of files is fast enough.

### Key Dolphin PRs

| PR | Date | Relevance |
|----|------|-----------|
| [#9221](https://github.com/dolphin-emu/dolphin/pull/9221) | 2020-12 | First SAF integration; introduced `ContentHandler.java` and `unmangle()` |
| [#9318](https://github.com/dolphin-emu/dolphin/pull/9318) | 2020-12 | SAF for game folder picker; `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission()` |
| [#9696](https://github.com/dolphin-emu/dolphin/pull/9696) | 2021-10 | **Main scoped storage PR.** Canonical explanation of why SAF is not used for app-internal storage. |
| [#11248](https://github.com/dolphin-emu/dolphin/pull/11248) | 2022-11 | Moved unmangle off UI thread for performance |
| [#11524](https://github.com/dolphin-emu/dolphin/pull/11524) | 2023-03 | Custom `DocumentProvider` — lets file managers browse Dolphin's files (reverse SAF) |
| [#11670](https://github.com/dolphin-emu/dolphin/pull/11670) | 2023-03 | Write mode `"w"` → `"wt"` fix (truncate semantics) |
| [#14142](https://github.com/dolphin-emu/dolphin/pull/14142) | 2025-11 | Eliminated redundant `File::Exists` on SAF paths |
