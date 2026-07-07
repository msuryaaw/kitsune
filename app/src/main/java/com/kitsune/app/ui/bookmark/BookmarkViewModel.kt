package com.kitsune.app.ui.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.BookmarkWithCount
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola daftar kategori Bookmark.
 * REVISION 6.8.3: Fokus hanya pada manajemen kategori. Sorting komik dipindahkan ke DetailViewModel.
 */
class BookmarkViewModel(
    private val bookmarkRepository: BookmarkRepository
) : ViewModel() {

    /**
     * Daftar kategori bookmark dengan urutan normal (berdasarkan waktu pembuatan di DAO).
     */
    val categories: StateFlow<List<BookmarkWithCount>> = bookmarkRepository.getAllBookmarksWithCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createBookmark(name: String) {
        viewModelScope.launch {
            bookmarkRepository.createBookmark(name)
        }
    }

    fun renameBookmark(id: Long, newName: String) {
        viewModelScope.launch {
            bookmarkRepository.renameBookmark(id, newName)
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            bookmarkRepository.deleteBookmark(id)
        }
    }
}
