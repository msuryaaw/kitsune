package com.example.kitsune.data

/**
 * Diagnostic result of checking if a chapter CBZ file is available on the filesystem before opening reader.
 */
sealed interface ChapterAvailabilityResult {
    /**
     * File is available on the filesystem and accessible.
     */
    object Available : ChapterAvailabilityResult

    /**
     * Chapter file was not found or was removed from storage.
     */
    object Missing : ChapterAvailabilityResult

    /**
     * Storage access permission was revoked or lost for the chapter's root.
     */
    data class PermissionDenied(val message: String) : ChapterAvailabilityResult

    /**
     * An unexpected I/O or storage error occurred while inspecting the file.
     */
    data class Error(val exception: Throwable) : ChapterAvailabilityResult
}
