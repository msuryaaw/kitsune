package com.example.kitsune.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Storage helper for SAF permission operations and accessibility checks.
 */
object SafStorageHelper {

    /**
     * Determines whether [uri] is currently accessible, granted persistent read permission,
     * and actually represents a valid directory.
     * Queries both COLUMN_DOCUMENT_ID and COLUMN_MIME_TYPE and ensures cursor is safely closed.
     */
    fun checkRootAccess(context: Context, uri: Uri): RootFolderState {
        val resolver = context.contentResolver
        val persisted = resolver.persistedUriPermissions.firstOrNull {
            it.uri == uri && it.isReadPermission
        }

        if (persisted == null) {
            return RootFolderState.PermissionLost(
                uriString = uri.toString(),
                reason = "Izin baca persisten tidak ditemukan atau telah dicabut oleh sistem."
            )
        }

        return try {
            val docUri = if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    DocumentsContract.getTreeDocumentId(uri)
                )
            } else {
                uri
            }

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            val cursor = resolver.query(docUri, projection, null, null, null)
                ?: return RootFolderState.Inaccessible(
                    uriString = uri.toString(),
                    error = "Penyedia penyimpanan mengembalikan null (folder tidak ditemukan atau tidak tersedia)."
                )

            cursor.use { c ->
                val hasRow = c.moveToFirst()
                val docIdIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeTypeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                val docId = if (docIdIndex >= 0 && hasRow) c.getString(docIdIndex) else null
                val mimeType = if (mimeTypeIndex >= 0 && hasRow) c.getString(mimeTypeIndex) else null

                val evaluation = SafPermissionDecider.evaluateDocumentMetadata(
                    hasRow = hasRow,
                    documentId = docId,
                    mimeType = mimeType,
                    expectedDirectoryMime = DocumentsContract.Document.MIME_TYPE_DIR
                )

                when (evaluation) {
                    is DocumentEvaluationResult.ValidDirectory -> {
                        RootFolderState.Ready(uri.toString())
                    }
                    is DocumentEvaluationResult.NotADirectory -> {
                        RootFolderState.Inaccessible(
                            uriString = uri.toString(),
                            error = "URI yang dipilih bukan merupakan direktori/folder: ${evaluation.reason}"
                        )
                    }
                    is DocumentEvaluationResult.Invalid -> {
                        RootFolderState.Inaccessible(
                            uriString = uri.toString(),
                            error = "Metadata dokumen tidak valid: ${evaluation.error}"
                        )
                    }
                }
            }
        } catch (se: SecurityException) {
            RootFolderState.PermissionLost(
                uriString = uri.toString(),
                reason = "Akses ditolak oleh penyedia penyimpanan: ${se.localizedMessage ?: "SecurityException"}"
            )
        } catch (e: Exception) {
            RootFolderState.Inaccessible(
                uriString = uri.toString(),
                error = "Gagal memverifikasi dokumen folder: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Takes persistable permissions for [uri] strictly respecting flags granted in [resultIntent].
     * Uses [SafPermissionDecider] to validate that persistable read permission was actually granted.
     */
    fun takePersistablePermissions(
        resolver: ContentResolver,
        uri: Uri,
        resultIntent: Intent?
    ): Result<Unit> {
        val intentFlags = resultIntent?.flags ?: 0
        val flagsResult = SafPermissionDecider.computeTakePersistableFlags(intentFlags)

        val flagsToTake = flagsResult.getOrElse { return Result.failure(it) }

        return try {
            resolver.takePersistableUriPermission(uri, flagsToTake)

            val hasPersistedRead = resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }

            if (hasPersistedRead) {
                Result.success(Unit)
            } else {
                Result.failure(
                    SecurityException("Izin diminta tetapi tidak tercatat dalam persistedUriPermissions.")
                )
            }
        } catch (se: SecurityException) {
            val fallbackFlags = SafPermissionDecider.computeFallbackFlags(flagsToTake)
            if (fallbackFlags != null) {
                try {
                    resolver.takePersistableUriPermission(uri, fallbackFlags)
                    val hasPersistedRead = resolver.persistedUriPermissions.any {
                        it.uri == uri && it.isReadPermission
                    }
                    if (hasPersistedRead) {
                        Result.success(Unit)
                    } else {
                        Result.failure(
                            SecurityException("Fallback izin baca tidak tercatat sebagai persisten.")
                        )
                    }
                } catch (fallbackSe: SecurityException) {
                    Result.failure(fallbackSe)
                }
            } else {
                Result.failure(se)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Releases whatever persistable permission is currently held for [uri].
     */
    fun releasePersistablePermissions(
        resolver: ContentResolver,
        uri: Uri
    ): Result<Unit> {
        val existing = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            ?: return Result.success(Unit)

        val releaseFlags = SafPermissionDecider.computeReleaseFlags(
            isReadHeld = existing.isReadPermission,
            isWriteHeld = existing.isWritePermission
        )

        return try {
            if (releaseFlags != 0) {
                resolver.releasePersistableUriPermission(uri, releaseFlags)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
