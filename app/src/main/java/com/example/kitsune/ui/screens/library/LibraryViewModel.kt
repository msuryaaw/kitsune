package com.example.kitsune.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
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

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val rootFolderPreferences: RootFolderPreferences,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf(LibraryUiState.RootNotConfigured)
            } else {
                libraryRepository.observeComicsByRoot(rootUri)
                    .combine(settingsRepository.observeSettings()) { comics, settings ->
                        if (comics.isEmpty()) {
                            LibraryUiState.EmptyComics(rootUri)
                        } else {
                            LibraryUiState.Success(
                                rootUriString = rootUri,
                                comics = comics,
                                gridColumns = settings.gridColumns.coerceIn(2, 4)
                            )
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

    fun setGridColumns(columns: Int) {
        viewModelScope.launch {
            settingsRepository.setGridColumns(columns)
        }
    }

    class Factory(
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                return LibraryViewModel(libraryRepository, rootFolderPreferences, settingsRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
