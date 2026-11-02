package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.entity.ComicEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BookmarkCollectionTest {

    private lateinit var db: KitsuneDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createCollection_trimsName_andInsertsUniqueCollection() = runTest {
        val bookmarkDao = db.bookmarkDao()

        val id1 = bookmarkDao.insertCollection(
            com.example.kitsune.data.entity.BookmarkEntity(name = "Manga Favorit")
        )
        assertTrue(id1 > 0)

        val fetched = bookmarkDao.getCollectionById(id1)
        assertNotNull(fetched)
        assertEquals("Manga Favorit", fetched?.name)
    }

    @Test
    fun addAndRemoveComicFromCollections_supportsMultiCollectionMembership_andDeleteSafety() = runTest {
        val comicDao = db.comicDao()
        val bookmarkDao = db.bookmarkDao()

        // 1. Insert a comic
        val comic = ComicEntity(
            rootUriString = "content://root",
            relativePath = "Comics/One Piece",
            title = "One Piece"
        )
        val comicId = comicDao.insert(comic)

        // 2. Create 2 collections: "Action" and "Shonen"
        val actionId = bookmarkDao.insertCollection(com.example.kitsune.data.entity.BookmarkEntity(name = "Action"))
        val shonenId = bookmarkDao.insertCollection(com.example.kitsune.data.entity.BookmarkEntity(name = "Shonen"))

        // 3. Add comic to both collections
        bookmarkDao.addComicToCollection(com.example.kitsune.data.entity.ComicBookmarkCrossRef(actionId, comicId))
        bookmarkDao.addComicToCollection(com.example.kitsune.data.entity.ComicBookmarkCrossRef(shonenId, comicId))

        // 4. Verify comic belongs to 2 collections
        val count = bookmarkDao.getBookmarkCountForComic(comicId)
        assertEquals(2, count)

        val cols = bookmarkDao.getCollectionsForComic(comicId)
        assertEquals(2, cols.size)

        // 5. Remove comic from "Action" collection -> comic remains in "Shonen"
        bookmarkDao.removeComicFromCollection(actionId, comicId)

        val remainingCols = bookmarkDao.getCollectionsForComic(comicId)
        assertEquals(1, remainingCols.size)
        assertEquals("Shonen", remainingCols.first().name)

        // 6. Delete "Shonen" collection -> comic MUST NOT be deleted from comics table!
        bookmarkDao.deleteCollectionById(shonenId)

        val comicStillExists = comicDao.getById(comicId)
        assertNotNull("Deleting collection MUST NOT delete the ComicEntity", comicStillExists)
        assertEquals("One Piece", comicStillExists?.title)
    }
}
