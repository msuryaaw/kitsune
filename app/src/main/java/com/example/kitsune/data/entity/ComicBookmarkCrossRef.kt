package com.example.kitsune.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction entity representing the many-to-many relationship between a comic [ComicEntity]
 * and a named bookmark collection [BookmarkEntity].
 *
 * @param bookmarkId Foreign key to [BookmarkEntity].
 * @param comicId Foreign key to [ComicEntity].
 */
@Entity(
    tableName = "comic_bookmark_cross_ref",
    primaryKeys = ["bookmarkId", "comicId"],
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ComicEntity::class,
            parentColumns = ["id"],
            childColumns = ["comicId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookmarkId"]),
        Index(value = ["comicId"])
    ]
)
data class ComicBookmarkCrossRef(
    val bookmarkId: Long,
    val comicId: Long
)
