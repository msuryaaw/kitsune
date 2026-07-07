package com.kitsune.app.ui.settings

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.*
import com.kitsune.app.database.entity.SettingsEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola pengaturan aplikasi dan statistik penyimpanan.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val scannerRepository: ScannerRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val playlistRepository: PlaylistRepository,
    private val progressRepository: ReadingProgressRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _isRescanning = MutableStateFlow(false)
    val isRescanning: StateFlow<Boolean> = _isRescanning.asStateFlow()

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
                playlistRepository.getAllPlaylistsWithCount()
            ) { settings, comics, bookmarks, playlists ->
                if (settings == null) {
                    SettingsUiState.Error("Settings not initialized")
                } else {
                    SettingsUiState.Success(
                        settings = settings,
                        comicCount = comics.size,
                        bookmarkCount = bookmarks.size,
                        playlistCount = playlists.size
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
     * Memicu pemindaian manual untuk mendeteksi perubahan di filesystem.
     * REVISION 6.7.6: Ditambahkan guard agar rescan tidak berjalan paralel.
     */
    fun rescanLibrary() {
        if (_isRescanning.value) return

        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val rootUriString = settings?.rootFolderUri
            if (!rootUriString.isNullOrEmpty()) {
                _isRescanning.value = true
                try {
                    scannerRepository.performIncrementalScan(rootUriString.toUri())
                } catch (e: Exception) {
                    // Log error
                } finally {
                    _isRescanning.value = false
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
        val playlistCount: Int
    ) : SettingsUiState()
    data class Error(val message: String) : SettingsUiState()
}
