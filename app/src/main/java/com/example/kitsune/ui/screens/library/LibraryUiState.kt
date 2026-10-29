package com.example.kitsune.ui.screens.library

import com.example.kitsune.data.entity.ComicEntity

/**
 * UI State for Library Screen.
 */
sealed interface LibraryUiState {
    /**
     * Root storage folder has not been selected in settings.
     */
    object RootNotConfigured : LibraryUiState

    /**
     * Initial loading state while setting up reactive stream.
     */
    object Loading : LibraryUiState

    /**
     * Root storage folder is configured, but no comics were found/indexed in the collection.
     */
    data class EmptyComics(val rootUriString: String) : LibraryUiState

    /**
     * Collection loaded successfully with one or more comic entities and grid column preference.
     */
    data class Success(
        val rootUriString: String,
        val comics: List<ComicEntity>,
        val gridColumns: Int = 3
    ) : LibraryUiState

    /**
     * Error state when observing the library fails.
     */
    data class Error(val message: String) : LibraryUiState
}
