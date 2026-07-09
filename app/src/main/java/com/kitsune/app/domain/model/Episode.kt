package com.kitsune.app.domain.model

/**
 * Representasi model data episode video.
 * Hanya sebagai domain model, tidak disimpan di database (Filesystem First).
 */
data class Episode(
    val name: String,
    val relativePath: String,
    val lastModified: Long
)
