package com.kitsune.app.ui.reader

import android.net.Uri
import com.kitsune.app.domain.model.Page

/**
 * State UI untuk layar Reader.
 */
sealed class ReaderUiState {
    data object Loading : ReaderUiState()
    data class Success(
        val pages: List<Page>,
        val chapterName: String,
        val readingMode: String = "Vertical",
        val chapterUri: Uri // Ditambahkan untuk atomisitas state (Phase 6.7.4)
    ) : ReaderUiState()
    data class Error(val message: String) : ReaderUiState()
    data object Empty : ReaderUiState()
}
