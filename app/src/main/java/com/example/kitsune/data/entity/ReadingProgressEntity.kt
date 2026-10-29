package com.example.kitsune.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Representation of the user's latest reading progress for a comic.
 * Intentionally maintains a loose reference to the chapter (via [lastReadChapterId], [lastReadChapterNumber],
 * and [chapterRelativePath]) without a cascading foreign key constraint to chapters, ensuring that
 * missing or deleted chapter files do not delete user reading progress.
 *
 * @param comicId Unique identifier of the associated [ComicEntity]. Acts as the primary key.
 * @param lastReadChapterId Optional database identifier of the chapter, nullable if chapter is missing.
 * @param lastReadChapterNumber Numeric chapter number that was read (e.g. 1).
 * @param chapterRelativePath Relative path of the chapter file (e.g. "Comics/One Piece/Chapter 1.cbz").
 * @param pageIndex 0-based index of the last read page in the chapter.
 * @param scrollOffset Optional vertical scroll offset for continuous reading mode.
 * @param updatedAt Timestamp (epoch millis) when the progress was recorded.
 */
@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["comicId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["comicId"], unique = true)
    ]
)
data class ReadingProgressEntity(
    @PrimaryKey
    val comicId: Long,
    val lastReadChapterId: Long? = null,
    val lastReadChapterNumber: Int,
    val chapterRelativePath: String,
    val pageIndex: Int,
    val scrollOffset: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

