package com.example.kitsune.ui.screens.reader

import com.example.kitsune.reader.ChapterPageIndex

/**
 * UI state for Reader Screen.
 */
sealed interface ReaderUiState {
    /**
     * Initial loading state while opening archive and indexing chapter.
     */
    object Loading : ReaderUiState

    /**
     * Chapter archive opened and indexed successfully.
     * Note: CbzPageDecoder and CbzArchiveHandle are private ViewModel resources and are NOT in UiState.
     */
    data class Success(
        val chapterId: Long,
        val pageIndex: ChapterPageIndex,
        val readerMode: String,
        val readingDirection: String = "ltr",
        val currentPageIndex: Int = 0,
        val scrollOffset: Int = 0
    ) : ReaderUiState

    /**
     * Error state when chapter cannot be opened or indexed.
     */
    data class Error(val message: String) : ReaderUiState
}
