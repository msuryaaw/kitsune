package com.example.kitsune.scanner

import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.reader.CbzArchiveResult
import com.example.kitsune.reader.DefaultCbzArchiveProvider
import com.example.kitsune.storage.FakeStorageGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzSecurityAuditTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    @Test
    fun cbzSecurity_pathTraversalEntries_rejectedSafelyWithoutExtractionOutsideRoot() {
        val zipBytes = createZipBytes(
            "../outside.jpg" to "evil_bytes".toByteArray(),
            "../../etc/passwd.png" to "evil_bytes".toByteArray(),
            "/absolute/root.jpg" to "evil_bytes".toByteArray(),
            "valid_folder/valid_page.jpg" to "valid_bytes".toByteArray()
        )

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Test/ZipSlip.cbz"
        gateway.addFile(cbzPath, zipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue("Result must be Success for valid entries", result is CbzCatalogResult.Success)
        val success = result as CbzCatalogResult.Success

        // Traversal entries MUST be filtered out -> ONLY valid_folder/valid_page.jpg remains!
        assertEquals("Traversal entries MUST be filtered out", 1, success.pages.size)
        assertEquals("valid_folder/valid_page.jpg", success.pages.first().entryPath)
    }

    @Test
    fun cbzSecurity_maxPagesPerChapterLimit500_enforcedForImageEntries() {
        // Create ZIP with 505 valid image entries
        val imageEntries = (1..505).map { i ->
            "image_${i.toString().padStart(3, '0')}.jpg" to "img_bytes".toByteArray()
        }.toTypedArray()

        val zipBytes = createZipBytes(*imageEntries)

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Test/TooManyPages.cbz"
        gateway.addFile(cbzPath, zipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue("Chapter exceeding 500 pages MUST return LimitExceeded", result is CbzCatalogResult.LimitExceeded)
        val limit = result as CbzCatalogResult.LimitExceeded
        assertTrue("Reason must mention 500 page limit", limit.reason.contains("500"))
    }

    @Test
    fun cbzSecurity_nonImageEntriesDoNotConsumePageLimitSlots() {
        // 490 image entries + 30 non-image entries (total 520 ZIP entries, but only 490 valid image entries)
        val imageEntries = (1..490).map { i ->
            "image_${i.toString().padStart(3, '0')}.jpg" to "img_bytes".toByteArray()
        }
        val junkEntries = (1..30).map { i ->
            "junk_$i.txt" to "junk".toByteArray()
        }

        val allEntries = (imageEntries + junkEntries).toTypedArray()
        val zipBytes = createZipBytes(*allEntries)

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Test/JunkAndImages.cbz"
        gateway.addFile(cbzPath, zipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue("490 valid images with 30 non-images MUST succeed", result is CbzCatalogResult.Success)
        val success = result as CbzCatalogResult.Success
        assertEquals("Non-image entries MUST NOT consume page limit slots", 490, success.pages.size)
    }

    @Test
    fun cbzSecurity_maxArchiveSize500MB_enforcedInArchiveProvider() = runTest {
        val spoolDir = tempFolder.newFolder("security_spool_500mb")
        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val dummyChapterDao = object : ChapterDao {
            override fun observeChaptersByComicId(comicId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.example.kitsune.data.entity.ChapterEntity>())
            override suspend fun getAllByComicId(comicId: Long) = emptyList<com.example.kitsune.data.entity.ChapterEntity>()
            override suspend fun getById(chapterId: Long): com.example.kitsune.data.entity.ChapterEntity {
                return com.example.kitsune.data.entity.ChapterEntity(id = chapterId, comicId = 1L, relativePath = "Comics/Test/Huge.cbz", chapterNumber = 1, displayName = "Huge.cbz", pageCount = 10, fileSize = 500 * 1024 * 1024L + 100L)
            }
            override suspend fun getByComicAndPath(comicId: Long, relativePath: String) = null
            override suspend fun getChapterOneByComicId(comicId: Long) = null
            override suspend fun countByComicId(comicId: Long) = 0
            override suspend fun insert(chapter: com.example.kitsune.data.entity.ChapterEntity) = 1L
            override suspend fun insertAll(chapters: List<com.example.kitsune.data.entity.ChapterEntity>) = emptyList<Long>()
            override suspend fun update(chapter: com.example.kitsune.data.entity.ChapterEntity) {}
            override suspend fun deleteById(chapterId: Long) {}
            override suspend fun deleteByIds(ids: List<Long>) {}
            override suspend fun deleteAllByComicId(comicId: Long) {}
            override suspend fun findOrphanedIds(comicId: Long, currentPaths: List<String>) = emptyList<Long>()
        }

        val provider = DefaultCbzArchiveProvider(
            spoolDir = spoolDir,
            storageGateway = fakeStorage,
            chapterDao = dummyChapterDao,
            maxSpoolSizeBytes = 500 * 1024 * 1024L // 500 MB limit
        )

        // File size 500MB + 100 bytes -> exceeds limit
        fakeStorage.addFile("Comics/Test/Huge.cbz", ByteArray(1024)) // Metadata in DB specifies 500MB+

        val result = provider.openArchive(1L)
        assertTrue("Archive exceeding 500 MB limit MUST return ArchiveTooLarge", result is CbzArchiveResult.ArchiveTooLarge)
    }

    @Test
    fun cbzSecurity_totalSpoolBudget1_5GB_enforcedInArchiveProvider() = runTest {
        val spoolDir = tempFolder.newFolder("security_spool_budget")
        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)

        val zipBytes1 = createZipBytes("p1.jpg" to ByteArray(400) { '1'.code.toByte() })
        val zipBytes2 = createZipBytes("p2.jpg" to ByteArray(400) { '2'.code.toByte() })
        fakeStorage.addFile("Comics/Test/Ch1.cbz", zipBytes1)
        fakeStorage.addFile("Comics/Test/Ch2.cbz", zipBytes2)

        val chapter1 = com.example.kitsune.data.entity.ChapterEntity(id = 1L, comicId = 1L, relativePath = "Comics/Test/Ch1.cbz", chapterNumber = 1, displayName = "Ch1.cbz", pageCount = 10, fileSize = zipBytes1.size.toLong())
        val chapter2 = com.example.kitsune.data.entity.ChapterEntity(id = 2L, comicId = 1L, relativePath = "Comics/Test/Ch2.cbz", chapterNumber = 2, displayName = "Ch2.cbz", pageCount = 10, fileSize = zipBytes2.size.toLong())

        val dummyChapterDao = object : ChapterDao {
            override fun observeChaptersByComicId(comicId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.example.kitsune.data.entity.ChapterEntity>())
            override suspend fun getAllByComicId(comicId: Long) = emptyList<com.example.kitsune.data.entity.ChapterEntity>()
            override suspend fun getById(chapterId: Long): com.example.kitsune.data.entity.ChapterEntity? {
                return if (chapterId == 1L) chapter1 else chapter2
            }
            override suspend fun getByComicAndPath(comicId: Long, relativePath: String) = null
            override suspend fun getChapterOneByComicId(comicId: Long) = null
            override suspend fun countByComicId(comicId: Long) = 0
            override suspend fun insert(chapter: com.example.kitsune.data.entity.ChapterEntity) = 1L
            override suspend fun insertAll(chapters: List<com.example.kitsune.data.entity.ChapterEntity>) = emptyList<Long>()
            override suspend fun update(chapter: com.example.kitsune.data.entity.ChapterEntity) {}
            override suspend fun deleteById(chapterId: Long) {}
            override suspend fun deleteByIds(ids: List<Long>) {}
            override suspend fun deleteAllByComicId(comicId: Long) {}
            override suspend fun findOrphanedIds(comicId: Long, currentPaths: List<String>) = emptyList<Long>()
        }

        val provider = DefaultCbzArchiveProvider(
            spoolDir = spoolDir,
            storageGateway = fakeStorage,
            chapterDao = dummyChapterDao,
            maxSpoolSizeBytes = 1000L,
            maxAggregateSpoolBudgetBytes = zipBytes1.size.toLong() + 50L // Budget only allows 1 active handle
        )

        // Handle 1 consumes zipBytes1 size -> Success
        val res1 = provider.openArchive(1L)
        assertTrue("Request 1 MUST succeed", res1 is CbzArchiveResult.Success)

        // Handle 2 attempts another chapter -> SpoolBudgetExceeded
        val res2 = provider.openArchive(2L)
        assertTrue("Request 2 exceeding aggregate spool budget MUST return SpoolBudgetExceeded", res2 is CbzArchiveResult.SpoolBudgetExceeded)

        // Close Handle 1 -> frees capacity
        (res1 as CbzArchiveResult.Success).handle.close()

        // Handle 2 now succeeds!
        val res2Retry = provider.openArchive(2L)
        assertTrue("Request 2 retry MUST succeed after Handle 1 is closed and budget freed", res2Retry is CbzArchiveResult.Success)
        (res2Retry as CbzArchiveResult.Success).handle.close()
    }

    @Test
    fun cbzSecurity_duplicateEntries_handledDeterministicallyWithoutCrash() {
        val zipBytes = createZipBytes(
            "image_01.jpg" to "bytes1".toByteArray(),
            "image_01.JPG" to "bytes2".toByteArray(),
            "folder/image_01.jpg" to "bytes3".toByteArray()
        )

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Test/Duplicates.cbz"
        gateway.addFile(cbzPath, zipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue("Duplicate entries MUST be cataloged deterministically without crash", result is CbzCatalogResult.Success)
        val success = result as CbzCatalogResult.Success
        assertEquals(3, success.pages.size)
        assertEquals(1, success.pages[0].pageNumber)
        assertEquals(2, success.pages[1].pageNumber)
        assertEquals(3, success.pages[2].pageNumber)
    }

    @Test
    fun cbzSecurity_maxPagesPerChapterBoundary_exact500PagesSucceeds_501PagesFails() {
        val entries500 = (1..500).map { i ->
            "image_${i.toString().padStart(3, '0')}.jpg" to "img".toByteArray()
        }.toTypedArray()
        val zip500 = createZipBytes(*entries500)

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addFile("Comics/Test/Exact500.cbz", zip500)

        val cataloger = DefaultCbzCataloger(gateway)
        val res500 = cataloger.catalogArchive("Comics/Test/Exact500.cbz")
        assertTrue("Exact 500 pages MUST succeed", res500 is CbzCatalogResult.Success)
        assertEquals(500, (res500 as CbzCatalogResult.Success).pages.size)

        val entries501 = (1..501).map { i ->
            "image_${i.toString().padStart(3, '0')}.jpg" to "img".toByteArray()
        }.toTypedArray()
        val zip501 = createZipBytes(*entries501)
        gateway.addFile("Comics/Test/Exact501.cbz", zip501)

        val res501 = cataloger.catalogArchive("Comics/Test/Exact501.cbz")
        assertTrue("501 pages MUST return LimitExceeded", res501 is CbzCatalogResult.LimitExceeded)
    }
}
