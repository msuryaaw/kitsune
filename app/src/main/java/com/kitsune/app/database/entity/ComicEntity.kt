package com.kitsune.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity untuk menyimpan cache metadata komik hasil scanning.
 * Digunakan untuk mendukung Incremental Scan dan Search Indexing.
 * 
 * REVISION 11.1.1: Added searchTags for lightweight metadata search cache.
 */
@Entity(tableName = "comics")
data class ComicEntity(
    @PrimaryKey val relativePath: String,
    val title: String,
    val coverUri: String?,
    val lastModified: Long,
    val searchTags: String? = null
)
