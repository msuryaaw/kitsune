package com.kitsune.app.ui.comicdetail

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.metadata.MediaMetadata
import com.kitsune.app.data.metadata.MetadataManager
import com.kitsune.app.data.repository.BookmarkRepository
import com.kitsune.app.data.repository.BookmarkWithCount
import com.kitsune.app.data.repository.ReadingProgressRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Chapter
import com.kitsune.app.domain.model.Comic
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel untuk mengelola data detail sebuah komik.
 * REVISION 10.5.8: Optimized settings retrieval using memory cache.
 * REVISION 11.1.10: Integrated Search Tag Index synchronization after metadata write.
 */
class ComicDetailViewModel(
    private val comicRelativePath: String,
    private val scannerRepository: ScannerRepository,
    private val settingsRepository: SettingsRepository,
    private val progressRepository: ReadingProgressRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val metadataManager: MetadataManager
) : ViewModel() {

    private val _comic = MutableStateFlow<Comic?>(null)
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    private val _metadata = MutableStateFlow(MediaMetadata())

    private val _availableBookmarks = MutableStateFlow<List<BookmarkWithMembership>>(emptyList())
    val availableBookmarks: StateFlow<List<BookmarkWithMembership>> = _availableBookmarks.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    val isBookmarked: StateFlow<Boolean> = bookmarkRepository.getBookmarkIdsForComic(comicRelativePath)
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val uiState: StateFlow<ComicDetailUiState> = combine(
        _comic,
        _chapters,
        progressRepository.getProgressByComic(comicRelativePath),
        _metadata
    ) { comic, chapters, progress, meta ->
        if (comic == null) {
            ComicDetailUiState.Loading
        } else {
            ComicDetailUiState.Success(
                comic = comic,
                chapters = chapters,
                progress = progress,
                metadata = meta
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ComicDetailUiState.Loading
    )

    init {
        loadInitialData()
        loadAvailableCollections()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                val comic = scannerRepository.getComicByPath(comicRelativePath)
                if (comic == null) return@launch
                _comic.value = comic

                // REVISION 10.5.8: Use cached settings to avoid extra DB query on navigation
                val settings = settingsRepository.getSettingsCached()
                val rootUriString = settings?.rootFolderUri ?: return@launch
                val rootUri = rootUriString.toUri()

                _chapters.value = scannerRepository.getChapters(rootUri, comicRelativePath)
                
                launch {
                    val meta = metadataManager.readMetadata(rootUri, comicRelativePath)
                    _metadata.value = meta.copy(tags = meta.tags.sortedBy { it.lowercase() })
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addTag(tagName: String) {
        viewModelScope.launch {
            val settings = settingsRepository.getSettingsCached()
            val rootUri = settings?.rootFolderUri?.toUri() ?: return@launch

            val updatedMetadata = _metadata.value.copy(
                tags = (_metadata.value.tags + tagName).sortedBy { it.lowercase() }
            )

            val result = metadataManager.writeMetadata(rootUri, comicRelativePath, updatedMetadata)
            if (result.isSuccess) {
                // SINKRONISASI SEARCH INDEX (REVISION 11.1.10)
                scannerRepository.updateComicSearchTags(comicRelativePath, updatedMetadata.tags)
                _metadata.value = updatedMetadata
            } else {
                _snackbarMessage.emit("Failed to save tag")
            }
        }
    }

    fun removeTag(tagName: String) {
        viewModelScope.launch {
            val settings = settingsRepository.getSettingsCached()
            val rootUri = settings?.rootFolderUri?.toUri() ?: return@launch

            val updatedMetadata = _metadata.value.copy(
                tags = _metadata.value.tags.filter { it != tagName }.sortedBy { it.lowercase() }
            )

            val result = metadataManager.writeMetadata(rootUri, comicRelativePath, updatedMetadata)
            if (result.isSuccess) {
                // SINKRONISASI SEARCH INDEX (REVISION 11.1.10)
                scannerRepository.updateComicSearchTags(comicRelativePath, updatedMetadata.tags)
                _metadata.value = updatedMetadata
            } else {
                _snackbarMessage.emit("Failed to remove tag")
            }
        }
    }

    fun toggleEditMode() {
        _isEditMode.value = !_isEditMode.value
    }

    private fun loadAvailableCollections() {
        combine(
            bookmarkRepository.getAllBookmarksWithCount(),
            bookmarkRepository.getBookmarkIdsForComic(comicRelativePath)
        ) { allBookmarks, memberIds ->
            allBookmarks.map { bookmarkWithCount ->
                BookmarkWithMembership(
                    bookmark = bookmarkWithCount,
                    isMember = memberIds.contains(bookmarkWithCount.bookmark.id)
                )
            }
        }.onEach { 
            _availableBookmarks.value = it 
        }.launchIn(viewModelScope)
    }

    fun toggleBookmarkMembership(bookmarkId: Long, isMember: Boolean) {
        viewModelScope.launch {
            if (isMember) {
                bookmarkRepository.removeComicFromBookmark(bookmarkId, comicRelativePath)
            } else {
                bookmarkRepository.addComicToBookmark(bookmarkId, comicRelativePath)
            }
        }
    }
}

data class BookmarkWithMembership(
    val bookmark: BookmarkWithCount,
    val isMember: Boolean
)

sealed class ComicDetailUiState {
    data object Loading : ComicDetailUiState()
    data class Success(
        val comic: Comic,
        val chapters: List<Chapter>,
        val progress: com.kitsune.app.database.entity.ReadingProgressEntity?,
        val metadata: MediaMetadata
    ) : ComicDetailUiState()
    data class Error(val message: String) : ComicDetailUiState()
}
