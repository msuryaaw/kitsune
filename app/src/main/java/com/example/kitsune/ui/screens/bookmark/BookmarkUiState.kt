package com.example.kitsune.ui.screens.bookmark

import com.example.kitsune.data.entity.ComicEntity

/**
 * UI State for the Bookmark Screen.
 */
sealed interface BookmarkUiState {
    object Loading : BookmarkUiState
    object RootNotConfigured : BookmarkUiState
    data class PermissionDenied(val message: String) : BookmarkUiState
    object Empty : BookmarkUiState
    data class Success(
        val rootUriString: String,
        val bookmarkedComics: List<ComicEntity>,
        val gridColumns: Int
    ) : BookmarkUiState
    data class Error(val message: String) : BookmarkUiState
}
