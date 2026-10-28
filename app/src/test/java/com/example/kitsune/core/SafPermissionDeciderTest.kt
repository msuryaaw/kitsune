package com.example.kitsune.core

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafPermissionDeciderTest {

    @Test
    fun computeTakePersistableFlags_withoutPersistableFlag_returnsFailure() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val result = SafPermissionDecider.computeTakePersistableFlags(flags)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(result.exceptionOrNull()?.message?.contains("FLAG_GRANT_PERSISTABLE_URI_PERMISSION") == true)
    }

    @Test
    fun computeTakePersistableFlags_withoutReadFlag_returnsFailure() {
        val flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val result = SafPermissionDecider.computeTakePersistableFlags(flags)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertTrue(result.exceptionOrNull()?.message?.contains("FLAG_GRANT_READ_URI_PERMISSION") == true)
    }

    @Test
    fun computeTakePersistableFlags_withPersistableAndRead_requestsReadOnly() {
        val flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        val result = SafPermissionDecider.computeTakePersistableFlags(flags)

        assertTrue(result.isSuccess)
        val modeFlags = result.getOrThrow()
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, modeFlags)
        assertFalse((modeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
    }

    @Test
    fun computeTakePersistableFlags_withPersistableReadAndWrite_requestsReadAndWrite() {
        val flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val result = SafPermissionDecider.computeTakePersistableFlags(flags)

        assertTrue(result.isSuccess)
        val modeFlags = result.getOrThrow()
        val expected = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals(expected, modeFlags)
    }

    @Test
    fun computeFallbackFlags_fromReadWrite_returnsReadOnly() {
        val combinedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val fallback = SafPermissionDecider.computeFallbackFlags(combinedFlags)

        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, fallback)
    }

    @Test
    fun computeFallbackFlags_fromReadOnly_returnsNull() {
        val readOnlyFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val fallback = SafPermissionDecider.computeFallbackFlags(readOnlyFlags)

        assertNull(fallback)
    }

    @Test
    fun computeReleaseFlags_matchesHeldPermissions() {
        assertEquals(0, SafPermissionDecider.computeReleaseFlags(isReadHeld = false, isWriteHeld = false))
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            SafPermissionDecider.computeReleaseFlags(isReadHeld = true, isWriteHeld = false)
        )
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            SafPermissionDecider.computeReleaseFlags(isReadHeld = true, isWriteHeld = true)
        )
    }

    @Test
    fun evaluateDocumentMetadata_validDirectory_returnsValidDirectory() {
        val result = SafPermissionDecider.evaluateDocumentMetadata(
            hasRow = true,
            documentId = "primary:Kitsune",
            mimeType = "vnd.android.document/directory",
            expectedDirectoryMime = "vnd.android.document/directory"
        )
        assertTrue(result is DocumentEvaluationResult.ValidDirectory)
    }

    @Test
    fun evaluateDocumentMetadata_notADirectory_returnsNotADirectory() {
        val result = SafPermissionDecider.evaluateDocumentMetadata(
            hasRow = true,
            documentId = "primary:Kitsune/comic.cbz",
            mimeType = "application/x-cbz",
            expectedDirectoryMime = "vnd.android.document/directory"
        )
        assertTrue(result is DocumentEvaluationResult.NotADirectory)
    }

    @Test
    fun evaluateDocumentMetadata_emptyCursorOrBlankDocId_returnsInvalid() {
        val emptyCursorResult = SafPermissionDecider.evaluateDocumentMetadata(
            hasRow = false,
            documentId = null,
            mimeType = null,
            expectedDirectoryMime = "vnd.android.document/directory"
        )
        assertTrue(emptyCursorResult is DocumentEvaluationResult.Invalid)

        val blankDocIdResult = SafPermissionDecider.evaluateDocumentMetadata(
            hasRow = true,
            documentId = "   ",
            mimeType = "vnd.android.document/directory",
            expectedDirectoryMime = "vnd.android.document/directory"
        )
        assertTrue(blankDocIdResult is DocumentEvaluationResult.Invalid)
    }
}

