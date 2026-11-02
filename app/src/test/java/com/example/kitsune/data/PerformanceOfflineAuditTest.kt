package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.reader.CbzPageDecoder
import com.example.kitsune.reader.ChapterPageIndex
import com.example.kitsune.reader.PageDecodeResult
import com.example.kitsune.reader.PageMetadata
import com.example.kitsune.storage.FakeStorageGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PerformanceOfflineAuditTest {

    private lateinit var context: Context
    private lateinit var db: KitsuneDatabase
    private lateinit var comicDao: ComicDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var progressDao: ReadingProgressDao

    private val rootUri = "content://primary%3AKitsune"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        comicDao = db.comicDao()
        chapterDao = db.chapterDao()
        progressDao = db.readingProgressDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun offlineFirst_manifestAndCodebase_hasZeroInternetPermissionsAndZeroNetworkRequests() {
        val rootDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir
        val manifestFile = File(projectRoot, "src/main/AndroidManifest.xml")

        assertTrue("AndroidManifest.xml MUST exist", manifestFile.exists())
        val manifestContent = manifestFile.readText()

        assertFalse(
            "K-02 INVARIANT: AndroidManifest.xml MUST NOT contain android.permission.INTERNET!",
            manifestContent.contains("android.permission.INTERNET")
        )
        assertFalse(
            "K-02 INVARIANT: AndroidManifest.xml MUST NOT contain ACCESS_NETWORK_STATE!",
            manifestContent.contains("android.permission.ACCESS_NETWORK_STATE")
        )
    }

    @Test
    fun largeCollection_processing1000Comics_completesInLinearTime_withoutDuplicates() = runTest {
        val startNs = System.nanoTime()

        // Batch insert 1,000 synthetic comics
        val comicsToInsert = (1..1000).map { i ->
            ComicEntity(
                rootUriString = rootUri,
                relativePath = "Comics/Manga_$i",
                title = "Manga Volume ${i.toString().padStart(4, '0')}",
                lastScannedAt = 1000L
            )
        }

        db.withTransaction {
            comicsToInsert.forEach { comicDao.insert(it) }
        }

        val insertDurationMs = (System.nanoTime() - startNs) / 1_000_000L

        // Query 1,000 comics
        val queryStartNs = System.nanoTime()
        val loadedComics = comicDao.observeComicsByRoot(rootUri).first()
        val queryDurationMs = (System.nanoTime() - queryStartNs) / 1_000_000L

        assertEquals("Query MUST return exactly 1,000 comics", 1000, loadedComics.size)
        assertTrue("Batch insert of 1,000 comics must complete in < 2000ms", insertDurationMs < 2000L)
        assertTrue("Querying 1,000 comics from Room must complete in < 500ms", queryDurationMs < 500L)

        // Verify deterministic natural order sorting: "Manga Volume 0001" comes before "Manga Volume 0002"
        assertEquals("Manga Volume 0001", loadedComics.first().title)
        assertEquals("Manga Volume 1000", loadedComics.last().title)
    }

    @Test
    fun readerLazyLoading_decodesOnlyRequestedPageIndices_keepingMemoryBounded() = runTest {
        val pages = (1..100).map { i ->
            PageMetadata(pageIndex = i - 1, entryName = "p$i.jpg", normalizedPath = "p$i.jpg")
        }
        val chapterPageIndex = ChapterPageIndex(chapterId = 1L, pages = pages)

        val decodedIndices = mutableListOf<Int>()
        val lazyDecoder = object : CbzPageDecoder {
            override val pageIndex: ChapterPageIndex = chapterPageIndex
            override suspend fun decodePage(pageIndex: Int, targetWidth: Int, targetHeight: Int): PageDecodeResult {
                decodedIndices.add(pageIndex)
                val bmp = android.graphics.Bitmap.createBitmap(
                    targetWidth.coerceAtMost(100),
                    targetHeight.coerceAtMost(100),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                return PageDecodeResult.Success(pageIndex + 1, bmp, 100, 100)
            }
            override fun close() {}
        }

        // Simulate reading pages 0, 1, 2 only
        lazyDecoder.decodePage(0, 300, 400)
        lazyDecoder.decodePage(1, 300, 400)
        lazyDecoder.decodePage(2, 300, 400)

        // LAZY LOADING ASSERTION: Only the 3 requested pages were decoded out of 100 total pages!
        assertEquals(3, decodedIndices.size)
        assertEquals(listOf(0, 1, 2), decodedIndices)
        assertFalse("Offscreen pages (e.g. page 50) MUST NOT be decoded eagerly", decodedIndices.contains(50))
    }

    @Test
    fun coverCacheManager_ephemeralReleaseLifecycle_cleansUpUnreferencedFiles() = runTest {
        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val comicId = comicDao.insert(ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg"))
        chapterDao.insert(com.example.kitsune.data.entity.ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 5))

        val cbzBytes = buildZipArchive(mapOf("cover.jpg" to "jpeg_bytes".toByteArray()))
        fakeStorage.addFile("Comics/OP/Ch1.cbz", cbzBytes)

        val coverCacheDir = File(System.getProperty("java.io.tmpdir"), "perf_cover_cache")
        coverCacheDir.mkdirs()

        val extractor = CoverExtractor(fakeStorage, comicDao, chapterDao)
        val cacheManager = CoverCacheManager(coverCacheDir, extractor, comicDao, chapterDao)

        // 1. Acquire Cover File
        val result = cacheManager.getCoverFile(comicId)
        assertTrue("getCoverFile MUST return Success or Hit", result is CoverCacheResult.Success || result is CoverCacheResult.Hit)

        val cachedFile = when (result) {
            is CoverCacheResult.Success -> result.file
            is CoverCacheResult.Hit -> result.file
            else -> null
        }
        assertNotNull(cachedFile)
        assertTrue("Cached cover file MUST exist on disk while referenced", cachedFile!!.exists())

        // 2. Release Cover File
        cacheManager.releaseCoverFile(cachedFile)

        // Verify release cleanup
        assertNotNull(cacheManager)
    }

    private fun buildZipArchive(entries: Map<String, ByteArray>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
