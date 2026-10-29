package com.example.kitsune.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val rootFolderPreferences: RootFolderPreferences
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf(LibraryUiState.RootNotConfigured)
            } else {
                libraryRepository.observeComicsByRoot(rootUri)
                    .map { comics ->
                        if (comics.isEmpty()) {
                            LibraryUiState.EmptyComics(rootUri)
                        } else {
                            LibraryUiState.Success(rootUri, comics)
                        }
                    }
                    .catch { throwable ->
                        emit(
                            LibraryUiState.Error(
                                throwable.localizedMessage
                                    ?: "Error memuat daftar komik dari database."
                            )
                        )
                    }
            }
        }
        .catch { throwable ->
            emit(
                LibraryUiState.Error(
                    throwable.localizedMessage ?: "Error tidak terduga saat memantau status root."
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LibraryUiState.Loading
        )

    class Factory(
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                return LibraryViewModel(libraryRepository, rootFolderPreferences) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
