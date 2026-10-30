package com.example.kitsune.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/**
 * In-memory Room database tests for [KitsuneDatabase] DAOs.
 *
 * Runs on JVM using Robolectric via the [AndroidJUnit4] runner.
 * Configured with SDK 34 (Android 14) for Robolectric JVM compatibility.
 * Tests cover CRUD operations, unique constraints, foreign key CASCADE behavior,
 * Flow reactivity, and isolation of reading progress from chapter deletion.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KitsuneDatabaseTest {

    private lateinit var db: KitsuneDatabase
    private lateinit var comicDao: ComicDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var progressDao: ReadingProgressDao

    private val rootUri = "content://com.android.externalstorage.documents/tree/primary%3AKitsune"
    private val rootUri2 = "content://com.android.externalstorage.documents/tree/primary%3AOther"

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        comicDao = db.comicDao()
        chapterDao = db.chapterDao()
        progressDao = db.readingProgressDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // ═══════════════════════════════════════════
    // 1. Insert and query comics
    // ═══════════════════════════════════════════

    @Test
    fun insertComic_andQueryById_returnsInserted() = runTest {
        val comic = makeComic(relativePath = "Comics/One Piece", title = "One Piece")
        val id = comicDao.insert(comic)
        assertTrue(id > 0)

        val retrieved = comicDao.getById(id)
        assertNotNull(retrieved)
        assertEquals("One Piece", retrieved!!.title)
        assertEquals("Comics/One Piece", retrieved.relativePath)
        assertEquals(rootUri, retrieved.rootUriString)
    }

    @Test
    fun insertComic_andQueryByRootAndPath_returnsCorrect() = runTest {
        val comic = makeComic(relativePath = "Comics/Naruto", title = "Naruto")
        comicDao.insert(comic)

        val found = comicDao.getByRootAndPath(rootUri, "Comics/Naruto")
        assertNotNull(found)
        assertEquals("Naruto", found!!.title)

        val notFound = comicDao.getByRootAndPath(rootUri, "Comics/NonExistent")
        assertNull(notFound)
    }

    @Test
    fun getAllByRoot_returnsOnlyComicsFromThatRoot() = runTest {
        comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        comicDao.insert(makeComic(rootUri, "Comics/B", "B"))
        comicDao.insert(makeComic(rootUri2, "Comics/C", "C"))

        val root1Comics = comicDao.getAllByRoot(rootUri)
        assertEquals(2, root1Comics.size)

        val root2Comics = comicDao.getAllByRoot(rootUri2)
        assertEquals(1, root2Comics.size)
        assertEquals("C", root2Comics[0].title)
    }

    // ═══════════════════════════════════════════
    // 2. Filter by root and bookmark
    // ═══════════════════════════════════════════

    @Test
    fun observeComicsByRoot_emitsFilteredByRoot() = runTest {
        comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        comicDao.insert(makeComic(rootUri, "Comics/B", "B"))
        comicDao.insert(makeComic(rootUri2, "Comics/C", "C"))

        val comics = comicDao.observeComicsByRoot(rootUri).first()
        assertEquals(2, comics.size)
        assertTrue(comics.all { it.rootUriString == rootUri })
    }

    @Test
    fun observeBookmarkedComicsByRoot_emitsOnlyBookmarked() = runTest {
        val id1 = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        comicDao.insert(makeComic(rootUri, "Comics/B", "B"))
        comicDao.setBookmarked(id1, true)

        val bookmarked = comicDao.observeBookmarkedComicsByRoot(rootUri).first()
        assertEquals(1, bookmarked.size)
        assertEquals("A", bookmarked[0].title)
    }

    @Test
    fun observeRecentlyReadByRoot_emitsOnlyReadComics_orderedByMostRecent() = runTest {
        val id1 = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        val id2 = comicDao.insert(makeComic(rootUri, "Comics/B", "B"))
        comicDao.insert(makeComic(rootUri, "Comics/C", "C")) // never read

        comicDao.updateLastReadAt(id1, 1000L)
        comicDao.updateLastReadAt(id2, 2000L)

        val recent = comicDao.observeRecentlyReadByRoot(rootUri).first()
        assertEquals(2, recent.size)
        assertEquals("B", recent[0].title) // most recent first
        assertEquals("A", recent[1].title)
    }

    // ═══════════════════════════════════════════
    // 3. Insert and query chapters
    // ═══════════════════════════════════════════

    @Test
    fun insertChapter_andQueryByComicId_returnsOrderedByChapterNumber() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))

        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 10.cbz", 10, "Chapter 10.cbz"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 2.cbz", 2, "Chapter 2.cbz"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz"))

        val chapters = chapterDao.getAllByComicId(comicId)
        assertEquals(3, chapters.size)
        assertEquals(listOf(1, 2, 10), chapters.map { it.chapterNumber })
    }

    @Test
    fun countByComicId_returnsCorrectCount() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        assertEquals(0, chapterDao.countByComicId(comicId))

        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 2.cbz", 2, "Chapter 2.cbz"))
        assertEquals(2, chapterDao.countByComicId(comicId))
    }

    @Test
    fun insertAll_batchInsert_insertsAllChapters() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        val chapters = listOf(
            makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz"),
            makeChapter(comicId, "Comics/OP/Chapter 2.cbz", 2, "Chapter 2.cbz"),
            makeChapter(comicId, "Comics/OP/Chapter 3.cbz", 3, "Chapter 3.cbz")
        )
        val ids = chapterDao.insertAll(chapters)
        assertEquals(3, ids.size)
        assertTrue(ids.all { it > 0 })
        assertEquals(3, chapterDao.countByComicId(comicId))
    }

    // ═══════════════════════════════════════════
    // 4. Unique constraint and foreign key behavior
    // ═══════════════════════════════════════════

    @Test
    fun insertDuplicateComic_uniqueConstraint_throwsException() = runTest {
        comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        try {
            comicDao.insert(makeComic(rootUri, "Comics/A", "A Duplicate"))
            fail("Expected SQLiteConstraintException for duplicate (rootUriString, relativePath)")
        } catch (e: SQLiteConstraintException) {
            // Expected
        }
    }

    @Test
    fun insertDuplicateChapter_uniqueConstraint_throwsException() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz"))
        try {
            chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1 dup"))
            fail("Expected SQLiteConstraintException for duplicate (comicId, relativePath)")
        } catch (e: SQLiteConstraintException) {
            // Expected
        }
    }

    @Test
    fun chapterDao_queriesReturnChaptersInNaturalOrderWithTieBreakers() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 10.cbz", 10, "Chapter 10.cbz"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 2.cbz", 2, "Chapter 2.cbz"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 3.cbz", 3, "Chapter 3.cbz"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 2_extra.cbz", 2, "Chapter 2 Extra.cbz"))

        val chapters = chapterDao.observeChaptersByComicId(comicId).first()

        assertEquals(5, chapters.size)
        assertEquals(1, chapters[0].chapterNumber)
        assertEquals("Chapter 1.cbz", chapters[0].displayName)

        assertEquals(2, chapters[1].chapterNumber)
        assertEquals("Chapter 2 Extra.cbz", chapters[1].displayName)

        assertEquals(2, chapters[2].chapterNumber)
        assertEquals("Chapter 2.cbz", chapters[2].displayName)

        assertEquals(3, chapters[3].chapterNumber)
        assertEquals(10, chapters[4].chapterNumber)
    }

    @Test
    fun chapterDao_queriesDoNotMixChaptersAcrossComics() = runTest {
        val comic1Id = comicDao.insert(makeComic(rootUri, "Comics/Comic1", "Comic 1"))
        val comic2Id = comicDao.insert(makeComic(rootUri, "Comics/Comic2", "Comic 2"))

        chapterDao.insert(makeChapter(comic1Id, "Comics/Comic1/Chapter 1.cbz", 1, "Comic 1 Ch 1"))
        chapterDao.insert(makeChapter(comic2Id, "Comics/Comic2/Chapter 1.cbz", 1, "Comic 2 Ch 1"))

        val chaptersComic1 = chapterDao.observeChaptersByComicId(comic1Id).first()
        val chaptersComic2 = chapterDao.observeChaptersByComicId(comic2Id).first()

        assertEquals(1, chaptersComic1.size)
        assertEquals("Comic 1 Ch 1", chaptersComic1[0].displayName)

        assertEquals(1, chaptersComic2.size)
        assertEquals("Comic 2 Ch 1", chaptersComic2[0].displayName)
    }

    @Test
    fun insertChapter_withNonExistentComicId_foreignKey_throwsException() = runTest {
        try {
            chapterDao.insert(makeChapter(99999L, "Comics/X/Chapter 1.cbz", 1, "Chapter 1.cbz"))
            fail("Expected SQLiteConstraintException for non-existent comicId FK")
        } catch (e: SQLiteConstraintException) {
            // Expected
        }
    }

    @Test
    fun sameRelativePath_differentRoots_allowedByUniqueConstraint() = runTest {
        val id1 = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        val id2 = comicDao.insert(makeComic(rootUri2, "Comics/A", "A"))
        assertTrue(id1 != id2) // Both inserted successfully
    }

    // ═══════════════════════════════════════════
    // 5. Deleting comic cascades to chapters
    // ═══════════════════════════════════════════

    @Test
    fun deleteComic_cascadeDeletesChaptersAndProgress() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 2.cbz", 2, "Chapter 2.cbz"))
        progressDao.save(makeProgress(comicId, chapterNumber = 1))

        // Verify data exists
        assertEquals(2, chapterDao.countByComicId(comicId))
        assertNotNull(progressDao.getByComicId(comicId))

        // Delete comic
        comicDao.deleteById(comicId)

        // Chapters and progress should be gone
        assertEquals(0, chapterDao.countByComicId(comicId))
        assertNull(progressDao.getByComicId(comicId))
    }

    @Test
    fun deleteAllByRoot_cascadeDeletesAll() = runTest {
        val id1 = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        val id2 = comicDao.insert(makeComic(rootUri, "Comics/B", "B"))
        chapterDao.insert(makeChapter(id1, "Comics/A/Chapter 1.cbz", 1, "Chapter 1.cbz"))
        chapterDao.insert(makeChapter(id2, "Comics/B/Chapter 1.cbz", 1, "Chapter 1.cbz"))

        comicDao.deleteAllByRoot(rootUri)

        assertEquals(0, comicDao.getAllByRoot(rootUri).size)
        assertEquals(0, chapterDao.countByComicId(id1))
        assertEquals(0, chapterDao.countByComicId(id2))
    }

    // ═══════════════════════════════════════════
    // 6. Deleting chapter does NOT delete progress
    // ═══════════════════════════════════════════

    @Test
    fun deleteChapter_doesNotDeleteReadingProgress() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        val chapterId = chapterDao.insert(
            makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz")
        )
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 2.cbz", 2, "Chapter 2.cbz"))

        progressDao.save(makeProgress(comicId, chapterNumber = 1, chapterId = chapterId))

        // Delete chapter 1
        chapterDao.deleteById(chapterId)

        // Progress must survive
        val progress = progressDao.getByComicId(comicId)
        assertNotNull(progress)
        assertEquals(1, progress!!.lastReadChapterNumber)

        // Other chapter still exists
        assertEquals(1, chapterDao.countByComicId(comicId))
    }

    @Test
    fun deleteAllChaptersByComicId_doesNotDeleteReadingProgress() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz"))
        progressDao.save(makeProgress(comicId, chapterNumber = 1))

        chapterDao.deleteAllByComicId(comicId)

        assertEquals(0, chapterDao.countByComicId(comicId))
        assertNotNull(progressDao.getByComicId(comicId))
    }

    // ═══════════════════════════════════════════
    // 7. Save and read reading progress
    // ═══════════════════════════════════════════

    @Test
    fun saveProgress_andReadBack_returnsCorrect() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        val progress = makeProgress(comicId, chapterNumber = 3, pageIndex = 5, scrollOffset = 120)
        progressDao.save(progress)

        val retrieved = progressDao.getByComicId(comicId)
        assertNotNull(retrieved)
        assertEquals(3, retrieved!!.lastReadChapterNumber)
        assertEquals(5, retrieved.pageIndex)
        assertEquals(120, retrieved.scrollOffset)
        assertEquals("Comics/OP/Chapter 3.cbz", retrieved.chapterRelativePath)
    }

    @Test
    fun saveProgress_twice_overwritesWithoutError() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        progressDao.save(makeProgress(comicId, chapterNumber = 1, pageIndex = 0))
        progressDao.save(makeProgress(comicId, chapterNumber = 2, pageIndex = 10))

        val retrieved = progressDao.getByComicId(comicId)
        assertNotNull(retrieved)
        assertEquals(2, retrieved!!.lastReadChapterNumber)
        assertEquals(10, retrieved.pageIndex)
    }

    @Test
    fun getAll_returnsProgressOrderedByMostRecent() = runTest {
        val id1 = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        val id2 = comicDao.insert(makeComic(rootUri, "Comics/B", "B"))

        progressDao.save(makeProgress(id1, chapterNumber = 1, updatedAt = 1000L))
        progressDao.save(makeProgress(id2, chapterNumber = 1, updatedAt = 2000L))

        val all = progressDao.getAll()
        assertEquals(2, all.size)
        assertEquals(id2, all[0].comicId) // most recent first
        assertEquals(id1, all[1].comicId)
    }

    @Test
    fun deleteProgressByComicId_removesOnlyTargetProgress() = runTest {
        val id1 = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        val id2 = comicDao.insert(makeComic(rootUri, "Comics/B", "B"))

        progressDao.save(makeProgress(id1, chapterNumber = 1))
        progressDao.save(makeProgress(id2, chapterNumber = 1))

        progressDao.deleteByComicId(id1)

        assertNull(progressDao.getByComicId(id1))
        assertNotNull(progressDao.getByComicId(id2))
    }

    // ═══════════════════════════════════════════
    // 8. Flow reactivity
    // ═══════════════════════════════════════════

    @Test
    fun observeComicsByRoot_flow_emitsUpdatesAfterInsert() = runTest {
        // Initial emission should be empty
        val initial = comicDao.observeComicsByRoot(rootUri).first()
        assertTrue(initial.isEmpty())

        // After insert, re-collecting should show the new comic
        comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        val afterInsert = comicDao.observeComicsByRoot(rootUri).first()
        assertEquals(1, afterInsert.size)
        assertEquals("A", afterInsert[0].title)
    }

    @Test
    fun observeChaptersByComicId_flow_emitsUpdatesAfterInsert() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))

        val initial = chapterDao.observeChaptersByComicId(comicId).first()
        assertTrue(initial.isEmpty())

        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz"))
        val afterInsert = chapterDao.observeChaptersByComicId(comicId).first()
        assertEquals(1, afterInsert.size)
    }

    @Test
    fun observeComicsByRoot_flow_emitsUpdatesAfterBookmarkChange() = runTest {
        val comicId = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))

        val before = comicDao.observeBookmarkedComicsByRoot(rootUri).first()
        assertTrue(before.isEmpty())

        comicDao.setBookmarked(comicId, true)
        val after = comicDao.observeBookmarkedComicsByRoot(rootUri).first()
        assertEquals(1, after.size)
    }

    @Test
    fun observeComicsByRoot_flow_emitsUpdatesAfterDelete() = runTest {
        val comicId = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        assertEquals(1, comicDao.observeComicsByRoot(rootUri).first().size)

        comicDao.deleteById(comicId)
        val afterDelete = comicDao.observeComicsByRoot(rootUri).first()
        assertTrue(afterDelete.isEmpty())
    }

    // ═══════════════════════════════════════════
    // 9. Batch/transaction and upsert operations
    // ═══════════════════════════════════════════

    @Test
    fun upsertComic_insertsNewComic() = runTest {
        val comic = makeComic(rootUri, "Comics/A", "A")
        val id = comicDao.upsertByRootAndPath(comic)
        assertTrue(id > 0)

        val retrieved = comicDao.getById(id)
        assertNotNull(retrieved)
        assertEquals("A", retrieved!!.title)
    }

    @Test
    fun upsertComic_updatesExisting_preservesUserState() = runTest {
        val id = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        comicDao.setBookmarked(id, true)
        comicDao.updateLastReadAt(id, 5000L)

        // Upsert with updated scan data (new coverEntryPath, same identity)
        val updated = makeComic(rootUri, "Comics/A", "A Updated").copy(
            coverEntryPath = "page_001.jpg",
            lastScannedAt = 9999L
        )
        val returnedId = comicDao.upsertByRootAndPath(updated)
        assertEquals(id, returnedId)

        val retrieved = comicDao.getById(id)!!
        assertEquals("A Updated", retrieved.title)
        assertEquals("page_001.jpg", retrieved.coverEntryPath)
        // User state preserved:
        assertTrue(retrieved.isBookmarked)
        assertEquals(5000L, retrieved.lastReadAt)
    }

    @Test
    fun upsertComic_doesNotCascadeDeleteChildren() = runTest {
        val comicId = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        chapterDao.insert(makeChapter(comicId, "Comics/A/Chapter 1.cbz", 1, "Chapter 1.cbz"))
        progressDao.save(makeProgress(comicId, chapterNumber = 1))

        // Upsert same comic identity with new scan data
        comicDao.upsertByRootAndPath(makeComic(rootUri, "Comics/A", "A Rescanned"))

        // Children must survive
        assertEquals(1, chapterDao.countByComicId(comicId))
        assertNotNull(progressDao.getByComicId(comicId))
    }

    @Test
    fun upsertChapter_insertsNewChapter() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        val chapter = makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz")
        val id = chapterDao.upsertByComicAndPath(chapter)
        assertTrue(id > 0)

        val retrieved = chapterDao.getById(id)
        assertNotNull(retrieved)
    }

    @Test
    fun upsertChapter_updatesExisting_preservesId() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        val id = chapterDao.insert(
            makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz", pageCount = 10)
        )

        // Upsert with updated page count
        val updated = makeChapter(
            comicId, "Comics/OP/Chapter 1.cbz", 1, "Chapter 1.cbz", pageCount = 15
        )
        val returnedId = chapterDao.upsertByComicAndPath(updated)
        assertEquals(id, returnedId)

        val retrieved = chapterDao.getById(id)!!
        assertEquals(15, retrieved.pageCount)
    }

    @Test
    fun findOrphanedComicIds_returnsCorrectIds() = runTest {
        val id1 = comicDao.insert(makeComic(rootUri, "Comics/A", "A"))
        val id2 = comicDao.insert(makeComic(rootUri, "Comics/B", "B"))
        comicDao.insert(makeComic(rootUri, "Comics/C", "C"))

        val orphaned = comicDao.findOrphanedIds(rootUri, listOf("Comics/C"))
        assertEquals(2, orphaned.size)
        assertTrue(orphaned.contains(id1))
        assertTrue(orphaned.contains(id2))
    }

    @Test
    fun findOrphanedChapterIds_returnsCorrectIds() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        val ch1 = chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Ch 1"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 2.cbz", 2, "Ch 2"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 3.cbz", 3, "Ch 3"))

        val orphaned = chapterDao.findOrphanedIds(
            comicId, listOf("Comics/OP/Chapter 2.cbz", "Comics/OP/Chapter 3.cbz")
        )
        assertEquals(1, orphaned.size)
        assertEquals(ch1, orphaned[0])
    }

    @Test
    fun deleteByIds_removesSelectedChapters() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        val ch1 = chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Ch 1"))
        val ch2 = chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 2.cbz", 2, "Ch 2"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 3.cbz", 3, "Ch 3"))

        chapterDao.deleteByIds(listOf(ch1, ch2))
        assertEquals(1, chapterDao.countByComicId(comicId))
        val remaining = chapterDao.getAllByComicId(comicId)
        assertEquals(3, remaining[0].chapterNumber)
    }

    @Test
    fun batchInsertChapters_atomicOnConstraintViolation() = runTest {
        val comicId = comicDao.insert(makeComic(relativePath = "Comics/OP", title = "OP"))
        chapterDao.insert(makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Ch 1"))

        // Attempt batch insert with one duplicate — should fail atomically
        val batch = listOf(
            makeChapter(comicId, "Comics/OP/Chapter 2.cbz", 2, "Ch 2"),
            makeChapter(comicId, "Comics/OP/Chapter 1.cbz", 1, "Ch 1 dup") // duplicate path
        )
        try {
            chapterDao.insertAll(batch)
            fail("Expected SQLiteConstraintException for batch with duplicate")
        } catch (e: SQLiteConstraintException) {
            // Expected — transaction rolled back
        }

        // Only the original chapter 1 should exist; chapter 2 should NOT have been inserted
        // because the entire batch is atomic (Room wraps @Insert in a transaction)
        assertEquals(1, chapterDao.countByComicId(comicId))
    }

    // ═══════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════

    private fun makeComic(
        root: String = rootUri,
        relativePath: String,
        title: String
    ): ComicEntity = ComicEntity(
        rootUriString = root,
        relativePath = relativePath,
        title = title,
        lastScannedAt = System.currentTimeMillis()
    )

    private fun makeChapter(
        comicId: Long,
        relativePath: String,
        chapterNumber: Int,
        displayName: String,
        pageCount: Int = 10
    ): ChapterEntity = ChapterEntity(
        comicId = comicId,
        relativePath = relativePath,
        chapterNumber = chapterNumber,
        displayName = displayName,
        pageCount = pageCount
    )

    private fun makeProgress(
        comicId: Long,
        chapterNumber: Int,
        chapterId: Long? = null,
        pageIndex: Int = 0,
        scrollOffset: Int = 0,
        updatedAt: Long = System.currentTimeMillis()
    ): ReadingProgressEntity = ReadingProgressEntity(
        comicId = comicId,
        lastReadChapterId = chapterId,
        lastReadChapterNumber = chapterNumber,
        chapterRelativePath = "Comics/OP/Chapter $chapterNumber.cbz",
        pageIndex = pageIndex,
        scrollOffset = scrollOffset,
        updatedAt = updatedAt
    )
}

