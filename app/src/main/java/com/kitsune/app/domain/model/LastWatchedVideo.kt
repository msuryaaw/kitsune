package com.kitsune.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Model domain untuk mewakili video yang terakhir ditonton.
 * Digunakan untuk fitur Continue Watching pada layar Home.
 */
@Immutable
data class LastWatchedVideo(
    val video: Video,
    val episodeRelativePath: String,
    val progressPositionMs: Long,
    val durationMs: Long,
    val watchedPercentage: Float,
    val lastWatchedAt: Long
)
