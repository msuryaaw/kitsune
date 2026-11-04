package com.example.kitsune.ui.screens.bookmark

import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.data.entity.ComicEntity

enum class BookmarkSortOption(val displayName: String) {
    TITLE_ASC("Title (A-Z)"),
    TITLE_DESC("Title (Z-A)"),
    AUTHOR_ASC("Author (A-Z)"),
    AUTHOR_DESC("Author (Z-A)")
}

enum class BookmarkStatusFilter(val displayName: String) {
    ALL("All"),
    COMPLETED("Completed"),
    NOT_COMPLETED("Not Completed")
}

/**
 * UI State for the Bookmark Screen (Phase 11).
 */
sealed interface BookmarkUiState {
    object Loading : BookmarkUiState
    object RootNotConfigured : BookmarkUiState
    data class PermissionDenied(val message: String) : BookmarkUiState
    object EmptyNoCollections : BookmarkUiState
    data class Success(
        val rootUriString: String,
        val collections: List<BookmarkEntity>,
        val selectedCollection: BookmarkEntity?,
        val bookmarkedComics: List<ComicEntity>,
        val gridColumns: Int = 3,
        val statusFilter: BookmarkStatusFilter = BookmarkStatusFilter.ALL,
        val sortOption: BookmarkSortOption = BookmarkSortOption.TITLE_ASC,
        val searchQuery: String = "",
        val selectedComicIds: Set<Long> = emptySet(),
        val isSelectionMode: Boolean = false
    ) : BookmarkUiState
    data class Error(val message: String) : BookmarkUiState
}
