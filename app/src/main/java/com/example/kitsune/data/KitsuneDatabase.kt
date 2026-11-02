package com.example.kitsune.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
import com.example.kitsune.data.dao.SettingsDao
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.data.entity.SettingsEntity

/**
 * Room database for the Kitsune application.
 *
 * Registers all four entities: [ComicEntity], [ChapterEntity],
 * [ReadingProgressEntity], and [SettingsEntity].
 *
 * Schema export is enabled to support future migration testing.
 * Exported JSON schemas are stored in `app/schemas/`.
 */
@Database(
    entities = [
        ComicEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        SettingsEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class KitsuneDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE comics ADD COLUMN type TEXT")
                db.execSQL("ALTER TABLE comics ADD COLUMN language TEXT")
            }
        }
    }
}

