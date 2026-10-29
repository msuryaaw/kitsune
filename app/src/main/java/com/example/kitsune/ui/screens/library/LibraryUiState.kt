package com.example.kitsune.ui.screens.library

import com.example.kitsune.data.entity.ComicEntity

/**
 * UI State for Library Screen.
 */
sealed interface LibraryUiState {
    /**
     * Initial loading state while setting up reactive stream or scanning.
     */
    object Loading : LibraryUiState

    /**
     * Root storage folder has not been selected in settings.
     */
    object RootNotConfigured : LibraryUiState

    /**
     * SAF storage access permission is missing, revoked, or root is inaccessible.
     */
    data class PermissionDenied(val message: String) : LibraryUiState

    /**
     * Storage root is accessible, but `Comics` directory does not exist inside it.
     */
    object ComicsDirectoryNotFound : LibraryUiState

    /**
     * `Comics` directory exists, but contains zero comic subfolders.
     */
    object EmptyComicsDirectory : LibraryUiState

    /**
     * Comic folders exist in `Comics`, but zero folders contain valid chapters.
     */
    object NoValidChapters : LibraryUiState

    /**
     * Collection loaded successfully with one or more comic entities, grid column preference, and optional issues count.
     */
    data class Success(
        val rootUriString: String,
        val comics: List<ComicEntity>,
        val gridColumns: Int = 3,
        val issuesCount: Int = 0
    ) : LibraryUiState

    /**
     * Error state when observing or scanning the library fails.
     */
    data class Error(val message: String) : LibraryUiState
}
