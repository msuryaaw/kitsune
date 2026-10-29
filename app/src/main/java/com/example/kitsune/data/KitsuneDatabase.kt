package com.example.kitsune.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.data.dao.ReadingProgressDao
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
 *
 * Note: [SettingsEntity] is registered as an entity but does not yet have a dedicated DAO.
 * A SettingsDao will be added when the roadmap reaches settings persistence (Task 7.x).
 */
@Database(
    entities = [
        ComicEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        SettingsEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class KitsuneDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
}

