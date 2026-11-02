package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.CoverExtractor
import com.example.kitsune.data.NoopCbzCataloger
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanner
import com.example.kitsune.scanner.LibraryScanResult
import com.example.kitsune.storage.FakeStorageGateway
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BookmarkManagerTest {

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
            File(System.getProperty("java.io.tmpdir"), "test_repo_covers"),
            coverExtractor, db.comicDao(), db.chapterDao()
        )
        val cbzProvider = com.example.kitsune.reader.DefaultCbzArchiveProvider(
            spoolDir = File(System.getProperty("java.io.tmpdir"), "test_repo_spool"),
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
    fun favoritCollection_isProtected_fromRenameAndDelete() = runTest {
        val bookmarkDao = db.bookmarkDao()

        val favoritId = bookmarkDao.insertCollection(BookmarkEntity(name = "Favorit", displayOrder = 0))
        assertTrue(favoritId > 0)

        // 1. Verify rename of Favorit throws IllegalArgumentException
        try {
            repository.renameBookmarkCollection(favoritId, "Koleksi Utama")
            fail("Renaming 'Favorit' MUST throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Favorit") == true)
        }

        // 2. Verify deletion of Favorit throws IllegalArgumentException
        try {
            repository.deleteBookmarkCollection(favoritId)
            fail("Deleting 'Favorit' MUST throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Favorit") == true)
        }

        // Favorit collection must still exist
        val favoritCol = bookmarkDao.getCollectionById(favoritId)
        assertNotNull(favoritCol)
        assertEquals("Favorit", favoritCol?.name)
    }

    @Test
    fun userCollections_canBeCreatedRenamedReordered_andDeletedSafely() = runTest {
        val comicDao = db.comicDao()
        val bookmarkDao = db.bookmarkDao()

        val comic = ComicEntity(rootUriString = "content://root", relativePath = "Comics/Bleach", title = "Bleach")
        val comicId = comicDao.insert(comic)

        val favoritId = bookmarkDao.insertCollection(BookmarkEntity(name = "Favorit", displayOrder = 0))
        val shonenId = repository.createBookmarkCollection("Shonen")

        repository.addComicToCollection(shonenId, comicId)

        // Rename
        repository.renameBookmarkCollection(shonenId, "Shonen Jump")
        val renamed = bookmarkDao.getCollectionById(shonenId)
        assertEquals("Shonen Jump", renamed?.name)

        // Delete user collection
        repository.deleteBookmarkCollection(shonenId)

        // Verify collection is deleted but comic is NOT deleted
        val deletedCol = bookmarkDao.getCollectionById(shonenId)
        assertEquals(null, deletedCol)

        val comicStillExists = comicDao.getById(comicId)
        assertNotNull("Deleting collection MUST NOT delete ComicEntity", comicStillExists)
        assertEquals("Bleach", comicStillExists?.title)
    }

    private class FakeLibraryScanner : LibraryScanner {
        override fun scanLibrary(): LibraryScanResult = LibraryScanResult.RootNotConfigured
    }
}
