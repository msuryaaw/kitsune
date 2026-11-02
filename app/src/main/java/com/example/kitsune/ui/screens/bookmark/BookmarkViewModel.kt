package com.example.kitsune.ui.screens.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModel(
    private val libraryRepository: LibraryRepository,
    private val rootFolderPreferences: RootFolderPreferences,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val selectedCollectionId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<BookmarkUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf(BookmarkUiState.RootNotConfigured)
            } else {
                combine(
                    libraryRepository.observeAllBookmarkCollections(),
                    selectedCollectionId,
                    settingsRepository.observeSettings()
                ) { collections, selectedId, settings ->
                    Triple(collections, selectedId, settings)
                }.flatMapLatest { (collections, selectedId, settings) ->
                    if (collections.isEmpty()) {
                        flowOf(BookmarkUiState.Empty)
                    } else {
                        val activeCollection = collections.firstOrNull { it.id == selectedId } ?: collections.first()
                        libraryRepository.observeComicsInBookmarkCollection(activeCollection.id, rootUri)
                            .combine(flowOf(settings)) { comics, currentSettings ->
                                if (collections.size == 1 && collections.first().name == "Favorit" && comics.isEmpty()) {
                                    BookmarkUiState.Empty
                                } else {
                                    BookmarkUiState.Success(
                                        rootUriString = rootUri,
                                        collections = collections,
                                        selectedCollection = activeCollection,
                                        bookmarkedComics = comics,
                                        gridColumns = currentSettings.gridColumns.coerceIn(2, 4)
                                    )
                                }
                            }
                    }
                }
            }
        }
        .catch { throwable ->
            emit(
                BookmarkUiState.Error(
                    throwable.localizedMessage ?: "Gagal memuat daftar bookmark."
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BookmarkUiState.Loading
        )

    fun selectCollection(bookmarkId: Long) {
        selectedCollectionId.value = bookmarkId
    }

    fun createCollection(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                val newId = libraryRepository.createBookmarkCollection(trimmed)
                selectedCollectionId.value = newId
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
                if (selectedCollectionId.value == bookmarkId) {
                    selectedCollectionId.value = null
                }
            } catch (_: Exception) {}
        }
    }

    fun removeComicFromCollection(bookmarkId: Long, comicId: Long) {
        viewModelScope.launch {
            try {
                libraryRepository.removeComicFromCollection(bookmarkId, comicId)
            } catch (_: Exception) {}
        }
    }

    fun toggleBookmark(comicId: Long, currentIsBookmarked: Boolean) {
        viewModelScope.launch {
            try {
                libraryRepository.setBookmarked(comicId, !currentIsBookmarked)
            } catch (_: Exception) {}
        }
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch {
            settingsRepository.setGridColumns(columns)
        }
    }

    suspend fun getCoverFile(comicId: Long): CoverCacheResult {
        return libraryRepository.getCoverFile(comicId)
    }

    suspend fun releaseCoverFile(file: File) {
        libraryRepository.releaseCoverFile(file)
    }

    class Factory(
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BookmarkViewModel::class.java)) {
                return BookmarkViewModel(libraryRepository, rootFolderPreferences, settingsRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
