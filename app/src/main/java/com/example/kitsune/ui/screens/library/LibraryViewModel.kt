package com.example.kitsune.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.scanner.ComicsDirectoryDetector
import com.example.kitsune.scanner.ComicsDirectoryState
import com.example.kitsune.scanner.LibraryScanResult
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

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val rootFolderPreferences: RootFolderPreferences,
    private val settingsRepository: SettingsRepository,
    private val comicsDirectoryDetector: ComicsDirectoryDetector
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<LibraryUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf(LibraryUiState.RootNotConfigured)
            } else {
                combine(
                    libraryRepository.observeComicsByRoot(rootUri),
                    settingsRepository.observeSettings(),
                    libraryRepository.observeLastScanResult(),
                    isRefreshing
                ) { comics, settings, scanResult, refreshing ->
                    if (refreshing) {
                        LibraryUiState.Loading
                    } else if (comics.isNotEmpty()) {
                        val issuesCount = (scanResult as? LibraryScanResult.Success)?.issues?.size ?: 0
                        LibraryUiState.Success(
                            rootUriString = rootUri,
                            comics = comics,
                            gridColumns = settings.gridColumns.coerceIn(2, 4),
                            issuesCount = issuesCount
                        )
                    } else {
                        mapEmptyComicsState(rootUri, scanResult)
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

    private fun mapEmptyComicsState(rootUri: String, scanResult: LibraryScanResult?): LibraryUiState {
        return when (scanResult) {
            is LibraryScanResult.RootNotConfigured -> LibraryUiState.RootNotConfigured
            is LibraryScanResult.PermissionDenied -> LibraryUiState.PermissionDenied(scanResult.message)
            is LibraryScanResult.ComicsDirectoryNotFound -> LibraryUiState.ComicsDirectoryNotFound
            is LibraryScanResult.EmptyComicsDirectory -> LibraryUiState.EmptyComicsDirectory
            is LibraryScanResult.Error -> LibraryUiState.Error(scanResult.exception.localizedMessage ?: "Gagal memindai koleksi komik.")
            else -> {
                when (val dirState = comicsDirectoryDetector.detectComicsDirectory()) {
                    is ComicsDirectoryState.RootNotConfigured -> LibraryUiState.RootNotConfigured
                    is ComicsDirectoryState.PermissionDenied -> LibraryUiState.PermissionDenied(dirState.message)
                    is ComicsDirectoryState.DirectoryNotFound, is ComicsDirectoryState.NotADirectory -> LibraryUiState.ComicsDirectoryNotFound
                    is ComicsDirectoryState.Empty -> LibraryUiState.EmptyComicsDirectory
                    is ComicsDirectoryState.Ready -> LibraryUiState.NoValidChapters
                    is ComicsDirectoryState.Error -> LibraryUiState.Error(dirState.exception.localizedMessage ?: "Gagal memeriksa direktori Comics.")
                }
            }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            val rootUri = rootFolderPreferences.getRootUriString()
            if (!rootUri.isNullOrBlank()) {
                isRefreshing.value = true
                try {
                    libraryRepository.syncLibrary(rootUri)
                } finally {
                    isRefreshing.value = false
                }
            }
        }
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch {
            settingsRepository.setGridColumns(columns)
        }
    }

    suspend fun getCoverFile(comicId: Long): com.example.kitsune.data.CoverCacheResult {
        return libraryRepository.getCoverFile(comicId)
    }

    suspend fun releaseCoverFile(file: java.io.File) {
        libraryRepository.releaseCoverFile(file)
    }

    class Factory(
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences,
        private val settingsRepository: SettingsRepository,
        private val comicsDirectoryDetector: ComicsDirectoryDetector
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                return LibraryViewModel(libraryRepository, rootFolderPreferences, settingsRepository, comicsDirectoryDetector) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
