package com.kitsune.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.PlaylistRepository
import com.kitsune.app.data.repository.PlaylistWithCount
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola daftar kategori Playlist.
 * REVISION 6.8.3: Fokus hanya pada manajemen kategori. Sorting komik dipindahkan ke DetailViewModel.
 */
class PlaylistViewModel(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    /**
     * Daftar kategori playlist dengan urutan normal.
     */
    val categories: StateFlow<List<PlaylistWithCount>> = playlistRepository.getAllPlaylistsWithCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name)
        }
    }

    fun renamePlaylist(id: Long, newName: String) {
        viewModelScope.launch {
            playlistRepository.renamePlaylist(id, newName)
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(id)
        }
    }

    fun deletePlaylists(ids: List<Long>) {
        viewModelScope.launch {
            playlistRepository.deletePlaylists(ids)
        }
    }
}
