package com.example.kitsune.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.ChapterAvailabilityResult
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.model.ComicWithProgress
import com.example.kitsune.scanner.LibraryScanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val libraryRepository: LibraryRepository,
    private val rootFolderPreferences: RootFolderPreferences
) : ViewModel() {

    private val eventChannel = Channel<HistoryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HistoryEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<HistoryUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf(HistoryUiState.RootNotConfigured)
            } else {
                combine(
                    libraryRepository.observeRecentlyReadWithProgressByRoot(rootUri),
                    libraryRepository.observeLastScanResult()
                ) { items, scanResult ->
                    if (scanResult is LibraryScanResult.PermissionDenied) {
                        HistoryUiState.PermissionDenied(scanResult.message)
                    } else if (items.isEmpty()) {
                        HistoryUiState.Empty
                    } else {
                        val groups = groupHistoryItemsByLocalDate(items)
                        if (groups.isEmpty()) {
                            HistoryUiState.Empty
                        } else {
                            HistoryUiState.Success(
                                rootUriString = rootUri,
                                groups = groups
                            )
                        }
                    }
                }
                .catch { throwable ->
                    emit(
                        HistoryUiState.Error(
                            throwable.localizedMessage ?: "Failed to load reading history."
                        )
                    )
                }
            }
        }
        .catch { throwable ->
            emit(
                HistoryUiState.Error(
                    throwable.localizedMessage ?: "Root storage error."
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HistoryUiState.Loading
        )

    fun onComicClicked(comicId: Long) {
        viewModelScope.launch {
            eventChannel.send(HistoryEvent.NavigateToComicDetail(comicId))
        }
    }

    fun onContinueReadingClicked(item: ComicWithProgress) {
        val lastChapterId = item.progress?.lastReadChapterId
        val comicId = item.comic.id

        if (lastChapterId == null || lastChapterId <= 0L) {
            viewModelScope.launch {
                eventChannel.send(HistoryEvent.NavigateToComicDetail(comicId))
            }
            return
        }

        viewModelScope.launch {
            try {
                when (val availability = libraryRepository.verifyChapterAvailable(lastChapterId)) {
                    is ChapterAvailabilityResult.Available -> {
                        eventChannel.send(HistoryEvent.NavigateToReader(lastChapterId))
                    }
                    is ChapterAvailabilityResult.Missing -> {
                        eventChannel.send(HistoryEvent.ShowFeedback("Chapter is missing. Opening comic detail."))
                        eventChannel.send(HistoryEvent.NavigateToComicDetail(comicId))
                    }
                    is ChapterAvailabilityResult.PermissionDenied -> {
                        eventChannel.send(HistoryEvent.ShowFeedback("Permission denied: ${availability.message}"))
                    }
                    is ChapterAvailabilityResult.Error -> {
                        eventChannel.send(HistoryEvent.ShowFeedback("Error checking chapter: ${availability.exception.localizedMessage}"))
                        eventChannel.send(HistoryEvent.NavigateToComicDetail(comicId))
                    }
                }
            } catch (e: Exception) {
                eventChannel.send(HistoryEvent.ShowFeedback("Failed to check chapter availability: ${e.localizedMessage}"))
                eventChannel.send(HistoryEvent.NavigateToComicDetail(comicId))
            }
        }
    }

    fun clearHistoryForComic(comicId: Long) {
        viewModelScope.launch {
            try {
                libraryRepository.clearHistoryForComic(comicId)
            } catch (e: Exception) {
                eventChannel.send(HistoryEvent.ShowFeedback("Failed to delete comic history: ${e.localizedMessage}"))
            }
        }
    }

    fun clearAllHistory(rootUri: String) {
        viewModelScope.launch {
            try {
                libraryRepository.clearAllHistoryByRoot(rootUri)
            } catch (e: Exception) {
                eventChannel.send(HistoryEvent.ShowFeedback("Failed to clear all history: ${e.localizedMessage}"))
            }
        }
    }

    suspend fun getCoverFile(comicId: Long): CoverCacheResult =
        libraryRepository.getCoverFile(comicId)

    suspend fun releaseCoverFile(file: File) {
        libraryRepository.releaseCoverFile(file)
    }

    companion object {
        fun groupHistoryItemsByLocalDate(
            items: List<ComicWithProgress>,
            nowMillis: Long = System.currentTimeMillis(),
            zoneId: ZoneId = ZoneId.systemDefault()
        ): List<HistoryGroup> {
            if (items.isEmpty()) return emptyList()

            val todayDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
            val yesterdayDate = todayDate.minusDays(1)

            val todayList = mutableListOf<ComicWithProgress>()
            val yesterdayList = mutableListOf<ComicWithProgress>()
            val earlierList = mutableListOf<ComicWithProgress>()

            for (item in items) {
                val lastReadAt = item.comic.lastReadAt ?: item.progress?.updatedAt ?: 0L
                if (lastReadAt <= 0L) {
                    earlierList.add(item)
                    continue
                }
                val itemDate = Instant.ofEpochMilli(lastReadAt).atZone(zoneId).toLocalDate()
                when {
                    itemDate.isEqual(todayDate) -> todayList.add(item)
                    itemDate.isEqual(yesterdayDate) -> yesterdayList.add(item)
                    else -> earlierList.add(item)
                }
            }

            val groups = mutableListOf<HistoryGroup>()
            if (todayList.isNotEmpty()) {
                groups.add(HistoryGroup(HistoryGroupHeader.TODAY, todayList))
            }
            if (yesterdayList.isNotEmpty()) {
                groups.add(HistoryGroup(HistoryGroupHeader.YESTERDAY, yesterdayList))
            }
            if (earlierList.isNotEmpty()) {
                groups.add(HistoryGroup(HistoryGroupHeader.EARLIER, earlierList))
            }
            return groups
        }
    }

    class Factory(
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(libraryRepository, rootFolderPreferences) as T
        }
    }
}
