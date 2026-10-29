package com.example.kitsune.scanner

/**
 * Default implementation of [LibraryScanner].
 *
 * Coordinates [ComicFolderScanner], [ChapterScanner], and [CbzCataloger] to scan the collection.
 * Isolates individual archive and folder failures so that a single corrupted CBZ or malformed file
 * does not abort the entire library scan.
 * Generates structured diagnostics and aggregate metrics per the roadmap (Task 2.6).
 */
class DefaultLibraryScanner(
    private val comicFolderScanner: ComicFolderScanner,
    private val chapterScanner: ChapterScanner,
    private val cbzCataloger: CbzCataloger
) : LibraryScanner {

    override fun scanLibrary(): LibraryScanResult {
        // 1. Temukan seluruh folder komik di bawah direktori Comics
        val foldersResult = comicFolderScanner.discoverComicFolders()
        val comicFolders = when (foldersResult) {
            is ComicFolderDiscoveryResult.RootNotConfigured -> return LibraryScanResult.RootNotConfigured
            is ComicFolderDiscoveryResult.PermissionDenied -> return LibraryScanResult.PermissionDenied(foldersResult.message)
            is ComicFolderDiscoveryResult.ComicsDirectoryNotFound -> return LibraryScanResult.ComicsDirectoryNotFound
            is ComicFolderDiscoveryResult.Error -> return LibraryScanResult.Error(foldersResult.exception)
            is ComicFolderDiscoveryResult.Success -> foldersResult.comicFolders
        }

        if (comicFolders.isEmpty()) {
            return LibraryScanResult.EmptyComicsDirectory
        }

        val scannedComics = mutableListOf<ScannedComic>()
        val allIssues = mutableListOf<ComicScanIssue>()

        var totalChaptersDiscovered = 0
        var totalValidChaptersCount = 0
        var totalCorruptArchivesCount = 0
        var totalEmptyArchivesCount = 0
        var totalRejectedFilesCount = 0
        var comicsMissingFirstChapterCount = 0

        // 2. Iterasi setiap folder komik
        for (folder in comicFolders) {
            val comicIssues = mutableListOf<ComicScanIssue>()
            val scannedChapters = mutableListOf<ScannedChapter>()
            val rejectedFiles = mutableListOf<RejectedChapterFile>()

            var chaptersScannedSuccessfully = false
            when (val chapterResult = chapterScanner.scanChapters(folder.relativePath)) {
                is ChapterDiscoveryResult.RootNotConfigured -> return LibraryScanResult.RootNotConfigured
                is ChapterDiscoveryResult.PermissionDenied -> return LibraryScanResult.PermissionDenied(chapterResult.message)
                is ChapterDiscoveryResult.ComicDirectoryNotFound -> {
                    // Folder komik hilang di filesystem saat scan sedang berlangsung
                    comicIssues.add(ComicScanIssue.ComicDirectoryMissing(folder.relativePath))
                }
                is ChapterDiscoveryResult.Error -> {
                    // I/O error saat scan folder komik individual, isolasi agar tidak membatalkan seluruh library
                    comicIssues.add(
                        ComicScanIssue.ChapterCatalogError(
                            cbzRelativePath = folder.relativePath,
                            exception = chapterResult.exception
                        )
                    )
                }
                is ChapterDiscoveryResult.Success -> {
                    chaptersScannedSuccessfully = true
                    rejectedFiles.addAll(chapterResult.rejectedFiles)
                    totalRejectedFilesCount += chapterResult.rejectedFiles.size
                    totalChaptersDiscovered += chapterResult.chapters.size

                    // 3. Catalog setiap chapter yang ditemukan
                    for (discoveredChapter in chapterResult.chapters) {
                        val catalogResult = cbzCataloger.catalogArchive(
                            cbzRelativePath = discoveredChapter.relativePath,
                            fileMetadata = discoveredChapter.entry
                        )
                        scannedChapters.add(ScannedChapter(discoveredChapter, catalogResult))

                        when (catalogResult) {
                            is CbzCatalogResult.Success -> {
                                totalValidChaptersCount++
                            }
                            is CbzCatalogResult.Empty -> {
                                totalEmptyArchivesCount++
                                val issue = if (discoveredChapter.chapterNumber == 1) {
                                    ComicScanIssue.EmptyFirstChapter(discoveredChapter.relativePath)
                                } else {
                                    ComicScanIssue.EmptyChapter(discoveredChapter.relativePath)
                                }
                                comicIssues.add(issue)
                            }
                            is CbzCatalogResult.CorruptArchive -> {
                                totalCorruptArchivesCount++
                                val issue = if (discoveredChapter.chapterNumber == 1) {
                                    ComicScanIssue.CorruptFirstChapter(discoveredChapter.relativePath, catalogResult.reason)
                                } else {
                                    ComicScanIssue.CorruptChapter(discoveredChapter.relativePath, catalogResult.reason)
                                }
                                comicIssues.add(issue)
                            }
                            is CbzCatalogResult.Error -> {
                                comicIssues.add(
                                    ComicScanIssue.ChapterCatalogError(
                                        cbzRelativePath = discoveredChapter.relativePath,
                                        exception = catalogResult.exception
                                    )
                                )
                            }
                            is CbzCatalogResult.NotFound -> {
                                comicIssues.add(
                                    ComicScanIssue.ChapterFileNotFound(
                                        cbzRelativePath = discoveredChapter.relativePath
                                    )
                                )
                            }
                            is CbzCatalogResult.PermissionDenied -> {
                                return LibraryScanResult.PermissionDenied(catalogResult.message)
                            }
                            is CbzCatalogResult.RootNotConfigured -> {
                                return LibraryScanResult.RootNotConfigured
                            }
                        }
                    }
                }
            }

            // 4. Periksa ketersediaan Chapter 1 dan chapter valid hanya jika pembacaan direktori berhasil
            if (chaptersScannedSuccessfully) {
                val hasValidChapters = scannedChapters.any { it.isValid }
                if (!hasValidChapters) {
                    comicIssues.add(ComicScanIssue.NoValidChapters(folder.relativePath))
                }

                val hasFirstChapter = scannedChapters.any { it.discoveredChapter.chapterNumber == 1 }
                if (!hasFirstChapter) {
                    comicIssues.add(ComicScanIssue.MissingFirstChapter(folder.relativePath))
                    comicsMissingFirstChapterCount++
                }
            }

            allIssues.addAll(comicIssues)
            scannedComics.add(
                ScannedComic(
                    folder = folder,
                    chapters = scannedChapters,
                    rejectedFiles = rejectedFiles,
                    issues = comicIssues
                )
            )
        }

        val validComicsCount = scannedComics.count { it.hasValidChapters }

        val summary = ScanSummary(
            totalComicFoldersDiscovered = comicFolders.size,
            validComicsCount = validComicsCount,
            totalChaptersDiscovered = totalChaptersDiscovered,
            totalValidChaptersCount = totalValidChaptersCount,
            totalCorruptArchivesCount = totalCorruptArchivesCount,
            totalEmptyArchivesCount = totalEmptyArchivesCount,
            totalRejectedFilesCount = totalRejectedFilesCount,
            comicsMissingFirstChapterCount = comicsMissingFirstChapterCount
        )

        return LibraryScanResult.Success(
            comics = scannedComics,
            summary = summary,
            issues = allIssues
        )
    }
}

