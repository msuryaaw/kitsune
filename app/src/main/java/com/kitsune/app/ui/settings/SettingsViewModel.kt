package com.kitsune.app.ui.settings

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.*
import com.kitsune.app.database.entity.SettingsEntity
import com.kitsune.app.domain.model.VideoStatistics
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola pengaturan aplikasi dan statistik penyimpanan.
 * REVISION 8.3.4: Added Video Watching History management.
 * REVISION 8.3.5: Integrated Video Statistics.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val scannerRepository: ScannerRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val playlistRepository: PlaylistRepository,
    private val progressRepository: ReadingProgressRepository,
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * REVISION 11.2.8: Connected to Global Scanning State via ScannerRepository.
     */
    val isRescanning: StateFlow<Boolean> = scannerRepository.isScanning
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                settingsRepository.settings,
                scannerRepository.allComics,
                bookmarkRepository.getAllBookmarksWithCount(),
                playlistRepository.getAllPlaylistsWithCount(),
                videoRepository.getVideoStatistics()
            ) { settings, comics, bookmarks, playlists, videoStats ->
                if (settings == null) {
                    SettingsUiState.Error("Settings not initialized")
                } else {
                    SettingsUiState.Success(
                        settings = settings,
                        comicCount = comics.size,
                        bookmarkCount = bookmarks.size,
                        playlistCount = playlists.size,
                        videoStatistics = videoStats
                    )
                }
            }.catch { e ->
                _uiState.value = SettingsUiState.Error(e.message ?: "Unknown error")
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun updateReadingMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.updateReadingMode(mode)
        }
    }

    fun updateGridSize(size: Int) {
        viewModelScope.launch {
            settingsRepository.updateGridSize(size)
        }
    }

    fun updateDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateDarkMode(enabled)
        }
    }

    fun updateOledBlack(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateOledBlack(enabled)
        }
    }

    /**
     * Memperbarui folder root dan memicu pemindaian ulang.
     */
    fun updateRootFolder(uri: String) {
        viewModelScope.launch {
            settingsRepository.updateRootFolderUri(uri)
            rescanLibrary()
        }
    }

    /**
     * Menghapus seluruh riwayat membaca.
     */
    fun clearReadingHistory() {
        viewModelScope.launch {
            progressRepository.clearAllHistory()
            _snackbarMessage.emit("Reading history cleared")
        }
    }

    /**
     * Menghapus seluruh riwayat menonton video (Phase 8.3.4).
     */
    fun clearWatchingHistory() {
        viewModelScope.launch {
            videoRepository.clearWatchingHistory()
            _snackbarMessage.emit("Watching history cleared")
        }
    }

    /**
     * Memicu pemindaian manual untuk mendeteksi perubahan di filesystem.
     * REVISION 11.2.9: Removed local flag management, relying on repository state.
     */
    fun rescanLibrary() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val rootUriString = settings?.rootFolderUri
            if (!rootUriString.isNullOrEmpty()) {
                try {
                    scannerRepository.performIncrementalScan(rootUriString.toUri())
                } catch (e: Exception) {
                    // Log error
                }
            }
        }
    }
}

sealed class SettingsUiState {
    data object Loading : SettingsUiState()
    data class Success(
        val settings: SettingsEntity,
        val comicCount: Int,
        val bookmarkCount: Int,
        val playlistCount: Int,
        val videoStatistics: VideoStatistics = VideoStatistics()
    ) : SettingsUiState()
    data class Error(val message: String) : SettingsUiState()
}
