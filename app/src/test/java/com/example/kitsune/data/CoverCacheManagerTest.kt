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
import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CoverCacheManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: KitsuneDatabase
    private lateinit var comicDao: ComicDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var fakeStorage: FakeStorageGateway
    private lateinit var trackingStorage: TrackingStorageGateway
    private lateinit var coverExtractor: CoverExtractor
    private lateinit var cacheManager: CoverCacheManager

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
        trackingStorage = TrackingStorageGateway(fakeStorage)

        coverExtractor = CoverExtractor(trackingStorage, comicDao, chapterDao)

        cacheManager = CoverCacheManager(
            cacheDir = tempFolder.newFolder("covers"),
            coverExtractor = coverExtractor,
            comicDao = comicDao,
            chapterDao = chapterDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getCoverFile_cacheMiss_extractsAndCommitsCacheFile() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/One Piece", title = "One Piece", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/One Piece/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )

        val imageContent = "jpeg_cover_payload".toByteArray()
        val cbzBytes = buildZipArchive(mapOf("cover.jpg" to imageContent))
        fakeStorage.addFile("Comics/One Piece/Chapter 1.cbz", cbzBytes)

        val result = cacheManager.getCoverFile(comicId)

        assertTrue(result is CoverCacheResult.Success)
        val success = result as CoverCacheResult.Success
        assertTrue("Cache file must exist on disk", success.file.exists())
        assertEquals(imageContent.size.toLong(), success.file.length())
        assertEquals(1, trackingStorage.openInputStreamCalls)
    }

    @Test
    fun getCoverFile_cacheHit_bypassesExtraction() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/One Piece", title = "One Piece", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/One Piece/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        val cbzBytes = buildZipArchive(mapOf("cover.jpg" to "bytes".toByteArray()))
        fakeStorage.addFile("Comics/One Piece/Chapter 1.cbz", cbzBytes)

        // Initial call (Miss)
        val firstResult = cacheManager.getCoverFile(comicId)
        assertTrue(firstResult is CoverCacheResult.Success)
        assertEquals(1, trackingStorage.openInputStreamCalls)

        // Second call (Hit)
        val secondResult = cacheManager.getCoverFile(comicId)
        assertTrue("Second call must be Cache Hit", secondResult is CoverCacheResult.Hit)
        assertEquals("Storage stream must NOT be opened again on cache hit", 1, trackingStorage.openInputStreamCalls)
        assertEquals((firstResult as CoverCacheResult.Success).file.absolutePath, (secondResult as CoverCacheResult.Hit).file.absolutePath)
    }

    @Test
    fun getCoverFile_metadataChanged_invalidatesOldAndCreatesNew() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        val ch1Id = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        val cbzBytes1 = buildZipArchive(mapOf("cover.jpg" to "v1".toByteArray()))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", cbzBytes1)

        val result1 = cacheManager.getCoverFile(comicId) as CoverCacheResult.Success
        val file1 = result1.file
        assertTrue(file1.exists())

        // Simulate Chapter 1 updated on disk (fileSize changed to 2000L, lastModified to 999L)
        chapterDao.update(
            chapterDao.getById(ch1Id)!!.copy(fileSize = 2000L, lastModifiedAt = 999L)
        )
        val cbzBytes2 = buildZipArchive(mapOf("cover.jpg" to "v2_updated".toByteArray()))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", cbzBytes2)

        val result2 = cacheManager.getCoverFile(comicId) as CoverCacheResult.Success
        val file2 = result2.file

        assertTrue("New cache file must exist", file2.exists())
        assertFalse("Old cache file must be deleted upon invalidation", file1.exists())
        assertEquals(2, trackingStorage.openInputStreamCalls)
    }

    @Test
    fun getCoverFile_unavailableResult_doesNotCreateCacheFile() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "missing.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        // CBZ missing cover.jpg
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", buildZipArchive(mapOf("page1.jpg" to "p".toByteArray())))

        val result = cacheManager.getCoverFile(comicId)

        assertTrue(result is CoverCacheResult.Unavailable)
    }

    @Test
    fun getCoverFile_concurrentCalls_doubleCheckedLocking() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", buildZipArchive(mapOf("cover.jpg" to "payload".toByteArray())))

        // Launch 5 concurrent calls for the same comicId
        val deferreds = (1..5).map {
            async { cacheManager.getCoverFile(comicId) }
        }
        val results = deferreds.awaitAll()

        assertTrue("All results must be successful or hit", results.all { it is CoverCacheResult.Success || it is CoverCacheResult.Hit })
        assertEquals("Storage stream must be opened only ONCE across concurrent calls", 1, trackingStorage.openInputStreamCalls)
    }

    @Test
    fun clearAllCache_removesAllCachedFiles() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", buildZipArchive(mapOf("cover.jpg" to "data".toByteArray())))

        val result = cacheManager.getCoverFile(comicId) as CoverCacheResult.Success
        assertTrue(result.file.exists())

        cacheManager.clearAllCache()

        assertFalse("Cache file must be deleted after clearAllCache", result.file.exists())
    }

    @Test
    fun getCoverFile_zeroByteCorruptedCache_reExtractsAndReplaces() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        val cbzBytes = buildZipArchive(mapOf("cover.jpg" to "valid_bytes".toByteArray()))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", cbzBytes)

        // Seed a 0-byte corrupt file
        val rawKey = "${comicId}_Comics/OP_Comics/OP/Chapter 1.cbz_cover.jpg_1000_500"
        val hash = CoverCacheManager.computeSha256Hex(rawKey)
        val coversFolder = tempFolder.root.listFiles()?.firstOrNull { it.name == "covers" } ?: tempFolder.newFolder("covers")
        val corruptFile = java.io.File(coversFolder, "cover_${comicId}_$hash.jpg")
        corruptFile.createNewFile()
        assertEquals(0L, corruptFile.length())

        // Calling getCoverFile should detect length == 0, treat as miss, and re-extract
        val result = cacheManager.getCoverFile(comicId)

        assertTrue(result is CoverCacheResult.Success)
        val success = result as CoverCacheResult.Success
        assertTrue("Corrupted cache must be replaced with valid file", success.file.length() > 0)
        assertEquals(1, trackingStorage.openInputStreamCalls)
    }

    @Test
    fun getCoverFile_propagatesCancellationException_andCleansUpTemp() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        val cancellingStorage = object : StorageGateway by fakeStorage {
            override fun openInputStream(relativePath: String): StorageResult<InputStream> {
                throw kotlinx.coroutines.CancellationException("Test cancellation in storage")
            }
        }

        val cancellingExtractor = CoverExtractor(cancellingStorage, comicDao, chapterDao)
        val cancellingCacheFolder = tempFolder.newFolder("cancelling_covers")
        val cancellingCacheManager = CoverCacheManager(
            cacheDir = cancellingCacheFolder,
            coverExtractor = cancellingExtractor,
            comicDao = comicDao,
            chapterDao = chapterDao
        )

        try {
            cancellingCacheManager.getCoverFile(comicId)
            org.junit.Assert.fail("CancellationException must be rethrown")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("Test cancellation in storage", e.message)
        }

        // Assert zero temp files or cache files remain in cancellingCacheFolder
        assertEquals(0, cancellingCacheFolder.listFiles()?.size ?: 0)
    }

    @Test
    fun getCoverFile_atomicWriteFailure_doesNotDeleteOldCacheAndCleansUpTemp() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        val ch1Id = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        val cbzBytes1 = buildZipArchive(mapOf("cover.jpg" to "valid_v1".toByteArray()))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", cbzBytes1)

        // Initial successful cache
        val firstResult = cacheManager.getCoverFile(comicId) as CoverCacheResult.Success
        val oldCacheFile = firstResult.file
        assertTrue(oldCacheFile.exists())

        // Update Chapter 1 metadata to invalidate old cache fingerprint
        chapterDao.update(
            chapterDao.getById(ch1Id)!!.copy(fileSize = 2000L, lastModifiedAt = 999L)
        )

        // Make StorageGateway fail during stream reading
        val errorStorage = object : StorageGateway by fakeStorage {
            override fun openInputStream(relativePath: String): StorageResult<InputStream> {
                return StorageResult.Error(java.io.IOException("Disk read error"))
            }
        }
        val errorExtractor = CoverExtractor(errorStorage, comicDao, chapterDao)
        val cacheFolder = tempFolder.newFolder("atomic_fail_covers")
        // Seed old cache file in cacheFolder
        val oldKey = "${comicId}_Comics/OP_Comics/OP/Chapter 1.cbz_cover.jpg_1000_500"
        val oldHash = CoverCacheManager.computeSha256Hex(oldKey)
        val oldFileInFolder = java.io.File(cacheFolder, "cover_${comicId}_$oldHash.jpg")
        oldFileInFolder.writeText("old_data")

        val failingCacheManager = CoverCacheManager(
            cacheDir = cacheFolder,
            coverExtractor = errorExtractor,
            comicDao = comicDao,
            chapterDao = chapterDao
        )

        val result = failingCacheManager.getCoverFile(comicId)

        assertTrue("Failing write must return Unavailable", result is CoverCacheResult.Unavailable)
        assertTrue("Old safe cache file must NOT be deleted when write fails", oldFileInFolder.exists())
        assertEquals("old_data", oldFileInFolder.readText())
    }

    @Test
    fun getCoverFile_renameFailure_deletesTempAndPreservesOldCache() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", buildZipArchive(mapOf("cover.jpg" to "valid_v1".toByteArray())))

        val cacheFolder = tempFolder.newFolder("rename_fail_covers")

        // Seed old cache file in cacheFolder
        val oldKey = "${comicId}_Comics/OP_Comics/OP/Chapter 1.cbz_cover.jpg_1000_500"
        val oldHash = CoverCacheManager.computeSha256Hex(oldKey)
        val oldFileInFolder = java.io.File(cacheFolder, "cover_${comicId}_$oldHash.jpg")
        oldFileInFolder.writeText("old_data")

        // Target filename is new metadata
        val targetHash = CoverCacheManager.computeSha256Hex("${comicId}_Comics/OP_Comics/OP/Chapter 1.cbz_cover.jpg_2000_999")
        val targetFileAsDir = java.io.File(cacheFolder, "cover_${comicId}_$targetHash.jpg")
        targetFileAsDir.mkdir() // Make target a non-empty directory so rename and copyTo fail
        java.io.File(targetFileAsDir, "block.txt").createNewFile()

        // Update DB to new metadata
        chapterDao.update(
            chapterDao.getChapterOneByComicId(comicId)!!.copy(fileSize = 2000L, lastModifiedAt = 999L)
        )

        val failingCacheManager = CoverCacheManager(
            cacheDir = cacheFolder,
            coverExtractor = coverExtractor,
            comicDao = comicDao,
            chapterDao = chapterDao
        )

        val result = failingCacheManager.getCoverFile(comicId)

        assertTrue(result is CoverCacheResult.Unavailable)
        assertTrue("Old safe cache file must NOT be deleted when commit fails", oldFileInFolder.exists())
        assertEquals("old_data", oldFileInFolder.readText())

        // Ensure no leftover temp files exist in cache folder
        val tempFiles = cacheFolder.listFiles()?.filter { it.name.startsWith("temp_") } ?: emptyList()
        assertEquals("No temp files should remain after rename failure", 0, tempFiles.size)
    }

    @Test
    fun getCoverFile_ephemeralRetention_retainsActiveConsumerFileAndCleansUpPriorSession() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        // Chapter 1 has null fileSize and null lastModifiedAt
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = null, lastModifiedAt = null)
        )
        val imageContent = "ephemeral_payload".toByteArray()
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", buildZipArchive(mapOf("cover.jpg" to imageContent)))

        val cacheFolder = tempFolder.newFolder("ephemeral_covers")

        // Seed an unreferenced ephemeral file
        val unreferencedFile = java.io.File(cacheFolder, "ephemeral_cover_${comicId}_1000.jpg")
        unreferencedFile.writeText("unreferenced_bytes")

        val ephemeralCacheManager = CoverCacheManager(
            cacheDir = cacheFolder,
            coverExtractor = coverExtractor,
            comicDao = comicDao,
            chapterDao = chapterDao
        )

        // Request A (Consumer A)
        val resultA = ephemeralCacheManager.getCoverFile(comicId) as CoverCacheResult.Success
        val fileA = resultA.file
        assertTrue("File A for Consumer A must exist", fileA.exists())
        assertTrue("File A must be registered as active", ephemeralCacheManager.isEphemeralFileActive(fileA))

        // Request B (Consumer B) for the same comic in the same active session
        val resultB = ephemeralCacheManager.getCoverFile(comicId) as CoverCacheResult.Success
        val fileB = resultB.file
        assertTrue("File B for Consumer B must exist", fileB.exists())

        // Consumer A's File A must STILL exist and NOT be deleted while Consumer A is active
        assertTrue("Active File A for Consumer A must NOT be deleted during Request B", fileA.exists())

        // Unreferenced ephemeral file MUST be cleaned up
        assertFalse("Unreferenced ephemeral file must be deleted", unreferencedFile.exists())

        // When Consumer A releases File A, it becomes unreferenced and is immediately deleted
        ephemeralCacheManager.releaseCoverFile(fileA)
        assertFalse("File A must no longer be active after release", ephemeralCacheManager.isEphemeralFileActive(fileA))
        assertFalse("Released File A must be deleted immediately when last reference is released", fileA.exists())

        // Zero temp files should remain
        val tempFiles = cacheFolder.listFiles()?.filter { it.name.startsWith("temp_") } ?: emptyList()
        assertEquals("Zero temp files should remain", 0, tempFiles.size)
    }

    @Test
    fun releaseCoverFile_deletesUnreferencedEphemeralImmediately_whenLastRefReleased() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = null, lastModifiedAt = null)
        )
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", buildZipArchive(mapOf("cover.jpg" to "payload".toByteArray())))

        val cacheFolder = tempFolder.newFolder("release_covers")
        val manager = CoverCacheManager(cacheFolder, coverExtractor, comicDao, chapterDao)

        val result = manager.getCoverFile(comicId) as CoverCacheResult.Success
        val ephemeralFile = result.file
        assertTrue("Ephemeral file must exist after extraction", ephemeralFile.exists())

        // Retain an additional reference (2 consumers active)
        manager.retainCoverFile(ephemeralFile)

        // Release first reference (1 consumer remaining)
        manager.releaseCoverFile(ephemeralFile)
        assertTrue("Ephemeral file must STILL exist while 1 consumer is still active", ephemeralFile.exists())

        // Release second reference (0 consumers remaining)
        manager.releaseCoverFile(ephemeralFile)
        assertFalse("Ephemeral file must be DELETED IMMEDIATELY when last reference is released", ephemeralFile.exists())

        // Repeated release calls on unreferenced file should handle gracefully
        manager.releaseCoverFile(ephemeralFile)
        assertFalse("Ephemeral file remains deleted on repeated release", ephemeralFile.exists())
    }

    @Test
    fun releaseCoverFile_neverDeletesPersistentCacheFiles() = runTest {
        val cacheFolder = tempFolder.newFolder("persistent_covers")
        val persistentFile = java.io.File(cacheFolder, "cover_1_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.jpg")
        persistentFile.writeText("persistent_data")

        val manager = CoverCacheManager(cacheFolder, coverExtractor, comicDao, chapterDao)

        // Calling releaseCoverFile on persistent cache file must be ignored
        manager.releaseCoverFile(persistentFile)

        assertTrue("Persistent fingerprint cache file must NEVER be deleted by releaseCoverFile", persistentFile.exists())
        assertEquals("persistent_data", persistentFile.readText())
    }

    @Test
    fun getCoverFile_cancellationDuringFileWrite_deletesTempAndRethrows() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        val ch1Id = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )
        val cbzBytes1 = buildZipArchive(mapOf("cover.jpg" to "valid_v1".toByteArray()))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", cbzBytes1)

        val cacheFolder = tempFolder.newFolder("cancel_write_covers")

        // Seed old valid cache file
        val oldKey = "${comicId}_Comics/OP_Comics/OP/Chapter 1.cbz_cover.jpg_1000_500"
        val oldHash = CoverCacheManager.computeSha256Hex(oldKey)
        val oldFileInFolder = java.io.File(cacheFolder, "cover_${comicId}_$oldHash.jpg")
        oldFileInFolder.writeText("old_valid_data")

        // Update DB metadata so cache miss occurs
        chapterDao.update(
            chapterDao.getChapterOneByComicId(comicId)!!.copy(fileSize = 2000L, lastModifiedAt = 999L)
        )

        // Make CoverExtractor produce a large byte array and cancel coroutine during byte stream writing
        val largeBytes = ByteArray(100_000) { 'x'.code.toByte() }
        val cancellingStorage = object : StorageGateway by fakeStorage {
            override fun openInputStream(relativePath: String): StorageResult<InputStream> {
                return StorageResult.Success(
                    java.io.ByteArrayInputStream(buildZipArchive(mapOf("cover.jpg" to largeBytes)))
                )
            }
        }
        val cancellingExtractor = CoverExtractor(cancellingStorage, comicDao, chapterDao)
        val cancellingManager = CoverCacheManager(cacheFolder, cancellingExtractor, comicDao, chapterDao)

        val job = launch {
            cancellingManager.getCoverFile(comicId)
        }
        // Cancel the job during execution
        job.cancel(kotlinx.coroutines.CancellationException("Write cancelled"))

        try {
            job.join()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            assertEquals("Write cancelled", ce.message)
        }

        // Assert old safe cache file is preserved
        assertTrue("Old safe cache file must NOT be deleted on cancellation", oldFileInFolder.exists())
        assertEquals("old_valid_data", oldFileInFolder.readText())

        // Assert no temp files remain in cache directory
        val tempFiles = cacheFolder.listFiles()?.filter { it.name.startsWith("temp_") } ?: emptyList()
        assertEquals("Zero temp files should remain after cancellation", 0, tempFiles.size)
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

    private class TrackingStorageGateway(
        private val delegate: StorageGateway
    ) : StorageGateway by delegate {
        var openInputStreamCalls = 0
            private set

        override fun openInputStream(relativePath: String): StorageResult<InputStream> {
            openInputStreamCalls++
            return delegate.openInputStream(relativePath)
        }
    }
}
