package com.kitsune.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kitsune.app.database.dao.BookmarkDao
import com.kitsune.app.database.dao.ComicDao
import com.kitsune.app.database.dao.PlaylistDao
import com.kitsune.app.database.dao.ReadingProgressDao
import com.kitsune.app.database.dao.SettingsDao
import com.kitsune.app.database.dao.VideoDao
import com.kitsune.app.database.entity.*

@Database(
    entities = [
        SettingsEntity::class,
        ComicEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        BookmarkComicEntity::class,
        PlaylistEntity::class,
        PlaylistComicEntity::class,
        VideoEntity::class,
        VideoProgressEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun comicDao(): ComicDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE reading_progress_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        comicRelativePath TEXT NOT NULL,
                        chapterRelativePath TEXT NOT NULL,
                        pageNumber INTEGER NOT NULL,
                        totalPages INTEGER NOT NULL,
                        lastReadAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO reading_progress_new (id, comicRelativePath, chapterRelativePath, pageNumber, totalPages, lastReadAt)
                    SELECT id, comicRelativePath, chapterRelativePath, pageNumber, totalPages, lastReadAt FROM reading_progress
                """.trimIndent())
                db.execSQL("DROP TABLE reading_progress")
                db.execSQL("ALTER TABLE reading_progress_new RENAME TO reading_progress")
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_reading_progress_comicRelativePath_chapterRelativePath 
                    ON reading_progress (comicRelativePath, chapterRelativePath)
                """.trimIndent())
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create videos table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `videos` (
                        `relativePath` TEXT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `coverUri` TEXT, 
                        `episodeCount` INTEGER NOT NULL, 
                        `lastModified` INTEGER NOT NULL, 
                        PRIMARY KEY(`relativePath`)
                    )
                """.trimIndent())

                // 2. Create video_progress table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `video_progress` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `videoRelativePath` TEXT NOT NULL, 
                        `episodeRelativePath` TEXT NOT NULL, 
                        `lastPositionMs` INTEGER NOT NULL, 
                        `durationMs` INTEGER NOT NULL, 
                        `lastWatchedAt` INTEGER NOT NULL
                    )
                """.trimIndent())

                // 3. Create index for video_progress
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_video_progress_videoRelativePath_episodeRelativePath` 
                    ON `video_progress` (`videoRelativePath`, `episodeRelativePath`)
                """.trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comics ADD COLUMN searchTags TEXT")
                db.execSQL("ALTER TABLE videos ADD COLUMN searchTags TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comics ADD COLUMN displayTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE comics ADD COLUMN author TEXT")
                db.execSQL("ALTER TABLE comics ADD COLUMN language TEXT")
                // Fill displayTitle with folder name for existing entries (REVISION 11.2.9)
                db.execSQL("UPDATE comics SET displayTitle = title")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comics ADD COLUMN type TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Remove duplicates before adding unique index (REVISION 11.4.1)
                db.execSQL("""
                    DELETE FROM bookmark_comics 
                    WHERE id NOT IN (
                        SELECT MIN(id) 
                        FROM bookmark_comics 
                        GROUP BY bookmarkId, comicRelativePath
                    )
                """.trimIndent())

                // 2. Create unique index
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bookmark_comics_bookmarkId_comicRelativePath ON bookmark_comics (bookmarkId, comicRelativePath)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comics ADD COLUMN chapterCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN lastScanTime INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kitsune.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
