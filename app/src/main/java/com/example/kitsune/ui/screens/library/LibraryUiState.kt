package com.example.kitsune.ui.screens.library

import com.example.kitsune.data.entity.ComicEntity

enum class SortOption(val displayName: String) {
    TITLE_ASC("Nama (A-Z)"),
    TITLE_DESC("Nama (Z-A)"),
    LAST_READ_DESC("Terakhir Dibaca")
}

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
     * Collection loaded successfully with matching comic entities, grid column preference, search query, sorting, and optional issues count.
     */
    data class Success(
        val rootUriString: String,
        val comics: List<ComicEntity>,
        val bookmarkedComicIds: Set<Long> = emptySet(),
        val gridColumns: Int = 3,
        val issuesCount: Int = 0,
        val searchQuery: String = "",
        val sortOption: SortOption = SortOption.TITLE_ASC,
        val isBookmarkedOnly: Boolean = false,
        val selectedComicIds: Set<Long> = emptySet(),
        val isSelectionMode: Boolean = false
    ) : LibraryUiState

    /**
     * Search query or active filter did not match any comic titles in the collection.
     */
    data class SearchNoResults(
        val rootUriString: String,
        val bookmarkedComicIds: Set<Long> = emptySet(),
        val searchQuery: String = "",
        val gridColumns: Int = 3,
        val sortOption: SortOption = SortOption.TITLE_ASC,
        val isBookmarkedOnly: Boolean = false
    ) : LibraryUiState

    /**
     * Error state when observing or scanning the library fails.
     */
    data class Error(val message: String) : LibraryUiState
}
