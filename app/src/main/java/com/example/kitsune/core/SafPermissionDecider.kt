package com.example.kitsune.core

import android.content.Intent

/**
 * Pure domain/business logic for SAF permission decisions and document evaluation.
 * Completely free of android.os / runtime IPC dependencies so it can be rigorously unit tested on JVM.
 */
object SafPermissionDecider {

    /**
     * Evaluates whether the picker result intent contains the necessary flags
     * to take persistable URI permissions.
     *
     * @param intentFlags flags received from ActivityResult Intent.flags
     * @return Result containing the exact mode flags to request with takePersistableUriPermission,
     *         or Failure if persistable read permission was not granted by picker.
     */
    fun computeTakePersistableFlags(intentFlags: Int): Result<Int> {
        val hasPersistable = (intentFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
        if (!hasPersistable) {
            return Result.failure(
                SecurityException("Intent hasil picker tidak menyertakan FLAG_GRANT_PERSISTABLE_URI_PERMISSION.")
            )
        }

        val hasRead = (intentFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        if (!hasRead) {
            return Result.failure(
                SecurityException("Intent hasil picker tidak memberikan izin baca (FLAG_GRANT_READ_URI_PERMISSION).")
            )
        }

        val hasWrite = (intentFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        val flagsToTake = if (hasWrite) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        } else {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        return Result.success(flagsToTake)
    }

    /**
     * Determines whether fallback to read-only is valid when taking read+write failed.
     *
     * @param originalAttemptedFlags the flags previously computed and attempted
     * @return fallback flags (FLAG_GRANT_READ_URI_PERMISSION) if original had write, or null if no fallback possible.
     */
    fun computeFallbackFlags(originalAttemptedFlags: Int): Int? {
        val hadWrite = (originalAttemptedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        val hadRead = (originalAttemptedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        return if (hadWrite && hadRead) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        } else {
            null
        }
    }

    /**
     * Computes the flags to release based on permissions currently held.
     */
    fun computeReleaseFlags(isReadHeld: Boolean, isWriteHeld: Boolean): Int {
        var flags = 0
        if (isReadHeld) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (isWriteHeld) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return flags
    }

    /**
     * Evaluates document query metadata to determine if it is a valid directory.
     *
     * @param hasRow true if cursor.moveToFirst() succeeded
     * @param documentId the COLUMN_DOCUMENT_ID string
     * @param mimeType the COLUMN_MIME_TYPE string
     * @param expectedDirectoryMime the MIME type constant for DocumentsContract directories
     */
    fun evaluateDocumentMetadata(
        hasRow: Boolean,
        documentId: String?,
        mimeType: String?,
        expectedDirectoryMime: String
    ): DocumentEvaluationResult {
        if (!hasRow) {
            return DocumentEvaluationResult.Invalid("Cursor kosong, tidak ada baris metadata dokumen.")
        }
        if (documentId.isNullOrBlank()) {
            return DocumentEvaluationResult.Invalid("Document ID kosong atau null.")
        }
        if (mimeType != expectedDirectoryMime) {
            return DocumentEvaluationResult.NotADirectory("MIME type '$mimeType' bukan direktori ($expectedDirectoryMime).")
        }
        return DocumentEvaluationResult.ValidDirectory
    }
}

sealed class DocumentEvaluationResult {
    object ValidDirectory : DocumentEvaluationResult()
    data class NotADirectory(val reason: String) : DocumentEvaluationResult()
    data class Invalid(val error: String) : DocumentEvaluationResult()
}

