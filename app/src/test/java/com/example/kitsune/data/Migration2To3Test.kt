package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration2To3Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun migration2To3_addsAuthorColumn_andPreservesExistingComicsData() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(tempFolder.newFolder(), "migration_2_3.db")

        // 1. Create SQLite database Version 2
        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
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
                    db.execSQL(
                        """
                        INSERT INTO comics (rootUriString, relativePath, title, type, language, isBookmarked, lastReadAt, coverEntryPath, lastScannedAt)
                        VALUES ('content://root', 'Comics/One Piece', 'One Piece', 'Manga', 'ID', 1, 5000, 'cover.jpg', 1000)
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = helperFactory.create(config)
        val v2Db = openHelper.writableDatabase
        assertEquals(2, v2Db.version)
        v2Db.close()

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

        val comicDao = roomDb.comicDao()
        val comics = comicDao.getAllByRoot("content://root")

        assertEquals(1, comics.size)
        val migratedComic = comics.first()

        assertEquals("One Piece", migratedComic.title)
        assertEquals("Comics/One Piece", migratedComic.relativePath)
        assertEquals("Manga", migratedComic.type)
        assertEquals("ID", migratedComic.language)
        assertNull("Author column MUST be null for migrated V2 records", migratedComic.author)

        roomDb.close()
        dbFile.delete()
    }
}
