package com.example.kitsune.ui.screens.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.data.entity.BookmarkEntity
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.scanner.NaturalOrderComparator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for Collection Detail Screen (Task 12.7 / K-36).
 * Scoped strictly to the active collection specified by [collectionId].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModel(
    val collectionId: Long,
    private val libraryRepository: LibraryRepository,
    private val rootFolderPreferences: RootFolderPreferences,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val selectedComicIds = MutableStateFlow<Set<Long>>(emptySet())
    val isSelectionMode = MutableStateFlow(false)
    val searchQuery = MutableStateFlow("")
    val statusFilter = MutableStateFlow(BookmarkStatusFilter.ALL)
    val sortOption = MutableStateFlow(BookmarkSortOption.TITLE_ASC)

    val uiState: StateFlow<CollectionDetailUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf(CollectionDetailUiState.RootNotConfigured)
            } else {
                combine(
                    libraryRepository.observeAllBookmarkCollections(),
                    settingsRepository.observeSettings(),
                    selectedComicIds,
                    isSelectionMode,
                    searchQuery,
                    statusFilter,
                    sortOption
                ) { flows: Array<Any?> ->
                    @Suppress("UNCHECKED_CAST")
                    val collections = flows[0] as List<BookmarkEntity>
                    val settings = flows[1] as com.example.kitsune.data.entity.SettingsEntity
                    @Suppress("UNCHECKED_CAST")
                    val selIds = flows[2] as Set<Long>
                    val selMode = flows[3] as Boolean
                    val query = flows[4] as String
                    val filter = flows[5] as BookmarkStatusFilter
                    val sort = flows[6] as BookmarkSortOption

                    Septet(collections, settings, selIds, selMode, query, filter, sort)
                }.flatMapLatest { (collections, settings, selIds, selMode, query, filter, sort) ->
                    val activeCollection = collections.firstOrNull { it.id == collectionId }
                    if (activeCollection == null) {
                        flowOf(CollectionDetailUiState.CollectionNotFound)
                    } else {
                        libraryRepository.observeComicsInBookmarkCollection(activeCollection.id, rootUri)
                            .flatMapLatest { comics ->
                                flow<CollectionDetailUiState> {
                                    val trimmedQuery = query.trim()
                                    val searchFiltered = if (trimmedQuery.isBlank()) {
                                        comics
                                    } else {
                                        comics.filter {
                                            it.title.contains(trimmedQuery, ignoreCase = true) ||
                                                (it.author?.contains(trimmedQuery, ignoreCase = true) == true)
                                        }
                                    }

                                    val statusFiltered = when (filter) {
                                        BookmarkStatusFilter.ALL -> searchFiltered
                                        BookmarkStatusFilter.COMPLETED -> searchFiltered.filter { isComicCompleted(it.id) }
                                        BookmarkStatusFilter.NOT_COMPLETED -> searchFiltered.filter { !isComicCompleted(it.id) }
                                    }

                                    val sortedComics = when (sort) {
                                        BookmarkSortOption.TITLE_ASC ->
                                            statusFiltered.sortedWith(
                                                Comparator<ComicEntity> { c1, c2 ->
                                                    NaturalOrderComparator.compare(c1.title, c2.title)
                                                }.thenBy { it.relativePath }
                                            )
                                        BookmarkSortOption.TITLE_DESC ->
                                            statusFiltered.sortedWith(
                                                Comparator<ComicEntity> { c1, c2 ->
                                                    NaturalOrderComparator.compare(c2.title, c1.title)
                                                }.thenBy { it.relativePath }
                                            )
                                        BookmarkSortOption.AUTHOR_ASC ->
                                            statusFiltered.sortedWith(
                                                Comparator<ComicEntity> { c1, c2 ->
                                                    val a1 = c1.author ?: ""
                                                    val a2 = c2.author ?: ""
                                                    NaturalOrderComparator.compare(a1, a2)
                                                }.thenBy { c1 -> c1.title }
                                            )
                                        BookmarkSortOption.AUTHOR_DESC ->
                                            statusFiltered.sortedWith(
                                                Comparator<ComicEntity> { c1, c2 ->
                                                    val a1 = c1.author ?: ""
                                                    val a2 = c2.author ?: ""
                                                    NaturalOrderComparator.compare(a2, a1)
                                                }.thenBy { c1 -> c1.title }
                                            )
                                    }

                                    emit(
                                        CollectionDetailUiState.Success(
                                            collection = activeCollection,
                                            comics = sortedComics,
                                            gridColumns = settings.gridColumns.coerceIn(2, 4),
                                            statusFilter = filter,
                                            sortOption = sort,
                                            searchQuery = query,
                                            selectedComicIds = selIds,
                                            isSelectionMode = selMode
                                        )
                                    )
                                }
                            }
                    }
                }
            }
        }
        .catch { throwable ->
            emit(
                CollectionDetailUiState.Error(
                    throwable.localizedMessage ?: "Failed to load collection details."
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CollectionDetailUiState.Loading
        )

    private suspend fun isComicCompleted(comicId: Long): Boolean {
        val progress = libraryRepository.getReadingProgress(comicId) ?: return false
        val chapters = libraryRepository.observeChaptersByComicId(comicId).first()
        if (chapters.isEmpty()) return false
        val finalChapter = chapters.maxByOrNull { it.chapterNumber } ?: return false
        return progress.lastReadChapterId == finalChapter.id && progress.pageIndex >= finalChapter.pageCount - 1
    }

    fun setStatusFilter(filter: BookmarkStatusFilter) {
        statusFilter.value = filter
    }

    fun setSortOption(sort: BookmarkSortOption) {
        sortOption.value = sort
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch {
            settingsRepository.setGridColumns(columns.coerceIn(2, 4))
        }
    }

    fun toggleComicSelection(comicId: Long) {
        val current = selectedComicIds.value
        if (current.contains(comicId)) {
            val updated = current - comicId
            selectedComicIds.value = updated
            if (updated.isEmpty()) {
                isSelectionMode.value = false
            }
        } else {
            selectedComicIds.value = current + comicId
            isSelectionMode.value = true
        }
    }

    fun selectAllComics(allComicIds: List<Long>) {
        selectedComicIds.value = allComicIds.toSet()
        if (allComicIds.isNotEmpty()) {
            isSelectionMode.value = true
        }
    }

    fun clearSelection() {
        selectedComicIds.value = emptySet()
        isSelectionMode.value = false
    }

    fun bulkRemoveFromCollection() {
        val idsToRemove = selectedComicIds.value.toList()
        if (idsToRemove.isEmpty()) return
        viewModelScope.launch {
            try {
                libraryRepository.removeComicsFromCollection(collectionId, idsToRemove)
            } catch (_: Exception) {} finally {
                clearSelection()
            }
        }
    }

    fun renameCollection(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                libraryRepository.renameBookmarkCollection(collectionId, trimmed)
            } catch (_: Exception) {}
        }
    }

    fun deleteCollection(onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                libraryRepository.deleteBookmarkCollection(collectionId)
                onDeleted()
            } catch (_: Exception) {}
        }
    }

    suspend fun getCoverFile(comicId: Long): CoverCacheResult =
        libraryRepository.getCoverFile(comicId)

    suspend fun releaseCoverFile(file: File) =
        libraryRepository.releaseCoverFile(file)

    class Factory(
        private val collectionId: Long,
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CollectionDetailViewModel(
                collectionId,
                libraryRepository,
                rootFolderPreferences,
                settingsRepository
            ) as T
        }
    }
}

private data class Septet<A, B, C, D, E, F, G>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F,
    val seventh: G
)

