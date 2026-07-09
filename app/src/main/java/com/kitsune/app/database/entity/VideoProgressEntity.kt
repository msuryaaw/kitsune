package com.kitsune.app.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity untuk menyimpan progres menonton video.
 * Menggunakan milidetik (ms) untuk presisi posisi playback.
 */
@Entity(
    tableName = "video_progress",
    indices = [
        Index(value = ["videoRelativePath", "episodeRelativePath"], unique = true)
    ]
)
data class VideoProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoRelativePath: String,
    val episodeRelativePath: String,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long = System.currentTimeMillis()
)
