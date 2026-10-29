package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.storage.FakeStorageGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CoverExtractorTest {

    private lateinit var db: KitsuneDatabase
    private lateinit var comicDao: ComicDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var fakeStorage: FakeStorageGateway
    private lateinit var coverExtractor: CoverExtractor

    private val rootUri = "content://primary%3AKitsune"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        comicDao = db.comicDao()
        chapterDao = db.chapterDao()
        fakeStorage = FakeStorageGateway()
        coverExtractor = CoverExtractor(fakeStorage, comicDao, chapterDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun extractCover_validChapter1_returnsSuccessWithBytesAndMimeType() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/One Piece", title = "One Piece", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/One Piece/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10)
        )

        val imageContent = "fake_jpeg_payload_bytes".toByteArray()
        val cbzBytes = buildZipArchive(mapOf("cover.jpg" to imageContent, "page2.jpg" to "other".toByteArray()))
        fakeStorage.addFile("Comics/One Piece/Chapter 1.cbz", cbzBytes)

        val result = coverExtractor.extractCover(comicId)

        assertTrue(result is CoverResult.Success)
        val success = result as CoverResult.Success
        assertEquals("image/jpeg", success.mimeType)
        assertTrue(imageContent.contentEquals(success.imageBytes))
    }

    @Test
    fun extractCover_missingChapter1_returnsUnavailable_noFallbackToChapter2() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/One Piece", title = "One Piece", coverEntryPath = "cover.jpg")
        )
        // Insert ONLY Chapter 2 (No Chapter 1)
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/One Piece/Chapter 2.cbz", chapterNumber = 2, displayName = "Chapter 2.cbz", pageCount = 10)
        )
        val cbzBytes = buildZipArchive(mapOf("cover.jpg" to "ch2_cover".toByteArray()))
        fakeStorage.addFile("Comics/One Piece/Chapter 2.cbz", cbzBytes)

        val result = coverExtractor.extractCover(comicId)

        assertTrue(result is CoverResult.Unavailable)
        assertEquals("Chapter 1 is missing for comic", (result as CoverResult.Unavailable).reason)
    }

    @Test
    fun extractCover_corruptArchive_returnsUnavailable() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 1)
        )
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", "corrupt_garbage_not_zip".toByteArray())

        val result = coverExtractor.extractCover(comicId)

        assertTrue("Corrupt archive must return Unavailable", result is CoverResult.Unavailable)
    }

    @Test
    fun extractCover_entryNotFound_returnsUnavailable() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "missing_cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 1)
        )
        val cbzBytes = buildZipArchive(mapOf("actual_page.jpg" to "data".toByteArray()))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", cbzBytes)

        val result = coverExtractor.extractCover(comicId)

        assertTrue(result is CoverResult.Unavailable)
        assertTrue((result as CoverResult.Unavailable).reason.contains("not found in Chapter 1 archive"))
    }

    @Test
    fun extractCover_emptyCoverEntryPath_returnsUnavailable() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = null)
        )

        val result = coverExtractor.extractCover(comicId)

        assertTrue(result is CoverResult.Unavailable)
        assertEquals("No cover entry path recorded for comic", (result as CoverResult.Unavailable).reason)
    }

    @Test
    fun extractCover_unsupportedExtension_returnsUnavailable() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.txt")
        )

        val result = coverExtractor.extractCover(comicId)

        assertTrue(result is CoverResult.Unavailable)
        assertTrue((result as CoverResult.Unavailable).reason.contains("unsupported extension"))
    }

    @Test
    fun extractCover_oversizedEntry_returnsUnavailable() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "huge_cover.png")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 1)
        )

        // 11 MB byte payload exceeding 10 MB limit
        val oversizedData = ByteArray(11 * 1024 * 1024)
        val cbzBytes = buildZipArchive(mapOf("huge_cover.png" to oversizedData))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", cbzBytes)

        val result = coverExtractor.extractCover(comicId)

        assertTrue(result is CoverResult.Unavailable)
        assertTrue((result as CoverResult.Unavailable).reason.contains("exceeded size limit of 10 MB"))
    }

    @Test
    fun extractCover_unknownHeaderSizeOversizedStream_returnsUnavailable() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "huge_unknown.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 1)
        )

        // Build a Zip archive without setting explicit entry size metadata (entry.size == -1L in streaming ZipInputStream)
        val oversizedData = ByteArray(11 * 1024 * 1024)
        val cbzBytes = buildZipArchive(mapOf("huge_unknown.jpg" to oversizedData))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", cbzBytes)

        val result = coverExtractor.extractCover(comicId)

        assertTrue(result is CoverResult.Unavailable)
        assertEquals("Cover image stream exceeded size limit of 10 MB", (result as CoverResult.Unavailable).reason)
    }

    @Test
    fun extractCover_propagatesCancellationException() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 1)
        )

        // Storage Gateway configured to simulate cancellation
        val cancellingStorage = object : com.example.kitsune.storage.StorageGateway by fakeStorage {
            override fun openInputStream(relativePath: String): com.example.kitsune.storage.StorageResult<java.io.InputStream> {
                throw CancellationException("Coroutine cancelled")
            }
        }
        val cancellingExtractor = CoverExtractor(cancellingStorage, comicDao, chapterDao)

        try {
            cancellingExtractor.extractCover(comicId)
            fail("CancellationException must be rethrown and not caught as Unavailable")
        } catch (e: CancellationException) {
            assertEquals("Coroutine cancelled", e.message)
        }
    }

    @Test
    fun extractCover_zeroBytePayload_returnsUnavailable() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 1)
        )
        val emptyZipBytes = buildZipArchive(mapOf("cover.jpg" to ByteArray(0)))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", emptyZipBytes)

        val result = coverExtractor.extractCover(comicId)

        assertTrue(result is CoverResult.Unavailable)
        assertEquals("Cover image entry payload is empty", (result as CoverResult.Unavailable).reason)
    }

    @Test
    fun extractCover_truncatedStreamDuringReading_returnsUnavailable() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 1)
        )

        // Storage Gateway configured to simulate a stream throwing IOException mid-reading
        val truncatingStorage = object : com.example.kitsune.storage.StorageGateway by fakeStorage {
            override fun openInputStream(relativePath: String): com.example.kitsune.storage.StorageResult<java.io.InputStream> {
                val truncatingStream = object : java.io.InputStream() {
                    override fun read(): Int {
                        throw java.io.IOException("Stream truncated prematurely")
                    }
                }
                return com.example.kitsune.storage.StorageResult.Success(truncatingStream)
            }
        }
        val truncatingExtractor = CoverExtractor(truncatingStorage, comicDao, chapterDao)

        val result = truncatingExtractor.extractCover(comicId)

        assertTrue(result is CoverResult.Unavailable)
        assertTrue((result as CoverResult.Unavailable).reason.contains("Stream truncated prematurely"))
    }

    private fun buildZipArchive(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
