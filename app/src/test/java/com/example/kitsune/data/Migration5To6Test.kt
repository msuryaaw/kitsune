package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration5To6Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun migration5To6_addsDisplayOrderColumn_andPreservesExistingBookmarkCollections() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(tempFolder.newFolder(), "migration_5_6.db")

        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    createV5Tables(db)
                    val now = System.currentTimeMillis()
                    db.execSQL("INSERT INTO `bookmarks` (`id`, `name`, `createdAt`, `updatedAt`) VALUES (1, 'Favorit', $now, $now)")
                    db.execSQL("INSERT INTO `bookmarks` (`id`, `name`, `createdAt`, `updatedAt`) VALUES (2, 'Shonen', $now, $now)")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = helperFactory.create(config)
        val v5Db = openHelper.writableDatabase
        assertEquals(5, v5Db.version)
        v5Db.close()

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
        val collections = bookmarkDao.observeAllCollections().first()

        assertEquals(2, collections.size)
        val favoritCol = collections.first { it.name == "Favorit" }
        assertNotNull(favoritCol)
        assertEquals(0, favoritCol.displayOrder)

        val shonenCol = collections.first { it.name == "Shonen" }
        assertNotNull(shonenCol)
        assertEquals(1, shonenCol.displayOrder)

        roomDb.close()
        dbFile.delete()
    }

    @Test
    fun migration5To6_guaranteesFavoritIsFirst_evenWhenFavoritHasHigherIdThanCustomCollections() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(tempFolder.newFolder(), "migration_5_6_favorit_higher_id.db")

        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    createV5Tables(db)
                    val now = System.currentTimeMillis()
                    // Custom collections inserted with smaller IDs
                    db.execSQL("INSERT INTO `bookmarks` (`id`, `name`, `createdAt`, `updatedAt`) VALUES (10, 'Action', ${now - 200}, ${now - 200})")
                    db.execSQL("INSERT INTO `bookmarks` (`id`, `name`, `createdAt`, `updatedAt`) VALUES (20, 'Shonen', ${now - 100}, ${now - 100})")
                    // Favorit inserted with higher ID
                    db.execSQL("INSERT INTO `bookmarks` (`id`, `name`, `createdAt`, `updatedAt`) VALUES (100, 'Favorit', $now, $now)")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = helperFactory.create(config)
        openHelper.writableDatabase.close()

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

        val collections = roomDb.bookmarkDao().observeAllCollections().first()
        assertEquals(3, collections.size)

        // 1. Favorit MUST be the first item in the ordered list (index 0)
        assertEquals("Favorit", collections[0].name)
        assertEquals(0, collections[0].displayOrder)

        // 2. Custom collections MUST follow in order
        assertEquals("Action", collections[1].name)
        assertEquals(1, collections[1].displayOrder)

        assertEquals("Shonen", collections[2].name)
        assertEquals(2, collections[2].displayOrder)

        roomDb.close()
        dbFile.delete()
    }

    private fun createV5Tables(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
                `readingMode` TEXT NOT NULL DEFAULT 'ltr',
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `bookmarks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `comic_bookmark_cross_ref` (
                `bookmarkId` INTEGER NOT NULL,
                `comicId` INTEGER NOT NULL,
                PRIMARY KEY(`bookmarkId`, `comicId`),
                FOREIGN KEY(`bookmarkId`) REFERENCES `bookmarks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`comicId`) REFERENCES `comics`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }
}
