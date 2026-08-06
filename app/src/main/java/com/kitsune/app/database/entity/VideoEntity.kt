package com.kitsune.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity untuk menyimpan cache metadata video hasil scanning.
 * Mendukung filosofi Filesystem First dengan relativePath sebagai identifier utama.
 * 
 * REVISION 11.1.1: Added searchTags for lightweight metadata search cache.
 */
@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val relativePath: String,
    val title: String,
    val coverUri: String?,
    val episodeCount: Int,
    val lastModified: Long,
    val searchTags: String? = null
)
