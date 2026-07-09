package com.kitsune.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity untuk menyimpan cache metadata video hasil scanning.
 * Mendukung filosofi Filesystem First dengan relativePath sebagai identifier utama.
 */
@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val relativePath: String,
    val title: String,
    val coverUri: String?,
    val episodeCount: Int,
    val lastModified: Long
)
