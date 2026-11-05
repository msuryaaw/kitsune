package com.example.kitsune.ui.screens.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.data.entity.BookmarkEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Bookmark Landing Screen (Task 12.7 / K-36).
 * Manages user-created bookmark collections, ordering, and CRUD operations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModel(
    private val libraryRepository: LibraryRepository,
    private val rootFolderPreferences: RootFolderPreferences,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<BookmarkLandingUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf(BookmarkLandingUiState.RootNotConfigured)
            } else {
                libraryRepository.observeAllBookmarkCollections()
                    .map { collections ->
                        if (collections.isEmpty()) {
                            BookmarkLandingUiState.EmptyNoCollections
                        } else {
                            BookmarkLandingUiState.Success(collections)
                        }
                    }
            }
        }
        .catch { throwable ->
            emit(
                BookmarkLandingUiState.Error(
                    throwable.localizedMessage ?: "Failed to load bookmark collections."
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BookmarkLandingUiState.Loading
        )

    fun createCollection(name: String, onCreated: ((Long) -> Unit)? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                val newId = libraryRepository.createBookmarkCollection(trimmed)
                if (newId > 0 && onCreated != null) {
                    onCreated(newId)
                }
            } catch (_: Exception) {}
        }
    }

    fun renameCollection(bookmarkId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                libraryRepository.renameBookmarkCollection(bookmarkId, trimmed)
            } catch (_: Exception) {}
        }
    }

    fun deleteCollection(bookmarkId: Long) {
        viewModelScope.launch {
            try {
                libraryRepository.deleteBookmarkCollection(bookmarkId)
            } catch (_: Exception) {}
        }
    }

    fun reorderCollections(collectionsInOrder: List<BookmarkEntity>) {
        viewModelScope.launch {
            try {
                libraryRepository.reorderBookmarkCollections(collectionsInOrder)
            } catch (_: Exception) {}
        }
    }

    class Factory(
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookmarkViewModel(
                libraryRepository,
                rootFolderPreferences,
                settingsRepository
            ) as T
        }
    }
}
