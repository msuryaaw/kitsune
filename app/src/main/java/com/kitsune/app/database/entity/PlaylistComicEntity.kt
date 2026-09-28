package com.kitsune.app.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity relasi antara Playlist dan Komik.
 */
@Entity(
    tableName = "playlist_comics",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["playlistId", "comicRelativePath"]),
        Index(value = ["comicRelativePath"])
    ]
)
data class PlaylistComicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val comicRelativePath: String,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
