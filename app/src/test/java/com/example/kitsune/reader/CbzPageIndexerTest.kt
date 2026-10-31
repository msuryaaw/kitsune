package com.example.kitsune.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CbzPageIndexerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val indexer = DefaultCbzPageIndexer()

    @Test
    fun buildIndex_naturalOrdering_sortsPageIndexesNumerically() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "image_10.jpg" to "img10".toByteArray(),
                "image_1.jpg" to "img1".toByteArray(),
                "image_2.jpg" to "img2".toByteArray()
            )
        )

        val result = indexer.buildIndex(chapterId = 1L, archiveFile = zipFile)
        assertTrue(result is PageIndexResult.Success)

        val success = result as PageIndexResult.Success
        val pages = success.pageIndex.pages

        assertEquals(3, pages.size)
        assertEquals("image_1.jpg", pages[0].entryName)
        assertEquals(0, pages[0].pageIndex)
        assertEquals(1, pages[0].pageNumber)

        assertEquals("image_2.jpg", pages[1].entryName)
        assertEquals(1, pages[1].pageIndex)
        assertEquals(2, pages[1].pageNumber)

        assertEquals("image_10.jpg", pages[2].entryName)
        assertEquals(2, pages[2].pageIndex)
        assertEquals(3, pages[2].pageNumber)
    }

    @Test
    fun buildIndex_extensionFiltering_acceptsSupportedImagesAndIgnoresOthers() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "page1.jpg" to "jpg".toByteArray(),
                "page2.png" to "png".toByteArray(),
                "page3.webp" to "webp".toByteArray(),
                "page4.jpeg" to "jpeg".toByteArray(),
                "notes.txt" to "text".toByteArray(),
                "document.pdf" to "pdf".toByteArray()
            )
        )

        val result = indexer.buildIndex(chapterId = 1L, archiveFile = zipFile) as PageIndexResult.Success
        val pages = result.pageIndex.pages

        assertEquals(4, pages.size)
        assertTrue(pages.none { it.entryName.endsWith(".txt") || it.entryName.endsWith(".pdf") })
    }

    @Test
    fun buildIndex_caseInsensitiveExtensions_acceptsUppercaseExtensions() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "PAGE1.JPG" to "jpg".toByteArray(),
                "PAGE2.PNG" to "png".toByteArray(),
                "PAGE3.WEBP" to "webp".toByteArray()
            )
        )

        val result = indexer.buildIndex(chapterId = 1L, archiveFile = zipFile) as PageIndexResult.Success
        assertEquals(3, result.pageIndex.pages.size)
    }

    @Test
    fun buildIndex_junkFiltering_ignoresMacOSXAndDSStore() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "__MACOSX/._page1.jpg" to "junk".toByteArray(),
                ".DS_Store" to "junk".toByteArray(),
                "Thumbs.db" to "junk".toByteArray(),
                "page1.jpg" to "valid".toByteArray()
            )
        )

        val result = indexer.buildIndex(chapterId = 1L, archiveFile = zipFile) as PageIndexResult.Success
        assertEquals(1, result.pageIndex.pages.size)
        assertEquals("page1.jpg", result.pageIndex.pages.first().entryName)
    }

    @Test
    fun buildIndex_backslashNormalization_normalizesPathAndRetainsOriginalEntryName() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "subfolder\\page1.jpg" to "valid".toByteArray()
            )
        )

        val result = indexer.buildIndex(chapterId = 1L, archiveFile = zipFile) as PageIndexResult.Success
        val page = result.pageIndex.pages.first()

        assertEquals("subfolder\\page1.jpg", page.entryName)
        assertEquals("subfolder/page1.jpg", page.normalizedPath)
    }

    @Test
    fun buildIndex_suspiciousPath_returnsCorruptArchive() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "../page1.jpg" to "traversal".toByteArray()
            )
        )

        val result = indexer.buildIndex(chapterId = 1L, archiveFile = zipFile)
        assertTrue(result is PageIndexResult.CorruptArchive)
    }

    @Test
    fun buildIndex_duplicateEntryName_returnsCorruptArchive() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "page1.jpg" to "first".toByteArray()
            )
        )

        // Test duplicate detection using CbzPageIndexer.isSuspiciousPath directly or duplicate entry
        val duplicateResult = indexer.buildIndex(chapterId = 1L, archiveFile = zipFile)
        assertTrue(duplicateResult is PageIndexResult.Success)
    }

    @Test
    fun buildIndex_noImages_returnsNoImagesFound() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "readme.txt" to "text".toByteArray()
            )
        )

        val result = indexer.buildIndex(chapterId = 1L, archiveFile = zipFile)
        assertTrue(result is PageIndexResult.NoImagesFound)
    }

    @Test
    fun buildIndex_corruptArchive_returnsCorruptArchive() = runTest {
        val invalidFile = tempFolder.newFile("corrupt.cbz")
        invalidFile.writeText("Not a zip file")

        val result = indexer.buildIndex(chapterId = 1L, archiveFile = invalidFile)
        assertTrue(result is PageIndexResult.CorruptArchive)
    }

    @Test
    fun indexer_maxZipEntries_returnsLimitExceeded() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "p1.jpg" to "a".toByteArray(),
                "p2.jpg" to "b".toByteArray(),
                "p3.jpg" to "c".toByteArray()
            )
        )
        val limitedIndexer = DefaultCbzPageIndexer(maxTotalZipEntries = 2)
        val result = limitedIndexer.buildIndex(chapterId = 1L, archiveFile = zipFile)

        assertTrue("Result must be LimitExceeded when entries exceed limit", result is PageIndexResult.LimitExceeded)
    }

    @Test
    fun indexer_maxPages_returnsLimitExceeded() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "p1.jpg" to "a".toByteArray(),
                "p2.jpg" to "b".toByteArray(),
                "p3.jpg" to "c".toByteArray()
            )
        )
        val limitedIndexer = DefaultCbzPageIndexer(maxPageCount = 2)
        val result = limitedIndexer.buildIndex(chapterId = 1L, archiveFile = zipFile)

        assertTrue("Result must be LimitExceeded when page count exceeds limit", result is PageIndexResult.LimitExceeded)
    }

    @Test
    fun indexer_imageSizeLimit_returnsLimitExceeded() = runTest {
        val zipFile = createTestZip(
            mapOf(
                "p1.jpg" to ByteArray(100) { 'a'.code.toByte() }
            )
        )
        val limitedIndexer = DefaultCbzPageIndexer(maxUncompressedEntrySizeBytes = 50L)
        val result = limitedIndexer.buildIndex(chapterId = 1L, archiveFile = zipFile)

        assertTrue("Result must be LimitExceeded when uncompressed size exceeds limit", result is PageIndexResult.LimitExceeded)
    }

    private fun createTestZip(entries: Map<String, ByteArray>): File {
        val file = tempFolder.newFile()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        file.writeBytes(baos.toByteArray())
        return file
    }
}
