package com.example.kitsune.ui.screens.history

import com.example.kitsune.data.model.ComicWithProgress

/**
 * UI state for History Screen.
 */
sealed interface HistoryUiState {
    object Loading : HistoryUiState
    object RootNotConfigured : HistoryUiState
    data class PermissionDenied(val message: String) : HistoryUiState
    object Empty : HistoryUiState
    data class Success(
        val rootUriString: String,
        val items: List<ComicWithProgress>
    ) : HistoryUiState
    data class Error(val message: String) : HistoryUiState
}
