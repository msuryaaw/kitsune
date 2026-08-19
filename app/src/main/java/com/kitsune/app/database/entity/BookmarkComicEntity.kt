package com.kitsune.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmark_comics",
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookmarkId"]),
        Index(value = ["bookmarkId", "comicRelativePath"], unique = true)
    ]
)
data class BookmarkComicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookmarkId: Long,
    val comicRelativePath: String,
    val createdAt: Long = System.currentTimeMillis()
)
