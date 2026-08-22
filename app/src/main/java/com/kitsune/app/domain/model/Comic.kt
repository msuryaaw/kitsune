package com.kitsune.app.domain.model

/**
 * Representasi model data komik di domain layer.
 * 
 * REVISION 11.1.3: Added searchTags for filter support in UI.
 * REVISION 11.2.2: Added displayTitle, author, and language.
 * REVISION 11.3.2: Added type.
 * REVISION 11.4.1: Added chapterCount for library badges.
 */
data class Comic(
    val title: String,
    val displayTitle: String,
    val author: String? = null,
    val language: String? = null,
    val type: String? = null,
    val relativePath: String,
    val coverUri: String?,
    val lastModified: Long,
    val searchTags: String? = null,
    val chapterCount: Int = 0
)
