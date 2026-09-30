package com.kitsune.app.ui.library

import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.kitsune.app.core.SearchUtils
import com.kitsune.app.data.metadata.MetadataManager
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.ReadingProgressRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.database.entity.BookmarkEntity
import com.kitsune.app.ui.library.base.BaseLibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ComicStatusFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    IN_PROGRESS("In Progress"),
    FINISHED("Finished")
}

/**
 * ViewModel untuk mengelola data pada layar Library Komik.
 * Menangani sinkronisasi antara Database dan Filesystem serta logika pencarian dan seleksi massal.
 */
class LibraryViewModel(
    scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val progressRepository: ReadingProgressRepository,
    private val metadataManager: MetadataManager
) : BaseLibraryViewModel(scannerRepository) {

    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _sortOrder = MutableStateFlow(ComicSortOrder.TITLE_ASC)
    val sortOrder: StateFlow<ComicSortOrder> = _sortOrder.asStateFlow()

    // --- Interactive Filter States (TASK-03) ---

    private val _selectedStatusFilter = MutableStateFlow(ComicStatusFilter.ALL)
    val selectedStatusFilter: StateFlow<ComicStatusFilter> = _selectedStatusFilter.asStateFlow()

    private val _selectedTypeFilter = MutableStateFlow<String?>(null)
    val selectedTypeFilter: StateFlow<String?> = _selectedTypeFilter.asStateFlow()

    private val _selectedTagFilter = MutableStateFlow<String?>(null)
    val selectedTagFilter: StateFlow<String?> = _selectedTagFilter.asStateFlow()

    fun setStatusFilter(filter: ComicStatusFilter) {
        _selectedStatusFilter.value = filter
    }

    fun setTypeFilter(type: String?) {
        _selectedTypeFilter.value = if (_selectedTypeFilter.value.equals(type, ignoreCase = true)) null else type
    }

    fun setTagFilter(tag: String?) {
        _selectedTagFilter.value = if (_selectedTagFilter.value.equals(tag, ignoreCase = true)) null else tag
    }

    fun clearFilters() {
        _selectedStatusFilter.value = ComicStatusFilter.ALL
        _selectedTypeFilter.value = null
        _selectedTagFilter.value = null
    }

    /**
     * Dynamically extracted top popular tags from existing comics' searchTags in memory.
     */
    val popularTags: StateFlow<List<String>> = scannerRepository.allComics
        .map { comics ->
            comics.mapNotNull { it.searchTags }
                .flatMap { it.split(" ") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .groupingBy { it.lowercase() }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    data class ComicFilterState(
        val query: String,
        val order: ComicSortOrder,
        val status: ComicStatusFilter,
        val type: String?,
        val tag: String?
    )

    private val filterState = combine(
        debouncedSearchQuery,
        _sortOrder,
        _selectedStatusFilter,
        _selectedTypeFilter,
        _selectedTagFilter
    ) { query, order, status, type, tag ->
        ComicFilterState(query, order, status, type, tag)
    }.distinctUntilChanged()

    /**
     * Tahap 1: Pemfilteran & Pengurutan.
     * Melakukan pencarian berdasarkan judul bersih, penulis, bahasa, tag, serta filter interaktif (TASK-03).
     */
    private val filteredComics = combine(
        scannerRepository.allComics,
        filterState,
        progressRepository.getFullReadHistory()
    ) { comics, filter, history ->
        var list = if (filter.query.isBlank()) {
            comics
        } else {
            comics.filter { comic ->
                SearchUtils.matches(
                    query = filter.query,
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

        if (filter.type != null) {
            list = list.filter { comic ->
                comic.type?.equals(filter.type, ignoreCase = true) == true
            }
        }

        if (filter.tag != null) {
            list = list.filter { comic ->
                comic.searchTags?.contains(filter.tag, ignoreCase = true) == true
            }
        }

        if (filter.status != ComicStatusFilter.ALL) {
            val historyMap = history.associateBy { it.comic.relativePath }
            list = list.filter { comic ->
                val lastRead = historyMap[comic.relativePath]
                when (filter.status) {
                    ComicStatusFilter.UNREAD -> lastRead == null
                    ComicStatusFilter.IN_PROGRESS -> {
                        val progress = lastRead?.progress
                        progress != null && progress.pageNumber < progress.totalPages
                    }
                    ComicStatusFilter.FINISHED -> {
                        val progress = lastRead?.progress
                        progress != null && progress.pageNumber >= progress.totalPages
                    }
                    ComicStatusFilter.ALL -> true
                }
            }
        }

        when (filter.order) {
            ComicSortOrder.TITLE_ASC -> list.sortedBy { it.displayTitle.lowercase() }
            ComicSortOrder.TITLE_DESC -> list.sortedByDescending { it.displayTitle.lowercase() }
            ComicSortOrder.AUTHOR_ASC -> list.sortedBy { (it.author ?: "").lowercase() }
            ComicSortOrder.AUTHOR_DESC -> list.sortedByDescending { (it.author ?: "").lowercase() }
            ComicSortOrder.DATE_ADDED_DESC -> list.sortedByDescending { it.lastModified }
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
        // REVISION Masalah 1: Removed automatic scan on init. 
        // Data is now loaded purely from database on startup.
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

    /**
     * Adds tag(s) to all currently selected comics in Selection Mode (TASK-04).
     * Parses comma-separated input, normalizes tags, updates metadata.json atomically on Dispatchers.IO,
     * updates Room searchTags cache upon filesystem success, and handles cancellation gracefully.
     */
    fun addTagsToSelectedComics(rawTagInput: String) {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty()) return

        val newTagsToProcess = rawTagInput.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        if (newTagsToProcess.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepository.getSettingsCached()
                val rootUriString = settings?.rootFolderUri
                if (rootUriString.isNullOrEmpty()) {
                    _snackbarMessage.emit("Root folder not configured")
                    return@launch
                }
                val rootUri = rootUriString.toUri()

                var successCount = 0
                var failureCount = 0

                paths.forEach { path ->
                    ensureActive()

                    try {
                        val existingMeta = metadataManager.readMetadata(rootUri, path)

                        val existingTagLowerSet = existingMeta.tags.map { it.lowercase() }.toSet()
                        val tagsToAdd = newTagsToProcess.filter { it.lowercase() !in existingTagLowerSet }

                        if (tagsToAdd.isNotEmpty()) {
                            val mergedTags = (existingMeta.tags + tagsToAdd).sortedBy { it.lowercase() }
                            val updatedMeta = existingMeta.copy(tags = mergedTags)

                            val result = metadataManager.writeMetadata(rootUri, path, updatedMeta)
                            if (result.isSuccess) {
                                scannerRepository.updateComicSearchTags(path, mergedTags)
                                successCount++
                            } else {
                                failureCount++
                            }
                        } else {
                            successCount++
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failureCount++
                    }
                }

                ensureActive()

                val message = when {
                    failureCount == 0 -> "Added tags to $successCount comics"
                    successCount > 0 -> "Added tags to $successCount of ${paths.size} comics ($failureCount failed)"
                    else -> "Failed to add tags to selected comics"
                }

                _snackbarMessage.emit(message)
                clearSelection()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _snackbarMessage.emit("Bulk tagging failed: ${e.message}")
            }
        }
    }

    suspend fun createBookmark(name: String): Long {
        return bookmarkRepository.createBookmark(name)
    }

    fun setSortOrder(order: ComicSortOrder) {
        _sortOrder.value = order
    }

    override fun refreshLibrary() {
        refreshLibraryInternal() // Manual refresh
    }

    private fun refreshLibraryInternal() {
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
                scannerRepository.performIncrementalScan(rootUriString.toUri())
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
