package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration3To4Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun migration3To4_createsBookmarkTables_andMigratesOldBookmarksToFavoritCollection() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(tempFolder.newFolder(), "migration_3_4.db")

        // 1. Create SQLite database Version 3 with 2 comics
        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `comics` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `rootUriString` TEXT NOT NULL,
                            `relativePath` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `type` TEXT,
                            `language` TEXT,
                            `author` TEXT,
                            `isBookmarked` INTEGER NOT NULL,
                            `lastReadAt` INTEGER,
                            `coverEntryPath` TEXT,
                            `lastScannedAt` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chapters` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `comicId` INTEGER NOT NULL,
                            `relativePath` TEXT NOT NULL,
                            `chapterNumber` INTEGER NOT NULL,
                            `displayName` TEXT NOT NULL,
                            `pageCount` INTEGER NOT NULL,
                            `fileSize` INTEGER,
                            `lastModifiedAt` INTEGER,
                            `isValid` INTEGER NOT NULL,
                            FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `reading_progress` (
                            `comicId` INTEGER NOT NULL,
                            `lastReadChapterId` INTEGER,
                            `lastReadChapterNumber` INTEGER NOT NULL,
                            `chapterRelativePath` TEXT NOT NULL,
                            `pageIndex` INTEGER NOT NULL,
                            `scrollOffset` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`comicId`),
                            FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `app_settings` (
                            `id` INTEGER NOT NULL,
                            `gridColumns` INTEGER NOT NULL,
                            `theme` TEXT NOT NULL,
                            `readingDirection` TEXT NOT NULL,
                            `readerMode` TEXT NOT NULL,
                            `pageFit` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    // Insert Comic 1 (isBookmarked = 1) and Comic 2 (isBookmarked = 0)
                    db.execSQL(
                        """
                        INSERT INTO comics (id, rootUriString, relativePath, title, type, language, author, isBookmarked, lastReadAt, coverEntryPath, lastScannedAt)
                        VALUES (1, 'content://root', 'Comics/Bookmarked', 'Bookmarked Comic', 'Manga', 'ID', 'Oda', 1, 5000, 'cover.jpg', 1000)
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO comics (id, rootUriString, relativePath, title, type, language, author, isBookmarked, lastReadAt, coverEntryPath, lastScannedAt)
                        VALUES (2, 'content://root', 'Comics/Normal', 'Normal Comic', 'Manga', 'ID', 'Oda', 0, NULL, NULL, 1000)
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = helperFactory.create(config)
        val v3Db = openHelper.writableDatabase
        assertEquals(3, v3Db.version)
        v3Db.close()

        // 2. Open DB with Room V7 and execute migrations
        val roomDb = Room.databaseBuilder(context, KitsuneDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                KitsuneDatabase.MIGRATION_1_2,
                KitsuneDatabase.MIGRATION_2_3,
                KitsuneDatabase.MIGRATION_3_4,
                KitsuneDatabase.MIGRATION_4_5,
                KitsuneDatabase.MIGRATION_5_6,
                KitsuneDatabase.MIGRATION_6_7
            )
            .allowMainThreadQueries()
            .build()

        val bookmarkDao = roomDb.bookmarkDao()
        val comicDao = roomDb.comicDao()

        // 3. Verify Favorit collection exists
        val favoritCol = bookmarkDao.getCollectionByName("Favorit")
        assertTrue("Collection 'Favorit' MUST be created during V3->V4 migration", favoritCol != null)

        // 4. Verify Comic 1 is in Favorit collection, and Comic 2 is NOT
        val favoritComics = bookmarkDao.getCollectionsForComic(1L)
        assertEquals("Comic 1 MUST belong to 1 collection ('Favorit')", 1, favoritComics.size)
        assertEquals("Favorit", favoritComics.first().name)

        val comic2Collections = bookmarkDao.getCollectionsForComic(2L)
        assertEquals("Comic 2 (unbookmarked) MUST NOT belong to any collection", 0, comic2Collections.size)

        // 5. Verify comics data is 100% preserved
        val comics = comicDao.getAllByRoot("content://root")
        assertEquals(2, comics.size)

        roomDb.close()
        dbFile.delete()
    }
}
