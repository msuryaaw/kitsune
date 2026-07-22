package com.kitsune.app.domain.model

/**
 * Model data untuk ringkasan statistik penggunaan Video Engine.
 */
data class VideoStatistics(
    val totalVideos: Int = 0,
    val watchedVideos: Int = 0,
    val completedVideos: Int = 0,
    val totalWatchTimeMs: Long = 0L
)
