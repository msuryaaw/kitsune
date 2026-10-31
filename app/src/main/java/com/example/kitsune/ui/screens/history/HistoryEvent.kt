package com.example.kitsune.ui.screens.history

/**
 * One-shot UI events emitted by [HistoryViewModel].
 */
sealed interface HistoryEvent {
    data class NavigateToReader(val chapterId: Long) : HistoryEvent
    data class NavigateToComicDetail(val comicId: Long) : HistoryEvent
    data class ShowFeedback(val message: String) : HistoryEvent
}
