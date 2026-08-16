package com.kitsune.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity untuk menyimpan cache metadata komik hasil scanning.
 * Digunakan untuk mendukung Incremental Scan dan Search Indexing.
 * 
 * REVISION 11.1.1: Added searchTags for lightweight metadata search cache.
 * REVISION 11.2.1: Added displayTitle, author, and language for advanced parsing.
 */
@Entity(tableName = "comics")
data class ComicEntity(
    @PrimaryKey val relativePath: String,
    val title: String, // Original folder name
    val displayTitle: String, // Parsed title
    val author: String? = null,
    val language: String? = null,
    val coverUri: String?,
    val lastModified: Long,
    val searchTags: String? = null
)
