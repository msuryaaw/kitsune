package com.example.kitsune.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.data.ChapterAvailabilityResult
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class ComicDetailViewModel(
    private val comicId: Long,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val eventChannel = Channel<ComicDetailEvent>(Channel.BUFFERED)
    val eventFlow: Flow<ComicDetailEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<ComicDetailUiState> = libraryRepository
        .observeComicById(comicId)
        .flatMapLatest { comic ->
            if (comic == null) {
                flowOf(ComicDetailUiState.ComicNotFound)
            } else {
                libraryRepository.observeChaptersByComicId(comicId)
                    .flatMapLatest { chapters ->
                        flow<ComicDetailUiState> {
                            if (chapters.isEmpty()) {
                                emit(ComicDetailUiState.EmptyChapters)
                            } else {
                                val progress = libraryRepository.getReadingProgress(comicId)
                                emit(
                                    ComicDetailUiState.Success(
                                        comic = comic,
                                        chapters = chapters,
                                        progress = progress
                                    )
                                )
                            }
                        }
                    }
            }
        }
        .catch { throwable ->
            emit(
                ComicDetailUiState.Error(
                    throwable.localizedMessage ?: "Failed to load comic detail."
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ComicDetailUiState.Loading
        )

    fun toggleBookmark() {
        val currentState = uiState.value
        if (currentState is ComicDetailUiState.Success) {
            val comic = currentState.comic
            viewModelScope.launch {
                try {
                    libraryRepository.setBookmarked(comic.id, !comic.isBookmarked)
                } catch (_: Exception) {}
            }
        }
    }

    fun observeAllBookmarkCollections(): Flow<List<com.example.kitsune.data.entity.BookmarkEntity>> =
        libraryRepository.observeAllBookmarkCollections()

    fun observeCollectionsForComic(): Flow<List<com.example.kitsune.data.entity.BookmarkEntity>> =
        libraryRepository.observeCollectionsForComic(comicId)

    fun updateComicCollections(selectedCollectionIds: Set<Long>) {
        viewModelScope.launch {
            try {
                val currentCollections = libraryRepository.getCollectionsForComic(comicId)
                val currentIds = currentCollections.map { it.id }.toSet()

                // Add to newly selected collections
                val toAdd = selectedCollectionIds - currentIds
                for (colId in toAdd) {
                    libraryRepository.addComicToCollection(colId, comicId)
                }

                // Remove from unselected collections
                val toRemove = currentIds - selectedCollectionIds
                for (colId in toRemove) {
                    libraryRepository.removeComicFromCollection(colId, comicId)
                }
            } catch (_: Exception) {}
        }
    }

    fun onChapterSelected(chapterId: Long) {
        val currentState = uiState.value
        if (currentState !is ComicDetailUiState.Success) return

        val targetChapter = currentState.chapters.firstOrNull { it.id == chapterId && it.comicId == comicId }
        if (targetChapter == null) {
            viewModelScope.launch {
                eventChannel.send(ComicDetailEvent.ShowFeedback("Chapter is not associated with this comic."))
            }
            return
        }

        viewModelScope.launch {
            try {
                when (val availability = libraryRepository.verifyChapterAvailable(chapterId)) {
                    is ChapterAvailabilityResult.Available -> {
                        eventChannel.send(ComicDetailEvent.NavigateToReader(chapterId))
                    }
                    is ChapterAvailabilityResult.Missing -> {
                        eventChannel.send(
                            ComicDetailEvent.ShowFeedback("Chapter file not found in storage.")
                        )
                    }
                    is ChapterAvailabilityResult.PermissionDenied -> {
                        eventChannel.send(
                            ComicDetailEvent.ShowFeedback("Storage permission disconnected: ${availability.message}")
                        )
                    }
                    is ChapterAvailabilityResult.Error -> {
                        eventChannel.send(
                            ComicDetailEvent.ShowFeedback("Failed to access chapter: ${availability.exception.localizedMessage}")
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                eventChannel.send(
                    ComicDetailEvent.ShowFeedback("Failed to check chapter availability: ${e.localizedMessage}")
                )
            }
        }
    }

    suspend fun getCoverFile(comicId: Long): CoverCacheResult {
        return libraryRepository.getCoverFile(comicId)
    }

    suspend fun releaseCoverFile(file: File) {
        libraryRepository.releaseCoverFile(file)
    }

    class Factory(
        private val comicId: Long,
        private val libraryRepository: LibraryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ComicDetailViewModel::class.java)) {
                return ComicDetailViewModel(comicId, libraryRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
