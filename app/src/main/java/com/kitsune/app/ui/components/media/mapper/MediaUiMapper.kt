package com.kitsune.app.ui.components.media.mapper

import com.kitsune.app.domain.model.Comic
import com.kitsune.app.domain.model.Video
import com.kitsune.app.domain.model.MediaType
import com.kitsune.app.ui.components.media.MediaUiModel
import com.kitsune.app.ui.library.ComicStatus
import com.kitsune.app.ui.video.VideoItemState

/**
 * Mapper Layer untuk mengubah domain model dan UI state spesifik menjadi MediaUiModel.
 */

fun Comic.toMediaUiModel(statuses: Set<ComicStatus> = emptySet()): MediaUiModel {
    return MediaUiModel(
        id = relativePath,
        title = title,
        displayTitle = displayTitle,
        author = author,
        language = language,
        type = type,
        badgeText = if (chapterCount > 0) "$chapterCount Ch" else null,
        thumbnailUri = coverUri,
        progress = 0f,
        isFinished = false,
        mediaType = MediaType.COMIC,
        statuses = statuses
    )
}

fun Video.toMediaUiModel(statuses: Set<ComicStatus> = emptySet()): MediaUiModel {
    return MediaUiModel(
        id = relativePath,
        title = title,
        displayTitle = title, // Video uses original title for now
        thumbnailUri = coverUri,
        author = null,
        language = null,
        type = null,
        badgeText = if (episodeCount > 0) "$episodeCount Ep" else null,
        progress = 0f,
        isFinished = false,
        mediaType = MediaType.VIDEO,
        statuses = statuses
    )
}

fun VideoItemState.toMediaUiModel(statuses: Set<ComicStatus> = emptySet()): MediaUiModel {
    return MediaUiModel(
        id = video.relativePath,
        title = video.title,
        displayTitle = video.title,
        thumbnailUri = video.coverUri,
        author = null,
        language = null,
        type = null,
        badgeText = if (video.episodeCount > 0) "${video.episodeCount} Ep" else null,
        progress = watchedPercentage,
        isFinished = isFinished,
        mediaType = MediaType.VIDEO,
        statuses = statuses
    )
}
