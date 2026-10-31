package com.example.kitsune.reader

import android.graphics.Bitmap

/**
 * Metadata for a single page entry in a CBZ archive.
 */
data class PageMetadata(
    val pageIndex: Int,
    val pageNumber: Int = pageIndex + 1,
    val entryName: String,
    val normalizedPath: String,
    val sizeBytes: Long? = null
)

/**
 * Immutable page index for an opened chapter archive.
 */
data class ChapterPageIndex(
    val chapterId: Long,
    val pages: List<PageMetadata>
) {
    val totalPages: Int get() = pages.size
}

/**
 * Result state of attempting to build a page index from a CBZ archive.
 */
sealed interface PageIndexResult {
    /**
     * Page index built successfully.
     */
    data class Success(val pageIndex: ChapterPageIndex) : PageIndexResult

    /**
     * Archive contains zero valid image pages.
     */
    object NoImagesFound : PageIndexResult

    /**
     * Archive is corrupt, truncated, or not a valid ZIP file.
     */
    data class CorruptArchive(val message: String) : PageIndexResult

    /**
     * Safety limit exceeded (e.g. entry count, page count, or entry size limit).
     */
    data class LimitExceeded(val reason: String) : PageIndexResult

    /**
     * An unexpected I/O error occurred during indexing.
     */
    data class StorageError(val exception: Throwable) : PageIndexResult
}

/**
 * Result state of attempting to decode a single page bitmap from a CBZ archive.
 */
sealed interface PageDecodeResult {
    /**
     * Page decoded successfully.
     */
    data class Success(
        val pageIndex: Int,
        val bitmap: Bitmap,
        val width: Int,
        val height: Int
    ) : PageDecodeResult

    /**
     * Requested page index was out of bounds or entry was missing.
     */
    data class PageNotFound(val pageIndex: Int) : PageDecodeResult

    /**
     * Page image entry was corrupt or could not be decoded by BitmapFactory.
     */
    data class ImageCorrupt(val pageIndex: Int, val message: String) : PageDecodeResult

    /**
     * Decoder has been closed and new decode requests are rejected.
     */
    object ArchiveClosed : PageDecodeResult

    /**
     * An unexpected error or size limit violation occurred during decoding.
     */
    data class Error(val exception: Throwable) : PageDecodeResult
}
