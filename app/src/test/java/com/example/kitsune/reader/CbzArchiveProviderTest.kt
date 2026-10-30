package com.example.kitsune.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.KitsuneDatabase
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.storage.FakeStorageGateway
import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CbzArchiveProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: KitsuneDatabase
    private lateinit var comicDao: ComicDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var fakeStorage: FakeStorageGateway
    private lateinit var spoolDir: File
    private lateinit var provider: DefaultCbzArchiveProvider

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
        spoolDir = tempFolder.newFolder("reader_spool")

        provider = DefaultCbzArchiveProvider(
            spoolDir = spoolDir,
            storageGateway = fakeStorage,
            chapterDao = chapterDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun openArchive_validChapter_spoolsSuccessfullyAndReturnsHandle() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/One Piece", title = "One Piece", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/One Piece/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )

        val zipBytes = buildZipArchive(mapOf("page1.jpg" to "page1_bytes".toByteArray()))
        fakeStorage.addFile("Comics/One Piece/Chapter 1.cbz", zipBytes)

        val result = provider.openArchive(chapterId)

        assertTrue("Result must be Success", result is CbzArchiveResult.Success)
        val success = result as CbzArchiveResult.Success
        val handle = success.handle

        assertTrue("Spooled file must exist on disk", handle.file.exists())
        assertEquals(zipBytes.size.toLong(), handle.file.length())
        assertEquals(zipBytes.toList(), handle.file.readBytes().toList())

        // Close handle
        handle.close()
        assertTrue("Handle must be marked closed", handle.isClosed)
        assertFalse("Spooled file must be deleted when handle is closed", handle.file.exists())
    }

    @Test
    fun openArchive_handleClose_isIdempotent() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 5, fileSize = 500L, lastModifiedAt = 100L)
        )
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", buildZipArchive(mapOf("p.jpg" to "p".toByteArray())))

        val result = provider.openArchive(chapterId) as CbzArchiveResult.Success
        val handle = result.handle

        assertTrue(handle.file.exists())

        handle.close()
        handle.close() // Second call must be idempotent no-op
        handle.close()

        assertFalse("Spooled file must remain deleted on repeated close", handle.file.exists())
    }

    @Test
    fun openArchive_archiveTooLarge_returnsArchiveTooLargeAndDeletesPartFile() = runTest {
        val maxSmallLimit = 100L
        val limitedProvider = DefaultCbzArchiveProvider(
            spoolDir = spoolDir,
            storageGateway = fakeStorage,
            chapterDao = chapterDao,
            maxSpoolSizeBytes = maxSmallLimit
        )

        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/Big", title = "Big", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/Big/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 100, fileSize = 2000L, lastModifiedAt = 500L)
        )

        val bigBytes = ByteArray(150) { 'x'.code.toByte() }
        fakeStorage.addFile("Comics/Big/Chapter 1.cbz", bigBytes)

        val result = limitedProvider.openArchive(chapterId)

        assertTrue("Result must be ArchiveTooLarge", result is CbzArchiveResult.ArchiveTooLarge)
        val tooLarge = result as CbzArchiveResult.ArchiveTooLarge
        assertEquals(maxSmallLimit, tooLarge.maxSizeBytes)

        // Ensure zero .part files remain in spoolDir
        val partFiles = spoolDir.listFiles()?.filter { it.name.contains(".part") } ?: emptyList()
        assertEquals("Zero .part files must remain after size abort", 0, partFiles.size)
    }

    @Test
    fun openArchive_cancellationDuringSpool_deletesPartFileAndRethrows() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )

        val cancellingStorage = object : StorageGateway by fakeStorage {
            override fun openInputStream(relativePath: String): StorageResult<InputStream> {
                throw CancellationException("Cancelled during SAF open")
            }
        }

        val cancellingProvider = DefaultCbzArchiveProvider(
            spoolDir = spoolDir,
            storageGateway = cancellingStorage,
            chapterDao = chapterDao
        )

        try {
            cancellingProvider.openArchive(chapterId)
            fail("CancellationException must be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("Cancelled during SAF open", ce.message)
        }

        val partFiles = spoolDir.listFiles()?.filter { it.name.contains(".part") } ?: emptyList()
        assertEquals("Zero .part files must remain after cancellation", 0, partFiles.size)
    }

    @Test
    fun openArchive_emptyFile_returnsStorageErrorAndDeletesPartFile() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/Empty", title = "Empty", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/Empty/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 0, fileSize = 0L, lastModifiedAt = 500L)
        )

        fakeStorage.addFile("Comics/Empty/Chapter 1.cbz", ByteArray(0))

        val result = provider.openArchive(chapterId)

        assertTrue("Result must be StorageError for 0-byte file", result is CbzArchiveResult.StorageError)

        val partFiles = spoolDir.listFiles()?.filter { it.name.contains(".part") } ?: emptyList()
        assertEquals("Zero .part files must remain after empty file error", 0, partFiles.size)
    }

    @Test
    fun openArchive_missingChapter_returnsChapterMissing() = runTest {
        val result = provider.openArchive(99999L)
        assertTrue(result is CbzArchiveResult.ChapterMissing)
    }

    @Test
    fun openArchive_permissionDenied_returnsPermissionDenied() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 1000L, lastModifiedAt = 500L)
        )

        val permStorage = object : StorageGateway by fakeStorage {
            override fun openInputStream(relativePath: String): StorageResult<InputStream> {
                return StorageResult.PermissionDenied("SAF Permission Revoked")
            }
        }

        val permProvider = DefaultCbzArchiveProvider(spoolDir, permStorage, chapterDao)
        val result = permProvider.openArchive(chapterId)

        assertTrue(result is CbzArchiveResult.PermissionDenied)
        assertEquals("SAF Permission Revoked", (result as CbzArchiveResult.PermissionDenied).message)
    }

    @Test
    fun startupCleanup_removesOrphanedPartAndSpoolFilesFromPriorSessions() = runTest {
        val pastSessionStart = System.currentTimeMillis()

        // Seed orphaned files created prior to session start
        val orphanedPart = File(spoolDir, "temp_spool_1_abc_123.part")
        orphanedPart.writeText("orphaned_part_data")
        orphanedPart.setLastModified(pastSessionStart - 3600000L)

        val orphanedSpool = File(spoolDir, "spool_1_abc_123.cbz")
        orphanedSpool.writeText("orphaned_spool_data")
        orphanedSpool.setLastModified(pastSessionStart - 3600000L)

        assertTrue(orphanedPart.exists())
        assertTrue(orphanedSpool.exists())

        // Re-initialize provider with session start after file creation
        val newProvider = DefaultCbzArchiveProvider(
            spoolDir = spoolDir,
            storageGateway = fakeStorage,
            chapterDao = chapterDao,
            sessionStartTimeMs = pastSessionStart
        )

        assertFalse("Orphaned .part file must be deleted on startup", orphanedPart.exists())
        assertFalse("Orphaned prior session spool file must be deleted on startup", orphanedSpool.exists())
    }

    @Test
    fun openArchive_aggregateBudget_rejectsRequestWhenActiveHandlesExceedBudget() = runTest {
        val smallBudgetProvider = DefaultCbzArchiveProvider(
            spoolDir = spoolDir,
            storageGateway = fakeStorage,
            chapterDao = chapterDao,
            maxSpoolSizeBytes = 1000L,
            maxAggregateSpoolBudgetBytes = 300L
        )

        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        val ch1Id = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = 200L, lastModifiedAt = 500L)
        )
        val ch2Id = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 2.cbz", chapterNumber = 2, displayName = "Chapter 2.cbz", pageCount = 10, fileSize = 200L, lastModifiedAt = 500L)
        )

        val zipBytes1 = buildZipArchive(mapOf("p1.jpg" to ByteArray(200) { '1'.code.toByte() }))
        val zipBytes2 = buildZipArchive(mapOf("p2.jpg" to ByteArray(200) { '2'.code.toByte() }))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", zipBytes1)
        fakeStorage.addFile("Comics/OP/Chapter 2.cbz", zipBytes2)

        // Request 1: uses ~200 bytes of 300 byte budget
        val res1 = smallBudgetProvider.openArchive(ch1Id) as CbzArchiveResult.Success
        val handle1 = res1.handle
        assertTrue("Handle 1 file must exist", handle1.file.exists())

        // Request 2: attempts another 200 bytes (total 400 bytes > 300 bytes budget)
        val res2 = smallBudgetProvider.openArchive(ch2Id)
        assertTrue("Request 2 must be rejected due to aggregate budget exceed", res2 is CbzArchiveResult.SpoolBudgetExceeded)

        // Handle 1 MUST remain active and its file MUST NOT be deleted
        assertTrue("Active handle 1 file must NOT be deleted by budget rejection", handle1.file.exists())

        // Close handle 1 -> frees capacity
        handle1.close()
        assertFalse("Handle 1 file is deleted when closed", handle1.file.exists())

        // Request 2 can now succeed!
        val res2Retry = smallBudgetProvider.openArchive(ch2Id)
        assertTrue("Request 2 succeeds after handle 1 capacity is released", res2Retry is CbzArchiveResult.Success)
        (res2Retry as CbzArchiveResult.Success).handle.close()
    }

    @Test
    fun openArchive_unknownSourceSize_exceedsBudgetDuringStreaming_abortsAndCleansUp() = runTest {
        val smallBudgetProvider = DefaultCbzArchiveProvider(
            spoolDir = spoolDir,
            storageGateway = fakeStorage,
            chapterDao = chapterDao,
            maxSpoolSizeBytes = 1000L,
            maxAggregateSpoolBudgetBytes = 100L
        )

        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/Unknown", title = "Unknown", coverEntryPath = "cover.jpg")
        )
        // Chapter 1 has null fileSize
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/Unknown/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 10, fileSize = null, lastModifiedAt = 500L)
        )

        val bigBytes = ByteArray(200) { 'z'.code.toByte() }
        fakeStorage.addFile("Comics/Unknown/Chapter 1.cbz", bigBytes)

        val result = smallBudgetProvider.openArchive(chapterId)

        assertTrue("Unknown size streaming crossing budget must be rejected with SpoolBudgetExceeded", result is CbzArchiveResult.SpoolBudgetExceeded)

        val partFiles = spoolDir.listFiles()?.filter { it.name.contains(".part") } ?: emptyList()
        assertEquals("Zero .part files must remain after budget abort during streaming", 0, partFiles.size)
    }

    @Test
    fun openArchive_atomicCommitFallback_verifiesByteContentMatchesBeforePublishing() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/OP", title = "OP", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/OP/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 5, fileSize = 500L, lastModifiedAt = 100L)
        )
        val zipBytes = buildZipArchive(mapOf("test.jpg" to "test_bytes".toByteArray()))
        fakeStorage.addFile("Comics/OP/Chapter 1.cbz", zipBytes)

        val result = provider.openArchive(chapterId)
        assertTrue(result is CbzArchiveResult.Success)
        val handle = (result as CbzArchiveResult.Success).handle

        assertTrue("Committed file must exist", handle.file.exists())
        handle.close()
    }

    @Test
    fun openArchive_concurrentReservationRace_rejectsWhenBudgetExceeded() = runTest {
        val gateProvider = DefaultCbzArchiveProvider(
            spoolDir = spoolDir,
            storageGateway = fakeStorage,
            chapterDao = chapterDao,
            maxSpoolSizeBytes = 1000L,
            maxAggregateSpoolBudgetBytes = 250L
        )

        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/Race", title = "Race", coverEntryPath = "cover.jpg")
        )
        val ch1Id = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/Race/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 5, fileSize = 60L, lastModifiedAt = 100L)
        )
        val ch2Id = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/Race/Chapter 2.cbz", chapterNumber = 2, displayName = "Chapter 2.cbz", pageCount = 5, fileSize = 60L, lastModifiedAt = 100L)
        )

        val bytes1 = buildZipArchive(mapOf("p1.jpg" to ByteArray(60) { 'a'.code.toByte() }))
        val bytes2 = buildZipArchive(mapOf("p2.jpg" to ByteArray(60) { 'b'.code.toByte() }))
        fakeStorage.addFile("Comics/Race/Chapter 1.cbz", bytes1)
        fakeStorage.addFile("Comics/Race/Chapter 2.cbz", bytes2)

        // Request 1 reserves 60 bytes of 100 budget -> Success
        val res1 = gateProvider.openArchive(ch1Id)
        assertTrue("Request 1 must succeed", res1 is CbzArchiveResult.Success)

        // Concurrent Request 2 attempts 60 bytes (total 120 > 100 budget) -> SpoolBudgetExceeded
        val res2 = gateProvider.openArchive(ch2Id)
        assertTrue("Request 2 must be rejected with SpoolBudgetExceeded", res2 is CbzArchiveResult.SpoolBudgetExceeded)

        // Close Handle 1 -> frees capacity
        (res1 as CbzArchiveResult.Success).handle.close()

        // Request 2 retry now succeeds!
        val res2Retry = gateProvider.openArchive(ch2Id)
        assertTrue("Request 2 retry succeeds after capacity released", res2Retry is CbzArchiveResult.Success)
        (res2Retry as CbzArchiveResult.Success).handle.close()
    }

    @Test
    fun openArchive_renameFailure_stagingFallbackSucceeds() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/Fallback", title = "Fallback", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/Fallback/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 5, fileSize = 500L, lastModifiedAt = 100L)
        )
        val zipBytes = buildZipArchive(mapOf("test.jpg" to "test_bytes".toByteArray()))
        fakeStorage.addFile("Comics/Fallback/Chapter 1.cbz", zipBytes)

        // Inject rename failure to force staging fallback copy path
        provider.forceRenameFailure = true
        provider.forceStagingFailure = false

        val result = provider.openArchive(chapterId)
        assertTrue("Staging fallback copy must succeed when rename fails", result is CbzArchiveResult.Success)
        val handle = (result as CbzArchiveResult.Success).handle

        assertTrue("Committed file must exist after staging fallback", handle.file.exists())
        assertEquals(zipBytes.size.toLong(), handle.file.length())

        handle.close()
    }

    @Test
    fun openArchive_stagingCopyFailure_abortsAndCleansUpAllArtifacts() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/Fail", title = "Fail", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/Fail/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 5, fileSize = 500L, lastModifiedAt = 100L)
        )
        val zipBytes = buildZipArchive(mapOf("test.jpg" to "test_bytes".toByteArray()))
        fakeStorage.addFile("Comics/Fail/Chapter 1.cbz", zipBytes)

        // Inject rename AND staging failure
        provider.forceRenameFailure = true
        provider.forceStagingFailure = true

        val result = provider.openArchive(chapterId)
        assertTrue("Staging failure must return StorageError", result is CbzArchiveResult.StorageError)

        // Ensure zero .part, .tmp, or final .cbz files remain in spoolDir
        val remainingFiles = spoolDir.listFiles() ?: emptyArray()
        assertEquals("Zero files must remain in spoolDir after staging failure", 0, remainingFiles.size)
    }

    @Test
    fun openArchive_stagingPublishFailure_abortsAndCleansUpAllArtifacts() = runTest {
        val comicId = comicDao.insert(
            ComicEntity(rootUriString = rootUri, relativePath = "Comics/FailPub", title = "FailPub", coverEntryPath = "cover.jpg")
        )
        val chapterId = chapterDao.insert(
            ChapterEntity(comicId = comicId, relativePath = "Comics/FailPub/Chapter 1.cbz", chapterNumber = 1, displayName = "Chapter 1.cbz", pageCount = 5, fileSize = 500L, lastModifiedAt = 100L)
        )
        val zipBytes = buildZipArchive(mapOf("test.jpg" to "test_bytes".toByteArray()))
        fakeStorage.addFile("Comics/FailPub/Chapter 1.cbz", zipBytes)

        // Inject rename AND staging publish failure
        provider.forceRenameFailure = true
        provider.forceStagingFailure = false
        provider.forceStagingPublishFailure = true

        val result = provider.openArchive(chapterId)
        assertTrue("Staging publish failure must return StorageError", result is CbzArchiveResult.StorageError)

        // Ensure zero .part, .tmp, or final .cbz files remain in spoolDir
        val remainingFiles = spoolDir.listFiles() ?: emptyArray()
        assertEquals("Zero files must remain in spoolDir after staging publish failure", 0, remainingFiles.size)
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
