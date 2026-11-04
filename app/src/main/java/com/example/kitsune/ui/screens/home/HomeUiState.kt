package com.example.kitsune.ui.screens.home

import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.ReadingProgressEntity

sealed interface HomeUiState {
    object Loading : HomeUiState

    data class Success(
        val lastReadItem: LastReadInfo?,
        val libraryCount: Int,
        val historyCount: Int
    ) : HomeUiState

    data class Error(val message: String) : HomeUiState
}

data class LastReadInfo(
    val comic: ComicEntity,
    val chapter: ChapterEntity?,
    val progress: ReadingProgressEntity,
    val totalPages: Int
)
