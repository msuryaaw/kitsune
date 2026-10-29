package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageEntry

/**
 * Representation of a discovered chapter file directly inside a comic directory.
 *
 * @param fileName Original file name on filesystem (e.g. "Chapter 1.cbz").
 * @param relativePath Relative path from storage root (e.g. "Comics/One Piece/Chapter 1.cbz").
 * @param chapterNumber Numerical identifier extracted from file name (e.g. 1).
 * @param entry The underlying [StorageEntry] record.
 */
data class DiscoveredChapter(
    val fileName: String,
    val relativePath: String,
    val chapterNumber: Int,
    val entry: StorageEntry
)

/**
 * Diagnostic record for files in a comic directory that do not adhere to the chapter convention.
 *
 * @param fileName The original file name.
 * @param relativePath Relative path from storage root.
 * @param reason Reason why the file was rejected as a chapter.
 */
data class RejectedChapterFile(
    val fileName: String,
    val relativePath: String,
    val reason: String
)

/**
 * Result state when scanning chapters in a comic folder.
 */
sealed class ChapterDiscoveryResult {
    object RootNotConfigured : ChapterDiscoveryResult()
    data class PermissionDenied(val message: String) : ChapterDiscoveryResult()
    object ComicDirectoryNotFound : ChapterDiscoveryResult()
    data class Success(
        val chapters: List<DiscoveredChapter>,
        val rejectedFiles: List<RejectedChapterFile>
    ) : ChapterDiscoveryResult()
    data class Error(val exception: Throwable) : ChapterDiscoveryResult()
}

/**
 * Contract for scanning and parsing chapter files directly inside a comic directory.
 */
interface ChapterScanner {

    /**
     * Lists direct children of [comicRelativePath], identifies valid chapters via [ChapterFileNameParser],
     * and records rejected files deterministically. Does not modify any file names on disk.
     */
    fun scanChapters(comicRelativePath: String): ChapterDiscoveryResult
}

