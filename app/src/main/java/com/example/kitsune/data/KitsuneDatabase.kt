package com.example.kitsune.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.kitsune.data.dao.BookmarkDao
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
import com.example.kitsune.data.dao.SettingsDao
import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicBookmarkCrossRef
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.data.entity.SettingsEntity

/**
 * Room database for the Kitsune application.
 *
 * Registers entities: [ComicEntity], [ChapterEntity], [ReadingProgressEntity],
 * [SettingsEntity], [BookmarkEntity], and [ComicBookmarkCrossRef].
 *
 * Schema export is enabled to support future migration testing.
 * Exported JSON schemas are stored in `app/schemas/`.
 */
@Database(
    entities = [
        ComicEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        SettingsEntity::class,
        BookmarkEntity::class,
        ComicBookmarkCrossRef::class
    ],
    version = 6,
    exportSchema = true
)
abstract class KitsuneDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun settingsDao(): SettingsDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comics ADD COLUMN type TEXT")
                db.execSQL("ALTER TABLE comics ADD COLUMN language TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comics ADD COLUMN author TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
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

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comic_bookmark_cross_ref_bookmarkId` ON `comic_bookmark_cross_ref` (`bookmarkId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comic_bookmark_cross_ref_comicId` ON `comic_bookmark_cross_ref` (`comicId`)")

                val now = System.currentTimeMillis()
                db.execSQL("INSERT INTO `bookmarks` (`name`, `createdAt`, `updatedAt`) VALUES ('Favorit', $now, $now)")

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `comic_bookmark_cross_ref` (`bookmarkId`, `comicId`)
                    SELECT
                        (SELECT `id` FROM `bookmarks` WHERE `name` = 'Favorit' ORDER BY `id` LIMIT 1),
                        `id`
                    FROM `comics`
                    WHERE `isBookmarked` = 1
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN readingMode TEXT NOT NULL DEFAULT 'ltr'")
                db.execSQL(
                    """
                    UPDATE app_settings
                    SET readingMode = CASE
                        WHEN readerMode = 'vertical_continuous' THEN 'vertical'
                        WHEN readerMode = 'single_page' AND readingDirection = 'rtl' THEN 'rtl'
                        WHEN readerMode = 'single_page' AND readingDirection = 'ltr' THEN 'ltr'
                        ELSE 'ltr'
                    END
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `bookmarks` SET `displayOrder` = 0 WHERE `name` = 'Favorit'")
                db.execSQL(
                    """
                    UPDATE `bookmarks`
                    SET `displayOrder` = (
                        SELECT COUNT(*) + 1
                        FROM `bookmarks` b2
                        WHERE b2.`name` != 'Favorit' AND (b2.`createdAt` < `bookmarks`.`createdAt` OR (b2.`createdAt` = `bookmarks`.`createdAt` AND b2.`id` < `bookmarks`.`id`))
                    )
                    WHERE `name` != 'Favorit'
                    """.trimIndent()
                )
            }
        }
    }
}

