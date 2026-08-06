package com.kitsune.app.domain.model

/**
 * Representasi model data video di domain layer.
 * 
 * REVISION 11.1.3: Added searchTags for filter support in UI.
 */
data class Video(
    val title: String,
    val relativePath: String,
    val coverUri: String?,
    val episodeCount: Int,
    val lastModified: Long,
    val searchTags: String? = null
)
