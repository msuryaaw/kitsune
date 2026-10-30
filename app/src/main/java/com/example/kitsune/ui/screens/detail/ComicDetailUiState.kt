package com.example.kitsune.ui.screens.detail

import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity

/**
 * UI State for Comic Detail Screen.
 */
sealed interface ComicDetailUiState {
    /**
     * Initial loading state while setting up reactive stream.
     */
    object Loading : ComicDetailUiState

    /**
     * Comic with target ID was not found or was removed from database.
     */
    object ComicNotFound : ComicDetailUiState

    /**
     * Comic exists, but contains zero valid chapters.
     */
    object EmptyChapters : ComicDetailUiState

    /**
     * Comic and its chapters loaded successfully.
     */
    data class Success(
        val comic: ComicEntity,
        val chapters: List<ChapterEntity>
    ) : ComicDetailUiState

    /**
     * Error state when loading comic detail fails.
     */
    data class Error(val message: String) : ComicDetailUiState
}

/**
 * One-shot navigation and feedback events for Comic Detail Screen.
 */
sealed interface ComicDetailEvent {
    /**
     * Navigate to reader for the specified [chapterId].
     */
    data class NavigateToReader(val chapterId: Long) : ComicDetailEvent

    /**
     * Show feedback message when chapter is missing or inaccessible.
     */
    data class ShowFeedback(val message: String) : ComicDetailEvent
}
