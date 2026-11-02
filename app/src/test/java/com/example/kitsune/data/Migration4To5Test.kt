package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class Migration4To5Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun migration4To5_verticalContinuous_migratesToVertical() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(tempFolder.newFolder(), "migration_4_5_vertical.db")

        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    createV4Tables(db)
                    db.execSQL(
                        """
                        INSERT INTO app_settings (id, gridColumns, theme, readingDirection, readerMode, pageFit)
                        VALUES (1, 3, 'system', 'rtl', 'vertical_continuous', 'fit_screen')
                        """.trimIndent()
                    )
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = helperFactory.create(config)
        val v4Db = openHelper.writableDatabase
        assertEquals(4, v4Db.version)
        v4Db.close()

        val roomDb = Room.databaseBuilder(context, KitsuneDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                KitsuneDatabase.MIGRATION_1_2,
                KitsuneDatabase.MIGRATION_2_3,
                KitsuneDatabase.MIGRATION_3_4,
                KitsuneDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()

        val settings = roomDb.settingsDao().getSettings()
        assertNotNull(settings)
        assertEquals("vertical_continuous MUST migrate to vertical", "vertical", settings?.readingMode)

        roomDb.close()
        dbFile.delete()
    }

    @Test
    fun migration4To5_singlePageRtl_migratesToRtl() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(tempFolder.newFolder(), "migration_4_5_rtl.db")

        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    createV4Tables(db)
                    db.execSQL(
                        """
                        INSERT INTO app_settings (id, gridColumns, theme, readingDirection, readerMode, pageFit)
                        VALUES (1, 3, 'system', 'rtl', 'single_page', 'fit_width')
                        """.trimIndent()
                    )
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
                KitsuneDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()

        val settings = roomDb.settingsDao().getSettings()
        assertNotNull(settings)
        assertEquals("single_page + rtl MUST migrate to rtl", "rtl", settings?.readingMode)
        assertEquals("fit_width", settings?.pageFit)

        roomDb.close()
        dbFile.delete()
    }

    @Test
    fun migration4To5_unknownReaderMode_fallsBackToLtrEvenIfDirectionIsRtl() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(tempFolder.newFolder(), "migration_4_5_fallback.db")

        val helperFactory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    createV4Tables(db)
                    db.execSQL(
                        """
                        INSERT INTO app_settings (id, gridColumns, theme, readingDirection, readerMode, pageFit)
                        VALUES (1, 3, 'system', 'rtl', 'unknown_legacy_mode', 'fit_screen')
                        """.trimIndent()
                    )
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
                KitsuneDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()

        val settings = roomDb.settingsDao().getSettings()
        assertNotNull(settings)
        assertEquals("Unknown readerMode MUST fall back to ltr", "ltr", settings?.readingMode)

        roomDb.close()
        dbFile.delete()
    }

    private fun createV4Tables(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
    }
}
