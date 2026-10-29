package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageEntry

/**
 * Diagnostic information regarding problems encountered for a specific comic folder.
 */
sealed class ComicScanIssue {
    /**
     * Comic folder was removed or not found when attempting to scan its chapters.
     */
    data class ComicDirectoryMissing(
        val comicRelativePath: String
    ) : ComicScanIssue()

    /**
     * A chapter file was discovered but was missing when cataloging its contents.
     */
    data class ChapterFileNotFound(
        val cbzRelativePath: String
    ) : ComicScanIssue()

    /**
     * Comic folder does not contain any valid chapters matching the naming convention.
     */
    data class NoValidChapters(
        val comicRelativePath: String
    ) : ComicScanIssue()

    /**
     * Comic folder does not contain `Chapter 1.cbz` required for standard cover resolution.
     */
    data class MissingFirstChapter(
        val comicRelativePath: String
    ) : ComicScanIssue()

    /**
     * `Chapter 1.cbz` exists but is corrupt or not a valid archive.
     */
    data class CorruptFirstChapter(
        val cbzRelativePath: String,
        val reason: String
    ) : ComicScanIssue()

    /**
     * `Chapter 1.cbz` exists and is a valid archive, but contains no supported image pages.
     */
    data class EmptyFirstChapter(
        val cbzRelativePath: String
    ) : ComicScanIssue()

    /**
     * A chapter in the comic folder failed cataloging due to archive corruption.
     */
    data class CorruptChapter(
        val cbzRelativePath: String,
        val reason: String
    ) : ComicScanIssue()

    /**
     * A chapter in the comic folder contains no supported image pages.
     */
    data class EmptyChapter(
        val cbzRelativePath: String
    ) : ComicScanIssue()

    /**
     * An I/O error occurred while inspecting a chapter archive.
     */
    data class ChapterCatalogError(
        val cbzRelativePath: String,
        val exception: Throwable
    ) : ComicScanIssue()
}

/**
 * Inspected details of a chapter in a scanned comic.
 *
 * @param discoveredChapter Underlying discovered chapter file.
 * @param catalogResult Result of cataloging the chapter's archive entries.
 */
data class ScannedChapter(
    val discoveredChapter: DiscoveredChapter,
    val catalogResult: CbzCatalogResult
) {
    val isValid: Boolean get() = catalogResult is CbzCatalogResult.Success
    val pageCount: Int get() = (catalogResult as? CbzCatalogResult.Success)?.pages?.size ?: 0
}

/**
 * Comprehensive scan report for a single comic folder.
 *
 * @param folder The underlying comic folder.
 * @param chapters All parsed chapters ordered by natural order.
 * @param rejectedFiles Files or subdirectories inside the comic folder that were not valid chapters.
 * @param issues Diagnostic issues identified during inspection.
 */
data class ScannedComic(
    val folder: DiscoveredComicFolder,
    val chapters: List<ScannedChapter>,
    val rejectedFiles: List<RejectedChapterFile>,
    val issues: List<ComicScanIssue>
) {
    /**
     * Title derived from the comic folder.
     */
    val title: String get() = folder.defaultTitle

    /**
     * Relative path of the comic folder.
     */
    val relativePath: String get() = folder.relativePath

    /**
     * Returns true if the comic folder has at least one valid readable chapter.
     */
    val hasValidChapters: Boolean get() = chapters.any { it.isValid }

    /**
     * Returns Chapter 1 if present.
     */
    val firstChapter: ScannedChapter? get() = chapters.firstOrNull { it.discoveredChapter.chapterNumber == 1 }

    /**
     * Path of the cover image entry in Chapter 1, if Chapter 1 is valid and has pages.
     */
    val coverEntryPath: String? get() = (firstChapter?.catalogResult as? CbzCatalogResult.Success)?.pages?.firstOrNull()?.entryPath
}

/**
 * Aggregate summary metrics of the scan operation.
 *
 * @param totalComicFoldersDiscovered Total comic directories detected directly under `Comics`.
 * @param validComicsCount Total comics with at least one valid chapter.
 * @param totalChaptersDiscovered Total chapter files matching convention across all comics.
 * @param totalValidChaptersCount Total chapters that successfully cataloged image pages.
 * @param totalCorruptArchivesCount Total chapter archives that were corrupt.
 * @param totalEmptyArchivesCount Total chapter archives that contained no supported image pages.
 * @param totalRejectedFilesCount Total non-chapter files or subdirectories rejected inside comic folders.
 * @param comicsMissingFirstChapterCount Total comics missing `Chapter 1.cbz`.
 */
data class ScanSummary(
    val totalComicFoldersDiscovered: Int,
    val validComicsCount: Int,
    val totalChaptersDiscovered: Int,
    val totalValidChaptersCount: Int,
    val totalCorruptArchivesCount: Int,
    val totalEmptyArchivesCount: Int,
    val totalRejectedFilesCount: Int,
    val comicsMissingFirstChapterCount: Int
)

/**
 * Result state of the collection scan operation.
 */
sealed class LibraryScanResult {
    /**
     * Storage root has not been configured in preferences.
     */
    object RootNotConfigured : LibraryScanResult()

    /**
     * Storage access permission is missing or revoked.
     */
    data class PermissionDenied(val message: String) : LibraryScanResult()

    /**
     * The `Comics` directory was not found under the root.
     */
    object ComicsDirectoryNotFound : LibraryScanResult()

    /**
     * The `Comics` directory exists but contains no comic folders.
     */
    object EmptyComicsDirectory : LibraryScanResult()

    /**
     * The scan completed successfully across the collection.
     * Contains detailed scanned comic objects, overall summary metrics, and any global or comic-specific issues.
     */
    data class Success(
        val comics: List<ScannedComic>,
        val summary: ScanSummary,
        val issues: List<ComicScanIssue>
    ) : LibraryScanResult()

    /**
     * An unexpected I/O error aborted the top-level scan.
     */
    data class Error(val exception: Throwable) : LibraryScanResult()
}

/**
 * Contract for executing a full library scan and producing diagnostic reports.
 */
interface LibraryScanner {

    /**
     * Executes a complete scan of the `Comics` directory:
     * 1. Discovers comic folders via [ComicFolderScanner].
     * 2. Scans chapters for each comic folder via [ChapterScanner].
     * 3. Catalogs each chapter CBZ via [CbzCataloger].
     * 4. Assembles diagnostic issues and summary metrics without failing the entire scan
     *    on individual file or chapter errors.
     */
    fun scanLibrary(): LibraryScanResult
}

