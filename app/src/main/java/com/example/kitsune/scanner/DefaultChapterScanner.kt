package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult

/**
 * Default implementation of [ChapterScanner].
 *
 * Scans direct children of a given comic directory using [StorageGateway.listChildren].
 * Filters and parses chapter file names using [ChapterFileNameParser].
 * Rejects subdirectories, non-CBZ files, and malformed chapter names deterministically
 * without modifying any file names on disk.
 */
class DefaultChapterScanner(
    private val storageGateway: StorageGateway
) : ChapterScanner {

    override fun scanChapters(comicRelativePath: String): ChapterDiscoveryResult {
        // 1. Validasi status root storage
        when (val rootState = storageGateway.getRootState()) {
            is StorageResult.RootNotConfigured -> return ChapterDiscoveryResult.RootNotConfigured
            is StorageResult.PermissionDenied -> return ChapterDiscoveryResult.PermissionDenied(rootState.message)
            is StorageResult.Error -> return ChapterDiscoveryResult.Error(rootState.exception)
            is StorageResult.NotFound -> return ChapterDiscoveryResult.RootNotConfigured
            is StorageResult.Success -> {
                // Root siap, lanjutkan scan chapter
            }
        }

        // 2. Ambil entitas anak langsung dari direktori komik
        val childrenResult = storageGateway.listChildren(comicRelativePath)
        return when (childrenResult) {
            is StorageResult.Success -> {
                val validChapters = mutableListOf<DiscoveredChapter>()
                val rejectedFiles = mutableListOf<RejectedChapterFile>()

                for (entry in childrenResult.data) {
                    // Abaikan direktori (bab harus langsung berupa file CBZ, bukan subfolder)
                    if (entry.isDirectory) {
                        rejectedFiles.add(
                            RejectedChapterFile(
                                fileName = entry.name,
                                relativePath = entry.relativePath,
                                reason = "Direct subdirectories inside comic folders are ignored"
                            )
                        )
                        continue
                    }

                    when (val parseResult = ChapterFileNameParser.parse(entry.name)) {
                        is ChapterParseResult.Valid -> {
                            validChapters.add(
                                DiscoveredChapter(
                                    fileName = entry.name,
                                    relativePath = entry.relativePath,
                                    chapterNumber = parseResult.chapterNumber,
                                    entry = entry
                                )
                            )
                        }
                        is ChapterParseResult.Invalid -> {
                            rejectedFiles.add(
                                RejectedChapterFile(
                                    fileName = entry.name,
                                    relativePath = entry.relativePath,
                                    reason = parseResult.reason
                                )
                            )
                        }
                    }
                }

                validChapters.sortWith(ChapterNaturalComparator)

                ChapterDiscoveryResult.Success(
                    chapters = validChapters,
                    rejectedFiles = rejectedFiles
                )
            }
            is StorageResult.RootNotConfigured -> ChapterDiscoveryResult.RootNotConfigured
            is StorageResult.PermissionDenied -> ChapterDiscoveryResult.PermissionDenied(childrenResult.message)
            is StorageResult.NotFound -> ChapterDiscoveryResult.ComicDirectoryNotFound
            is StorageResult.Error -> ChapterDiscoveryResult.Error(childrenResult.exception)
        }
    }
}

