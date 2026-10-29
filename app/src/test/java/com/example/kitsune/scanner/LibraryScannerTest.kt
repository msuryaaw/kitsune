package com.example.kitsune.scanner

import com.example.kitsune.storage.FakeStorageGateway
import com.example.kitsune.storage.StorageEntry
import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryScannerTest {

    private fun createZipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun createLibraryScanner(gateway: StorageGateway): DefaultLibraryScanner {
        val detector = DefaultComicsDirectoryDetector(gateway)
        val comicFolderScanner = DefaultComicFolderScanner(detector, gateway)
        val chapterScanner = DefaultChapterScanner(gateway)
        val cbzCataloger = DefaultCbzCataloger(gateway)
        return DefaultLibraryScanner(comicFolderScanner, chapterScanner, cbzCataloger)
    }

    @Test
    fun scanLibrary_cleanCollection_identifiesAllComicsChaptersAndCoverDeterministically() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/Naruto")
        gateway.addDirectory("Comics/Bleach")

        // Setup Naruto with Chapter 1 and Chapter 2
        val narutoCh1 = createZipBytes(
            "cover.jpg" to "page 1".toByteArray(),
            "page_02.png" to "page 2".toByteArray()
        )
        val narutoCh2 = createZipBytes(
            "p01.jpg" to "page 1".toByteArray()
        )
        gateway.addFile("Comics/Naruto/Chapter 1.cbz", narutoCh1)
        gateway.addFile("Comics/Naruto/Chapter 2.cbz", narutoCh2)

        // Setup Bleach with Chapter 1 only
        val bleachCh1 = createZipBytes(
            "001.webp" to "cover webp".toByteArray()
        )
        gateway.addFile("Comics/Bleach/Chapter 1.cbz", bleachCh1)

        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        assertEquals(2, success.comics.size)
        assertEquals(2, success.summary.totalComicFoldersDiscovered)
        assertEquals(2, success.summary.validComicsCount)
        assertEquals(3, success.summary.totalChaptersDiscovered)
        assertEquals(3, success.summary.totalValidChaptersCount)
        assertEquals(0, success.summary.totalCorruptArchivesCount)
        assertEquals(0, success.summary.totalEmptyArchivesCount)
        assertEquals(0, success.summary.totalRejectedFilesCount)
        assertEquals(0, success.summary.comicsMissingFirstChapterCount)
        assertTrue(success.issues.isEmpty())

        val naruto = success.comics.first { it.folder.folderName == "Naruto" }
        assertEquals("Naruto", naruto.title)
        assertTrue(naruto.hasValidChapters)
        assertEquals(2, naruto.chapters.size)
        assertEquals("cover.jpg", naruto.coverEntryPath)

        val bleach = success.comics.first { it.folder.folderName == "Bleach" }
        assertEquals("Bleach", bleach.title)
        assertTrue(bleach.hasValidChapters)
        assertEquals(1, bleach.chapters.size)
        assertEquals("001.webp", bleach.coverEntryPath)
    }

    @Test
    fun scanLibrary_isolatedCorruption_doesNotAbortWholeScan() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/Naruto")
        gateway.addDirectory("Comics/One Piece")

        // Naruto has Chapter 1 valid, Chapter 2 corrupt (random non-zip bytes)
        val narutoCh1 = createZipBytes("cover.jpg" to "page 1".toByteArray())
        val corruptBytes = "Definitely corrupted non-zip content".toByteArray()
        gateway.addFile("Comics/Naruto/Chapter 1.cbz", narutoCh1)
        gateway.addFile("Comics/Naruto/Chapter 2.cbz", corruptBytes)

        // One Piece has Chapter 1 valid
        val opCh1 = createZipBytes("cover.png" to "op cover".toByteArray())
        gateway.addFile("Comics/One Piece/Chapter 1.cbz", opCh1)

        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        assertEquals(2, success.comics.size)
        assertEquals(2, success.summary.validComicsCount)
        assertEquals(3, success.summary.totalChaptersDiscovered)
        assertEquals(2, success.summary.totalValidChaptersCount)
        assertEquals(1, success.summary.totalCorruptArchivesCount)

        val naruto = success.comics.first { it.folder.folderName == "Naruto" }
        assertEquals(2, naruto.chapters.size)
        assertTrue(naruto.chapters[0].isValid)
        assertFalse(naruto.chapters[1].isValid)
        assertTrue(naruto.chapters[1].catalogResult is CbzCatalogResult.CorruptArchive)

        val corruptIssue = naruto.issues.filterIsInstance<ComicScanIssue.CorruptChapter>()
        assertEquals(1, corruptIssue.size)
        assertEquals("Comics/Naruto/Chapter 2.cbz", corruptIssue[0].cbzRelativePath)
    }

    @Test
    fun scanLibrary_missingFirstChapter_recordsMissingFirstChapterDiagnostic() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/Naruto")

        // Naruto only has Chapter 2 and Chapter 3 (Chapter 1 is missing!)
        val narutoCh2 = createZipBytes("page.jpg" to "ch 2".toByteArray())
        val narutoCh3 = createZipBytes("page.jpg" to "ch 3".toByteArray())
        gateway.addFile("Comics/Naruto/Chapter 2.cbz", narutoCh2)
        gateway.addFile("Comics/Naruto/Chapter 3.cbz", narutoCh3)

        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        assertEquals(1, success.summary.comicsMissingFirstChapterCount)
        val naruto = success.comics.first()
        assertTrue(naruto.hasValidChapters)
        assertNull(naruto.firstChapter)
        assertNull(naruto.coverEntryPath)

        val missingCh1Issue = naruto.issues.filterIsInstance<ComicScanIssue.MissingFirstChapter>()
        assertEquals(1, missingCh1Issue.size)
        assertEquals("Comics/Naruto", missingCh1Issue[0].comicRelativePath)
    }

    @Test
    fun scanLibrary_corruptFirstChapter_recordsCorruptFirstChapterDiagnostic() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/Naruto")

        // Chapter 1 is corrupted
        val corruptBytes = "Random corrupted content".toByteArray()
        val narutoCh2 = createZipBytes("page.jpg" to "ch 2".toByteArray())
        gateway.addFile("Comics/Naruto/Chapter 1.cbz", corruptBytes)
        gateway.addFile("Comics/Naruto/Chapter 2.cbz", narutoCh2)

        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        val naruto = success.comics.first()
        assertTrue(naruto.hasValidChapters) // Has chapter 2
        assertNull(naruto.coverEntryPath) // Chapter 1 corrupt so no cover

        val corruptFirstIssue = naruto.issues.filterIsInstance<ComicScanIssue.CorruptFirstChapter>()
        assertEquals(1, corruptFirstIssue.size)
        assertEquals("Comics/Naruto/Chapter 1.cbz", corruptFirstIssue[0].cbzRelativePath)
    }

    @Test
    fun scanLibrary_emptyFirstChapter_recordsEmptyFirstChapterDiagnostic() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/Naruto")

        // Chapter 1 is valid zip but contains no images
        val emptyCh1 = createZipBytes("readme.txt" to "no images here".toByteArray())
        val narutoCh2 = createZipBytes("page.jpg" to "ch 2".toByteArray())
        gateway.addFile("Comics/Naruto/Chapter 1.cbz", emptyCh1)
        gateway.addFile("Comics/Naruto/Chapter 2.cbz", narutoCh2)

        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        val naruto = success.comics.first()
        assertTrue(naruto.hasValidChapters)
        assertNull(naruto.coverEntryPath)

        val emptyFirstIssue = naruto.issues.filterIsInstance<ComicScanIssue.EmptyFirstChapter>()
        assertEquals(1, emptyFirstIssue.size)
        assertEquals("Comics/Naruto/Chapter 1.cbz", emptyFirstIssue[0].cbzRelativePath)
    }

    @Test
    fun scanLibrary_comicWithoutValidChapters_recordsNoValidChaptersIssue() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/EmptyComic")

        // Comic folder with only text file and subfolder (no valid chapters)
        gateway.addFile("Comics/EmptyComic/notes.txt", "Some notes".toByteArray())

        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        assertEquals(1, success.comics.size)
        val comic = success.comics.first()
        assertFalse(comic.hasValidChapters)
        assertEquals(0, success.summary.validComicsCount)
        assertEquals(1, success.summary.totalRejectedFilesCount)

        val noValidChaptersIssues = comic.issues.filterIsInstance<ComicScanIssue.NoValidChapters>()
        assertEquals(1, noValidChaptersIssues.size)
    }

    @Test
    fun scanLibrary_emptyComicsDirectory_returnsEmptyComicsDirectory() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")

        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.EmptyComicsDirectory)
    }

    @Test
    fun scanLibrary_rootNotConfigured_returnsRootNotConfigured() {
        val gateway = FakeStorageGateway(rootConfigured = false)
        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.RootNotConfigured)
    }

    @Test
    fun scanLibrary_permissionDenied_returnsPermissionDenied() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = false)
        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.PermissionDenied)
    }

    @Test
    fun scanLibrary_storageIoErrorOnChapterScan_isolatedToComicIssues() {
        val failingGateway = object : StorageGateway {
            override fun getRootState() = StorageResult.Success("content://root")
            override fun listChildren(parentRelativePath: String): StorageResult<List<StorageEntry>> {
                return if (parentRelativePath == "") {
                    StorageResult.Success(
                        listOf(
                            StorageEntry(
                                name = "Comics",
                                relativePath = "Comics",
                                isDirectory = true
                            )
                        )
                    )
                } else if (parentRelativePath == "Comics") {
                    StorageResult.Success(
                        listOf(
                            StorageEntry(
                                name = "FailingComic",
                                relativePath = "Comics/FailingComic",
                                isDirectory = true
                            ),
                            StorageEntry(
                                name = "HealthyComic",
                                relativePath = "Comics/HealthyComic",
                                isDirectory = true
                            )
                        )
                    )
                } else if (parentRelativePath == "Comics/FailingComic") {
                    StorageResult.Error(IOException("Disk I/O error listing failing comic"))
                } else {
                    StorageResult.Success(emptyList())
                }
            }
            override fun findEntry(relativePath: String) = StorageResult.NotFound(relativePath)
            override fun openInputStream(relativePath: String) = StorageResult.NotFound(relativePath)
            override fun openFileDescriptor(relativePath: String, mode: String) = StorageResult.NotFound(relativePath)
        }

        val scanner = createLibraryScanner(failingGateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        assertEquals(2, success.comics.size)
        val failingComic = success.comics.first { it.title == "FailingComic" }
        assertFalse(failingComic.hasValidChapters)
        // Assert exact list of issues for FailingComic: only the I/O ChapterEnumerationError
        // (folder-level), no false NoValidChapters or MissingFirstChapter
        assertEquals(1, failingComic.issues.size)
        val errorIssue = failingComic.issues[0] as ComicScanIssue.ChapterEnumerationError
        assertEquals("Comics/FailingComic", errorIssue.comicRelativePath)
        assertTrue(errorIssue.exception is IOException)
        assertEquals(1, success.summary.comicsMissingFirstChapterCount)
        val healthyComic = success.comics.first { it.title == "HealthyComic" }
        assertTrue(healthyComic.issues.any { it is ComicScanIssue.MissingFirstChapter })
    }

    @Test
    fun scanLibrary_comicDirectoryMissingMidScan_recordsSingleComicDirectoryMissingWithoutDuplicates() {
        // Setup gateway where ComicFolderScanner detects "DisappearingComic",
        // but when ChapterScanner tries to list it, the folder is already deleted / not found.
        val disappearingGateway = object : StorageGateway {
            override fun getRootState() = StorageResult.Success("content://root")
            override fun listChildren(parentRelativePath: String): StorageResult<List<StorageEntry>> {
                return when (parentRelativePath) {
                    "" -> StorageResult.Success(
                        listOf(StorageEntry("Comics", "Comics", isDirectory = true))
                    )
                    "Comics" -> StorageResult.Success(
                        listOf(
                            StorageEntry("DisappearingComic", "Comics/DisappearingComic", isDirectory = true),
                            StorageEntry("PersistentComic", "Comics/PersistentComic", isDirectory = true)
                        )
                    )
                    "Comics/DisappearingComic" -> StorageResult.NotFound("Comics/DisappearingComic")
                    "Comics/PersistentComic" -> StorageResult.Success(emptyList())
                    else -> StorageResult.NotFound(parentRelativePath)
                }
            }
            override fun findEntry(relativePath: String) = StorageResult.NotFound(relativePath)
            override fun openInputStream(relativePath: String) = StorageResult.NotFound(relativePath)
            override fun openFileDescriptor(relativePath: String, mode: String) = StorageResult.NotFound(relativePath)
        }

        val scanner = createLibraryScanner(disappearingGateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        val disappearing = success.comics.first { it.title == "DisappearingComic" }
        // Verify exactly one issue recorded, specifically ComicDirectoryMissing, and no duplicate NoValidChapters
        assertEquals(1, disappearing.issues.size)
        assertTrue(disappearing.issues[0] is ComicScanIssue.ComicDirectoryMissing)
        assertEquals("Comics/DisappearingComic", (disappearing.issues[0] as ComicScanIssue.ComicDirectoryMissing).comicRelativePath)
    }

    @Test
    fun scanLibrary_chapterFileNotFoundDuringCataloging_recordsChapterFileNotFoundIssue() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/Naruto")

        // Add Chapter 1 as a valid entry in directory listing, but when opening InputStream it returns NotFound
        val customGateway = object : StorageGateway {
            override fun getRootState() = StorageResult.Success("content://root")
            override fun listChildren(parentRelativePath: String): StorageResult<List<StorageEntry>> {
                return when (parentRelativePath) {
                    "" -> StorageResult.Success(listOf(StorageEntry("Comics", "Comics", isDirectory = true)))
                    "Comics" -> StorageResult.Success(listOf(StorageEntry("Naruto", "Comics/Naruto", isDirectory = true)))
                    "Comics/Naruto" -> StorageResult.Success(
                        listOf(StorageEntry("Chapter 1.cbz", "Comics/Naruto/Chapter 1.cbz", isDirectory = false))
                    )
                    else -> StorageResult.NotFound(parentRelativePath)
                }
            }
            override fun findEntry(relativePath: String) = StorageResult.NotFound(relativePath)
            override fun openInputStream(relativePath: String): StorageResult<java.io.InputStream> {
                // File deleted or missing when opening stream
                return StorageResult.NotFound(relativePath)
            }
            override fun openFileDescriptor(relativePath: String, mode: String) = StorageResult.NotFound(relativePath)
        }

        val scanner = createLibraryScanner(customGateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        // Verify that NotFound during cataloging is classified as ChapterFileNotFound, NOT corrupt archive
        assertEquals(0, success.summary.totalCorruptArchivesCount)
        val naruto = success.comics.first()
        val notFoundIssue = naruto.issues.filterIsInstance<ComicScanIssue.ChapterFileNotFound>()
        assertEquals(1, notFoundIssue.size)
        assertEquals("Comics/Naruto/Chapter 1.cbz", notFoundIssue[0].cbzRelativePath)
    }

    @Test
    fun scanLibrary_chapterCatalogIoError_isolatedAndDoesNotAbortOtherComics() {
        // Setup 2 comics: ComicA has Chapter 1 throwing IOException on openInputStream, ComicB has valid Chapter 1
        val customGateway = object : StorageGateway {
            override fun getRootState() = StorageResult.Success("content://root")
            override fun listChildren(parentRelativePath: String): StorageResult<List<StorageEntry>> {
                return when (parentRelativePath) {
                    "" -> StorageResult.Success(listOf(StorageEntry("Comics", "Comics", isDirectory = true)))
                    "Comics" -> StorageResult.Success(
                        listOf(
                            StorageEntry("ComicA", "Comics/ComicA", isDirectory = true),
                            StorageEntry("ComicB", "Comics/ComicB", isDirectory = true)
                        )
                    )
                    "Comics/ComicA" -> StorageResult.Success(
                        listOf(StorageEntry("Chapter 1.cbz", "Comics/ComicA/Chapter 1.cbz", isDirectory = false))
                    )
                    "Comics/ComicB" -> StorageResult.Success(
                        listOf(StorageEntry("Chapter 1.cbz", "Comics/ComicB/Chapter 1.cbz", isDirectory = false))
                    )
                    else -> StorageResult.NotFound(parentRelativePath)
                }
            }
            override fun findEntry(relativePath: String) = StorageResult.NotFound(relativePath)
            override fun openInputStream(relativePath: String): StorageResult<java.io.InputStream> {
                return if (relativePath == "Comics/ComicA/Chapter 1.cbz") {
                    StorageResult.Error(IOException("I/O error opening stream for ComicA"))
                } else if (relativePath == "Comics/ComicB/Chapter 1.cbz") {
                    val validZip = createZipBytes("cover.jpg" to "comic b cover".toByteArray())
                    StorageResult.Success(java.io.ByteArrayInputStream(validZip))
                } else {
                    StorageResult.NotFound(relativePath)
                }
            }
            override fun openFileDescriptor(relativePath: String, mode: String) = StorageResult.NotFound(relativePath)
        }

        val scanner = createLibraryScanner(customGateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        assertEquals(2, success.comics.size)
        val comicA = success.comics.first { it.title == "ComicA" }
        assertFalse(comicA.hasValidChapters)
        val catalogError = comicA.issues.filterIsInstance<ComicScanIssue.ChapterCatalogError>()
        assertEquals(1, catalogError.size)
        assertEquals("Comics/ComicA/Chapter 1.cbz", catalogError[0].cbzRelativePath)

        val comicB = success.comics.first { it.title == "ComicB" }
        assertTrue(comicB.hasValidChapters)
        assertEquals("cover.jpg", comicB.coverEntryPath)
    }

    @Test
    fun scanLibrary_coverResolution_handlesCaseAndLeadingZeroDeterministically() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/CaseVariation")
        gateway.addDirectory("Comics/LeadingZero")

        // Case variation: "chapter 1.cbz" (lowercase)
        val caseCh1 = createZipBytes("cover_lowercase.jpg" to "data".toByteArray())
        gateway.addFile("Comics/CaseVariation/chapter 1.cbz", caseCh1)

        // Leading zero: "Chapter 01.cbz"
        val zeroCh1 = createZipBytes("cover_zero.jpg" to "data".toByteArray())
        gateway.addFile("Comics/LeadingZero/Chapter 01.cbz", zeroCh1)

        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        val caseComic = success.comics.first { it.title == "CaseVariation" }
        assertTrue(caseComic.hasValidChapters)
        assertEquals("cover_lowercase.jpg", caseComic.coverEntryPath)

        val zeroComic = success.comics.first { it.title == "LeadingZero" }
        assertTrue(zeroComic.hasValidChapters)
        assertEquals("cover_zero.jpg", zeroComic.coverEntryPath)
    }

    @Test
    fun scanLibrary_multipleChapterOneVariants_selectsFirstByNaturalSortDeterministically() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/MultiOne")

        // Add both "Chapter 01.cbz" and "Chapter 1.cbz"
        // ChapterNaturalComparator sorts Chapter 1 before Chapter 01 (fewer leading zeros first)
        val ch1Bytes = createZipBytes("cover_ch1.jpg" to "ch1".toByteArray())
        val ch01Bytes = createZipBytes("cover_ch01.jpg" to "ch01".toByteArray())
        gateway.addFile("Comics/MultiOne/Chapter 1.cbz", ch1Bytes)
        gateway.addFile("Comics/MultiOne/Chapter 01.cbz", ch01Bytes)

        val scanner = createLibraryScanner(gateway)
        val result = scanner.scanLibrary()

        assertTrue(result is LibraryScanResult.Success)
        val success = result as LibraryScanResult.Success

        val comic = success.comics.first { it.title == "MultiOne" }
        assertTrue(comic.hasValidChapters)
        assertEquals(2, comic.chapters.size)
        // Verify natural order tie-break puts "Chapter 1.cbz" first, so its cover is chosen deterministically
        assertEquals("Chapter 1.cbz", comic.firstChapter?.discoveredChapter?.fileName)
        assertEquals("cover_ch1.jpg", comic.coverEntryPath)
    }
}
