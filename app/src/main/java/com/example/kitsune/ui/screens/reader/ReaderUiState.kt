package com.example.kitsune.ui.screens.reader

import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.reader.ChapterPageIndex

/**
 * UI state for Reader Screen (Phase 11).
 */
sealed interface ReaderUiState {
    object Loading : ReaderUiState

    data class Success(
        val chapterId: Long,
        val pageIndex: ChapterPageIndex,
        val readerMode: String,
        val readingDirection: String = "ltr",
        val pageFit: String = "fit_screen",
        val readingMode: String = "ltr",
        val keepScreenAwake: Boolean = false,
        val currentPageIndex: Int = 0,
        val scrollOffset: Int = 0,
        val chapters: List<ChapterEntity> = emptyList(),
        val currentChapterIndex: Int = -1,
        val hasPreviousChapter: Boolean = false,
        val hasNextChapter: Boolean = false,
        val comicTitle: String = ""
    ) : ReaderUiState {
        val effectiveReadingMode: String
            get() = when (readingMode) {
                "rtl", "vertical" -> readingMode
                else -> "ltr"
            }
    }

    data class Error(
        val message: String,
        val errorType: ReaderErrorType = ReaderErrorType.GENERAL
    ) : ReaderUiState
}

enum class ReaderErrorType {
    CORRUPT_ARCHIVE,
    UNSUPPORTED_ARCHIVE,
    NO_IMAGES,
    PERMISSION_DENIED,
    ROOT_NOT_CONFIGURED,
    CHAPTER_MISSING,
    STORAGE_ERROR,
    GENERAL
}
