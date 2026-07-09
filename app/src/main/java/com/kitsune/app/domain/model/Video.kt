package com.kitsune.app.domain.model

/**
 * Representasi model data video di domain layer.
 */
data class Video(
    val title: String,
    val relativePath: String,
    val coverUri: String?,
    val episodeCount: Int,
    val lastModified: Long
)
