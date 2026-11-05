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
 * UI State for the Bookmark Landing Screen (Task 12.7 / K-36).
 */
typealias BookmarkUiState = BookmarkLandingUiState

sealed interface BookmarkLandingUiState {
    object Loading : BookmarkLandingUiState
    object RootNotConfigured : BookmarkLandingUiState
    data class PermissionDenied(val message: String) : BookmarkLandingUiState
    object EmptyNoCollections : BookmarkLandingUiState
    data class Success(
        val collections: List<BookmarkEntity>
    ) : BookmarkLandingUiState
    data class Error(val message: String) : BookmarkLandingUiState

    companion object {
        val EmptyNoCollections = BookmarkLandingUiState.EmptyNoCollections
        fun Error(message: String) = BookmarkLandingUiState.Error(message)
    }
}

/**
 * UI State for Collection Detail Screen (Task 12.7 / K-36).
 */
sealed interface CollectionDetailUiState {
    object Loading : CollectionDetailUiState
    object RootNotConfigured : CollectionDetailUiState
    data class PermissionDenied(val message: String) : CollectionDetailUiState
    object CollectionNotFound : CollectionDetailUiState
    data class Success(
        val collection: BookmarkEntity,
        val comics: List<ComicEntity>,
        val gridColumns: Int = 3,
        val statusFilter: BookmarkStatusFilter = BookmarkStatusFilter.ALL,
        val sortOption: BookmarkSortOption = BookmarkSortOption.TITLE_ASC,
        val searchQuery: String = "",
        val selectedComicIds: Set<Long> = emptySet(),
        val isSelectionMode: Boolean = false
    ) : CollectionDetailUiState
    data class Error(val message: String) : CollectionDetailUiState
}
