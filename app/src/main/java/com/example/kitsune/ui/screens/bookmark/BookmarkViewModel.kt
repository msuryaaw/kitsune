package com.example.kitsune.ui.screens.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    val uiState: StateFlow<BookmarkUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf(BookmarkUiState.RootNotConfigured)
            } else {
                combine(
                    libraryRepository.observeBookmarkedComicsByRoot(rootUri),
                    settingsRepository.observeSettings()
                ) { bookmarkedComics, settings ->
                    if (bookmarkedComics.isEmpty()) {
                        BookmarkUiState.Empty
                    } else {
                        BookmarkUiState.Success(
                            rootUriString = rootUri,
                            bookmarkedComics = bookmarkedComics,
                            gridColumns = settings.gridColumns.coerceIn(2, 4)
                        )
                    }
                }.catch { throwable ->
                    emit(
                        BookmarkUiState.Error(
                            throwable.localizedMessage ?: "Gagal memuat daftar bookmark."
                        )
                    )
                }
            }
        }
        .catch { throwable ->
            emit(
                BookmarkUiState.Error(
                    throwable.localizedMessage ?: "Terjadi kesalahan pada status root."
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = BookmarkUiState.Loading
        )

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
