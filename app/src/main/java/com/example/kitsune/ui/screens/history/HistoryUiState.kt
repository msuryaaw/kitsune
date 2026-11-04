package com.example.kitsune.ui.screens.history

import com.example.kitsune.data.model.ComicWithProgress

enum class HistoryGroupHeader(val displayName: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    EARLIER("Earlier")
}

data class HistoryGroup(
    val header: HistoryGroupHeader,
    val items: List<ComicWithProgress>
)

/**
 * UI state for History Screen (Phase 11).
 */
sealed interface HistoryUiState {
    object Loading : HistoryUiState
    object RootNotConfigured : HistoryUiState
    data class PermissionDenied(val message: String) : HistoryUiState
    object Empty : HistoryUiState
    data class Success(
        val rootUriString: String,
        val groups: List<HistoryGroup>
    ) : HistoryUiState
    data class Error(val message: String) : HistoryUiState
}
