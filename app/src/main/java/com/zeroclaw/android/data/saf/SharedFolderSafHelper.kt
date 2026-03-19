/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
class SharedFolderSafHelper(
    private val context: Context,
) {
    private val json = Json { prettyPrint = false }

    /**
     * Lists immediate children of a path within the shared folder.
     *
     * @param rootUri Persisted SAF tree URI for the shared folder root.
     * @param path Relative path from the root (e.g., `"subfolder"` or `"/"`).
     * @return JSON array of [FolderEntry] objects.
     * @throws IOException if the folder is not accessible.
     */
    fun list(
        rootUri: Uri,
        path: String,
    ): String {
        val targetUri =
            resolvePath(rootUri, path)
                ?: return errorJson("Path not found: $path")

        val docId = DocumentsContract.getDocumentId(targetUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, docId)

        val entries = mutableListOf<FolderEntry>()
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

        try {
            context.contentResolver
                .query(childrenUri, projection, null, null, null)
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
    fun read(
        rootUri: Uri,
        path: String,
    ): String {
        val targetUri =
            resolvePath(rootUri, path)
                ?: return errorJson("File not found: $path")

        val mimeType = context.contentResolver.getType(targetUri) ?: "application/octet-stream"
        val isText =
            mimeType.startsWith("text/") ||
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
                val created =
                    createDirectories(rootUri, path)
                        ?: return errorJson("Failed to create directory: $path")

                @Suppress("UNUSED_VARIABLE")
                val name = DocumentsContract.getDocumentId(created)
                return json.encodeToString(WriteResult(path, 0))
            }

            val bytes =
                if (isBase64 && content != null) {
                    android.util.Base64.decode(content, android.util.Base64.DEFAULT)
                } else {
                    content?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                }

            if (bytes.size > MAX_WRITE_BYTES) {
                return errorJson("Write too large (${bytes.size / (1024 * 1024)}MB). Limit: ${MAX_WRITE_BYTES / (1024 * 1024)}MB.")
            }

            val segments = splitPath(path)
            val fileName =
                segments.lastOrNull()
                    ?: return errorJson("Invalid path: $path")
            val parentPath = segments.dropLast(1)

            val parentUri =
                if (parentPath.isEmpty()) {
                    documentUriFromTree(rootUri)
                } else {
                    createDirectories(rootUri, parentPath.joinToString("/"))
                        ?: return errorJson("Failed to create parent directories for: $path")
                }

            val existingUri = findChild(rootUri, parentUri, fileName)
            val targetUri =
                if (existingUri != null) {
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
    private fun resolvePath(
        rootUri: Uri,
        path: String,
    ): Uri? {
        val segments = splitPath(path)
        if (segments.isEmpty()) return documentUriFromTree(rootUri)

        var current = documentUriFromTree(rootUri)
        for (segment in segments) {
            current = findChild(rootUri, current, segment) ?: return null
        }
        return current
    }

    /** Finds a child document by display name within a parent. */
    private fun findChild(
        treeUri: Uri,
        parentUri: Uri,
        childName: String,
    ): Uri? {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
        try {
            context.contentResolver
                .query(childrenUri, projection, null, null, null)
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
    private fun createDirectories(
        rootUri: Uri,
        path: String,
    ): Uri? {
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

    private fun splitPath(path: String): List<String> = path.trim('/').split('/').filter { it.isNotEmpty() && !it.contains('/') }

    private fun formatTimestamp(millis: Long): String {
        if (millis == 0L) return ""
        val instant = java.time.Instant.ofEpochMilli(millis)
        return java.time.format.DateTimeFormatter.ISO_INSTANT
            .format(instant)
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MIME_MAP[ext] ?: "application/octet-stream"
    }

    private fun errorJson(message: String): String = json.encodeToString(ErrorResult(message))

    @Serializable
    private data class FolderEntry(
        val name: String,
        val type: String,
        @SerialName("size_bytes")
        val sizeBytes: Long,
        @SerialName("last_modified")
        val lastModified: String,
    )

    @Serializable
    private data class TextReadResult(
        val type: String,
        val content: String,
    )

    @Serializable
    private data class BinaryReadResult(
        val type: String,
        @SerialName("mime_type")
        val mimeType: String,
        @SerialName("content_base64")
        val contentBase64: String,
    )

    @Serializable
    private data class WriteResult(
        val path: String,
        @SerialName("bytes_written")
        val bytesWritten: Long,
    )

    @Serializable
    private data class ErrorResult(
        val error: String,
    )

    companion object {
        private const val TAG = "SharedFolderSaf"
        private const val MAX_TEXT_READ_BYTES = 10L * 1024 * 1024
        private const val MAX_BINARY_READ_BYTES = 2L * 1024 * 1024
        private const val MAX_WRITE_BYTES = 50L * 1024 * 1024

        private val TEXT_MIME_TYPES =
            setOf(
                "application/json",
                "application/xml",
                "application/javascript",
                "application/x-yaml",
                "application/toml",
            )

        private val MIME_MAP =
            mapOf(
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
