package com.kitsune.app.ui.library

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.kitsune.app.core.SearchUtils
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.database.entity.BookmarkEntity
import com.kitsune.app.ui.library.base.BaseLibraryViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola data pada layar Library Komik.
 * Menangani sinkronisasi antara Database dan Filesystem serta logika pencarian dan seleksi massal.
 */
class LibraryViewModel(
    scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkRepository: BookmarkRepository
) : BaseLibraryViewModel(scannerRepository) {

    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _sortOrder = MutableStateFlow(ComicSortOrder.TITLE_ASC)
    val sortOrder: StateFlow<ComicSortOrder> = _sortOrder.asStateFlow()

    /**
     * Observable set of bookmarked paths.
     */
    val bookmarkedPaths: StateFlow<Set<String>> = bookmarkRepository.getAllBookmarkedComics()
        .map { it.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Observable list of all bookmarks.
     */
    val allBookmarks: StateFlow<List<BookmarkEntity>> = bookmarkRepository.getAllBookmarksWithCount()
        .map { list -> list.map { it.bookmark } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Tahap 1: Pemfilteran & Pengurutan.
     * Melakukan pencarian berdasarkan judul bersih, penulis, bahasa, dan tag.
     * REVISION Masalah 3: Menggunakan Multi-token AND Search.
     */
    private val filteredComics = combine(
        scannerRepository.allComics,
        debouncedSearchQuery,
        _sortOrder
    ) { comics, query, order ->
        val filtered = if (query.isBlank()) {
            comics
        } else {
            comics.filter { comic ->
                SearchUtils.matches(
                    query = query,
                    searchableFields = listOf(
                        comic.displayTitle,
                        comic.author,
                        comic.language,
                        comic.type,
                        comic.searchTags
                    )
                )
            }
        }

        when (order) {
            ComicSortOrder.TITLE_ASC -> filtered.sortedBy { it.displayTitle.lowercase() }
            ComicSortOrder.TITLE_DESC -> filtered.sortedByDescending { it.displayTitle.lowercase() }
            ComicSortOrder.AUTHOR_ASC -> filtered.sortedBy { (it.author ?: "").lowercase() }
            ComicSortOrder.AUTHOR_DESC -> filtered.sortedByDescending { (it.author ?: "").lowercase() }
            ComicSortOrder.DATE_ADDED_DESC -> filtered.sortedByDescending { it.lastModified }
        }
    }.distinctUntilChanged()

    /**
     * Tahap 2: Pemetaan Status Visual (misal: apakah komik di-bookmark).
     */
    private val comicStatuses = combine(
        filteredComics,
        bookmarkedPaths
    ) { comics, bookmarks ->
        comics.associate { comic ->
            val path = comic.relativePath
            val hasBookmark = bookmarks.contains(path)
            
            val statuses = when {
                hasBookmark -> ComicStatusSets.BOOKMARKED
                else -> ComicStatusSets.EMPTY
            }
            path to statuses
        }
    }.distinctUntilChanged()

    /**
     * Tahap 3: Penggabungan akhir untuk UI State.
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        filteredComics,
        comicStatuses,
        settingsRepository.settings.map { it?.gridSize ?: 3 }.distinctUntilChanged(),
        isRefreshing,
        _errorMessage
    ) { comics, statuses, gridSize, refreshing, error ->
        val query = _searchQuery.value

        when {
            error != null -> LibraryUiState.Error(error)
            refreshing && comics.isEmpty() && query.isBlank() -> LibraryUiState.Loading
            comics.isEmpty() -> LibraryUiState.Empty
            else -> LibraryUiState.Success(
                comics = comics,
                comicStatuses = statuses,
                isRefreshing = refreshing,
                gridSize = gridSize
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState.Loading
    )

    init {
        refreshLibraryInternal(force = false) // Auto-scan on init with cooldown guard
    }

    fun selectAll() {
        val state = uiState.value
        if (state is LibraryUiState.Success) {
            _selectedPaths.value = state.comics.map { it.relativePath }.toSet()
        }
    }

    fun addSelectedToBookmarks(bookmarkIds: List<Long>) {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty() || bookmarkIds.isEmpty()) return
        
        viewModelScope.launch {
            bookmarkRepository.addComicsToBookmarks(bookmarkIds, paths)
            _snackbarMessage.emit("Added ${paths.size} comics to ${bookmarkIds.size} bookmarks.")
            clearSelection()
        }
    }

    suspend fun createBookmark(name: String): Long {
        return bookmarkRepository.createBookmark(name)
    }

    fun setSortOrder(order: ComicSortOrder) {
        _sortOrder.value = order
    }

    override fun refreshLibrary() {
        refreshLibraryInternal(force = true) // Manual refresh bypasses cooldown
    }

    private fun refreshLibraryInternal(force: Boolean) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                val settings = settingsRepository.getSettingsCached()
                val rootUriString = settings?.rootFolderUri
                
                if (rootUriString.isNullOrEmpty()) {
                    _errorMessage.value = "Root folder belum dikonfigurasi"
                    return@launch
                }

                // REVISION Masalah 4: Logic simplified as cooldown is now centralized in Repository
                scannerRepository.performIncrementalScan(rootUriString.toUri(), force = force)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to scan library: ${e.message}"
            }
        }
    }
}

sealed class LibraryUiState {
    data object Loading : LibraryUiState()
    data object Empty : LibraryUiState()
    data class Success(
        val comics: List<Comic>,
        val comicStatuses: Map<String, Set<ComicStatus>>,
        val isRefreshing: Boolean,
        val gridSize: Int
    ) : LibraryUiState()
    data class Error(val message: String) : LibraryUiState()
}
