package com.example.kitsune.ui.screens.detail

import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity

/**
 * UI State for Comic Detail Screen (Phase 11).
 */
sealed interface ComicDetailUiState {
    object Loading : ComicDetailUiState
    object ComicNotFound : ComicDetailUiState
    object EmptyChapters : ComicDetailUiState

    data class Success(
        val comic: ComicEntity,
        val chapters: List<ChapterEntity>,
        val progress: ReadingProgressEntity? = null
    ) : ComicDetailUiState

    data class Error(val message: String) : ComicDetailUiState
}

/**
 * One-shot navigation and feedback events for Comic Detail Screen.
 */
sealed interface ComicDetailEvent {
    data class NavigateToReader(val chapterId: Long) : ComicDetailEvent
    data class ShowFeedback(val message: String) : ComicDetailEvent
}
