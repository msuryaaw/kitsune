package com.example.kitsune.scanner

import com.example.kitsune.storage.FakeStorageGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterScannerTest {

    @Test
    fun scanChapters_rootNotConfigured_returnsRootNotConfigured() {
        val gateway = FakeStorageGateway(rootConfigured = false)
        val scanner = DefaultChapterScanner(gateway)

        val result = scanner.scanChapters("Comics/One Piece")
        assertTrue(result is ChapterDiscoveryResult.RootNotConfigured)
    }

    @Test
    fun scanChapters_permissionDenied_returnsPermissionDenied() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = false)
        val scanner = DefaultChapterScanner(gateway)

        val result = scanner.scanChapters("Comics/One Piece")
        assertTrue(result is ChapterDiscoveryResult.PermissionDenied)
    }

    @Test
    fun scanChapters_comicDirectoryMissing_returnsComicDirectoryNotFound() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val scanner = DefaultChapterScanner(gateway)

        val result = scanner.scanChapters("Comics/MissingComic")
        assertTrue(result is ChapterDiscoveryResult.ComicDirectoryNotFound)
    }

    @Test
    fun scanChapters_validChapters_parsedCorrectlyAndRejectsNonChapters() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val comicPath = "Comics/One Piece"
        gateway.addDirectory(comicPath)

        // Valid chapters
        gateway.addFile("$comicPath/Chapter 1.cbz", "ch1".toByteArray())
        gateway.addFile("$comicPath/Chapter 2.cbz", "ch2".toByteArray())
        gateway.addFile("$comicPath/chapter 10.cbz", "ch10".toByteArray())

        // Non-chapter files to reject
        gateway.addFile("$comicPath/.nomedia", ByteArray(0))
        gateway.addFile("$comicPath/notes.txt", "notes".toByteArray())
        gateway.addFile("$comicPath/Chapter 3.zip", "zip".toByteArray())
        gateway.addFile("$comicPath/Special Chapter.cbz", "special".toByteArray())

        // Subfolder to ignore
        gateway.addDirectory("$comicPath/Extra")

        val scanner = DefaultChapterScanner(gateway)
        val result = scanner.scanChapters(comicPath)

        assertTrue(result is ChapterDiscoveryResult.Success)
        val success = result as ChapterDiscoveryResult.Success

        // Verify valid chapters
        assertEquals(3, success.chapters.size)
        val ch1 = success.chapters.first { it.chapterNumber == 1 }
        assertEquals("Chapter 1.cbz", ch1.fileName)
        assertEquals("$comicPath/Chapter 1.cbz", ch1.relativePath)

        val ch2 = success.chapters.first { it.chapterNumber == 2 }
        assertEquals("Chapter 2.cbz", ch2.fileName)

        val ch10 = success.chapters.first { it.chapterNumber == 10 }
        assertEquals("chapter 10.cbz", ch10.fileName)

        // Verify rejected files list
        assertEquals(5, success.rejectedFiles.size)
        val rejectedNames = success.rejectedFiles.map { it.fileName }
        assertTrue(rejectedNames.contains(".nomedia"))
        assertTrue(rejectedNames.contains("notes.txt"))
        assertTrue(rejectedNames.contains("Chapter 3.zip"))
        assertTrue(rejectedNames.contains("Special Chapter.cbz"))
        assertTrue(rejectedNames.contains("Extra"))
    }

    @Test
    fun scanChapters_handlesStorageIoErrorExplicitly() {
        val errorGateway = object : com.example.kitsune.storage.StorageGateway {
            override fun getRootState() = com.example.kitsune.storage.StorageResult.Success("content://root")
            override fun listChildren(parentRelativePath: String) =
                com.example.kitsune.storage.StorageResult.Error(java.io.IOException("Read error on comic dir"))
            override fun findEntry(relativePath: String) = com.example.kitsune.storage.StorageResult.NotFound(relativePath)
            override fun openInputStream(relativePath: String) = com.example.kitsune.storage.StorageResult.NotFound(relativePath)
            override fun openFileDescriptor(relativePath: String, mode: String) = com.example.kitsune.storage.StorageResult.NotFound(relativePath)
        }

        val scanner = DefaultChapterScanner(errorGateway)
        val result = scanner.scanChapters("Comics/BrokenComic")

        assertTrue(result is ChapterDiscoveryResult.Error)
        val err = result as ChapterDiscoveryResult.Error
        assertTrue(err.exception is java.io.IOException)
        assertEquals("Read error on comic dir", err.exception.message)
    }
}

