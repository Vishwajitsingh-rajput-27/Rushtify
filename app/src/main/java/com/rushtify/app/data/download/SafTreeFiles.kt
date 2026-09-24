package com.rushtify.app.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream

/**
 * Storage Access Framework helpers for the user-chosen custom download
 * folder (Settings → Downloads → Download location).
 *
 * The folder is picked with ACTION_OPEN_DOCUMENT_TREE and its Uri is
 * persisted with takePersistableUriPermission, so it keeps working after
 * reboots and handles SD cards without hard-coded "/storage/XXXX-XXXX/"
 * paths. Framework DocumentsContract APIs only — no extra dependencies.
 */
object SafTreeFiles {

    /** Parses a persisted tree-uri string; null when blank or malformed. */
    fun parseTreeUri(uriString: String?): Uri? {
        if (uriString.isNullOrBlank()) return null
        return runCatching { Uri.parse(uriString.trim()) }
            .getOrNull()
            ?.takeIf { it.scheme == "content" }
    }

    /** True when we still hold a persisted read+write grant for the tree
     *  (survives reboots; false after revocation or SD-card removal). */
    fun hasPersistedAccess(context: Context, uriString: String?): Boolean {
        val treeUri = parseTreeUri(uriString) ?: return false
        return runCatching {
            context.contentResolver.persistedUriPermissions.any { grant ->
                grant.uri == treeUri && grant.isReadPermission && grant.isWritePermission
            }
        }.getOrDefault(false)
    }

    /** Persists the read+write grant from an OpenDocumentTree result.
     *  Returns false when the provider did not grant persistable access. */
    fun takePersistedAccess(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** Drops our persisted grant (used when resetting to the default). */
    fun releasePersistedAccess(context: Context, uriString: String?) {
        val treeUri = parseTreeUri(uriString) ?: return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** Human-readable label for settings, e.g. "SD card › Music/Rushtify".
     *  Null when the Uri is blank, malformed, or no longer accessible. */
    fun describeLocation(context: Context, uriString: String?): String? {
        val treeUri = parseTreeUri(uriString) ?: return null
        if (!hasPersistedAccess(context, uriString)) return null
        return runCatching {
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val volume = treeId.substringBefore(':')
            val relative = treeId.substringAfter(':', "").trim('/').trim()
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
            val dirName = documentDisplayName(context, docUri)
            when {
                volume == "primary" && relative.isNotBlank() -> "Internal storage › $relative"
                volume == "primary" -> "Internal storage › ${dirName.orEmpty()}".trimEnd(' ', '›')
                relative.isNotBlank() -> "SD card › $relative"
                else -> "SD card › ${dirName.orEmpty()}".trimEnd(' ', '›').ifBlank { "SD card" }
            }
        }.getOrNull()
    }

    /**
     * Creates (or replaces) [filename] inside the tree, creating any missing
     * [subpathSegments] folders first. Mirrors the MediaStore path's
     * de-duplication: a same-named file is deleted before creating the new
     * one so providers can't append "(1)" suffixes. Returns the new
     * document Uri, or null when anything fails.
     */
    fun createFile(
        context: Context,
        treeUri: Uri,
        subpathSegments: List<String>,
        filename: String,
        mimeType: String,
    ): Uri? = runCatching {
        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        var dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        for (raw in subpathSegments) {
            val segment = raw.trim().trim('/', '\\')
            if (segment.isBlank() || segment == "." || segment == "..") continue
            dirUri = findChild(resolver, treeUri, dirUri, segment)
                ?: createDirectory(resolver, dirUri, segment)
                ?: error("Cannot create folder \"$segment\" in the selected location")
        }
        findChild(resolver, treeUri, dirUri, filename)?.let { existing ->
            runCatching { DocumentsContract.deleteDocument(resolver, existing) }
        }
        DocumentsContract.createDocument(
            resolver,
            dirUri,
            mimeType.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            filename,
        ) ?: error("Storage provider refused file creation")
    }.getOrNull()

    /** Opens a truncating write stream for a document created by [createFile]. */
    fun openWriteStream(context: Context, fileUri: Uri): OutputStream? {
        val resolver = context.contentResolver
        return runCatching { resolver.openOutputStream(fileUri, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(fileUri, "w") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(fileUri) }.getOrNull()
    }

    private fun findChild(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        displayName: String,
    ): Uri? = runCatching {
        val parentId = DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idCol < 0 || nameCol < 0) return@runCatching null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == displayName) {
                    return@runCatching DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        cursor.getString(idCol),
                    )
                }
            }
            null
        }
    }.getOrNull()

    private fun createDirectory(
        resolver: android.content.ContentResolver,
        parentDocUri: Uri,
        displayName: String,
    ): Uri? = runCatching {
        DocumentsContract.createDocument(
            resolver,
            parentDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            displayName,
        )
    }.getOrNull()

    private fun documentDisplayName(context: Context, docUri: Uri): String? = runCatching {
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}
