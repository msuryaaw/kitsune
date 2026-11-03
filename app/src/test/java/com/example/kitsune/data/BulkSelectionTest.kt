package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanner
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.storage.FakeStorageGateway
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BulkSelectionTest {

    private lateinit var db: KitsuneDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val fakeScanner = FakeLibraryScanner()
        val noopCataloger = IncrementalCbzCataloger(NoopCbzCataloger)
        val fakeStorage = FakeStorageGateway()
        val coverExtractor = CoverExtractor(fakeStorage, db.comicDao(), db.chapterDao())
        val coverCacheManager = CoverCacheManager(
            File(System.getProperty("java.io.tmpdir"), "test_repo_covers_bulk"),
            coverExtractor, db.comicDao(), db.chapterDao()
        )
        val cbzProvider = com.example.kitsune.reader.DefaultCbzArchiveProvider(
            spoolDir = File(System.getProperty("java.io.tmpdir"), "test_repo_spool_bulk"),
            storageGateway = fakeStorage,
            chapterDao = db.chapterDao()
        )

        repository = DefaultLibraryRepository(
            fakeScanner, noopCataloger, db.comicDao(), db.chapterDao(), db.readingProgressDao(), coverExtractor, coverCacheManager, fakeStorage, cbzProvider, db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun bulkAddToCollection_addsComics_withoutDuplicates() = runTest {
        val comicDao = db.comicDao()
        val bookmarkDao = db.bookmarkDao()

        val c1 = ComicEntity(rootUriString = "content://root", relativePath = "Comics/Bleach", title = "Bleach")
        val c2 = ComicEntity(rootUriString = "content://root", relativePath = "Comics/Naruto", title = "Naruto")
        val id1 = comicDao.insert(c1)
        val id2 = comicDao.insert(c2)

        val colId = repository.createBookmarkCollection("Action")

        // Bulk Add
        repository.addComicsToCollection(colId, listOf(id1, id2))

        val colsC1 = bookmarkDao.getCollectionsForComic(id1)
        assertEquals(1, colsC1.size)
        assertEquals("Action", colsC1.first().name)

        val colsC2 = bookmarkDao.getCollectionsForComic(id2)
        assertEquals(1, colsC2.size)

        // Idempotent re-add
        repository.addComicsToCollection(colId, listOf(id1, id2))
        assertEquals(1, bookmarkDao.getCollectionsForComic(id1).size)
    }

    @Test
    fun bulkRemoveFromCollection_removesCrossRefsOnlyForActiveCollection_andPreservesComicsAndOtherCollections() = runTest {
        val comicDao = db.comicDao()
        val bookmarkDao = db.bookmarkDao()

        // Fixture:
        // Comic A (Bleach) in Action & Shonen
        // Comic B (Naruto) in Action & Favorit
        // Comic C (One Piece) in Action
        val comicA = ComicEntity(rootUriString = "content://root", relativePath = "Comics/Bleach", title = "Bleach")
        val comicB = ComicEntity(rootUriString = "content://root", relativePath = "Comics/Naruto", title = "Naruto")
        val comicC = ComicEntity(rootUriString = "content://root", relativePath = "Comics/OnePiece", title = "One Piece")

        val idA = comicDao.insert(comicA)
        val idB = comicDao.insert(comicB)
        val idC = comicDao.insert(comicC)

        val favoritId = bookmarkDao.insertCollection(BookmarkEntity(name = "Favorit", displayOrder = 0))
        val actionId = repository.createBookmarkCollection("Action")
        val shonenId = repository.createBookmarkCollection("Shonen")

        repository.addComicsToCollection(actionId, listOf(idA, idB, idC))
        repository.addComicsToCollection(shonenId, listOf(idA))
        repository.addComicsToCollection(favoritId, listOf(idB))

        // Bulk remove Comic A, B, C from "Action" collection ONLY
        repository.removeComicsFromCollection(actionId, listOf(idA, idB, idC))

        // 1. Verify Comic A, B, C cross-refs for "Action" are removed
        assertEquals(0, bookmarkDao.getCollectionsForComic(idA).filter { it.id == actionId }.size)
        assertEquals(0, bookmarkDao.getCollectionsForComic(idB).filter { it.id == actionId }.size)
        assertEquals(0, bookmarkDao.getCollectionsForComic(idC).filter { it.id == actionId }.size)

        // 2. Verify Comic A relation to "Shonen" remains intact
        val shonenColsA = bookmarkDao.getCollectionsForComic(idA).filter { it.id == shonenId }
        assertEquals(1, shonenColsA.size)

        // 3. Verify Comic B relation to "Favorit" remains intact
        val favoritColsB = bookmarkDao.getCollectionsForComic(idB).filter { it.id == favoritId }
        assertEquals(1, favoritColsB.size)

        // 4. Verify ComicEntities A, B, and C in comics table remain 100% intact
        assertNotNull("Bulk remove MUST NOT delete ComicEntity A", comicDao.getById(idA))
        assertNotNull("Bulk remove MUST NOT delete ComicEntity B", comicDao.getById(idB))
        assertNotNull("Bulk remove MUST NOT delete ComicEntity C", comicDao.getById(idC))

        // 5. Verify Collections Action, Shonen, and Favorit remain intact in bookmarks table
        assertNotNull("Action collection MUST exist", bookmarkDao.getCollectionById(actionId))
        assertNotNull("Shonen collection MUST exist", bookmarkDao.getCollectionById(shonenId))
        assertNotNull("Favorit collection MUST exist", bookmarkDao.getCollectionById(favoritId))
    }

    private class FakeLibraryScanner : LibraryScanner {
        override fun scanLibrary(): LibraryScanResult = LibraryScanResult.RootNotConfigured
    }
}
