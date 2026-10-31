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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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
                kotlinx.coroutines.flow.combine(
                    libraryRepository.observeRecentlyReadWithProgressByRoot(rootUri),
                    libraryRepository.observeLastScanResult()
                ) { items, scanResult ->
                    if (scanResult is LibraryScanResult.PermissionDenied) {
                        HistoryUiState.PermissionDenied(scanResult.message)
                    } else if (items.isEmpty()) {
                        HistoryUiState.Empty
                    } else {
                        HistoryUiState.Success(
                            rootUriString = rootUri,
                            items = items
                        )
                    }
                }
                .catch { throwable ->
                    emit(
                        HistoryUiState.Error(
                            throwable.localizedMessage ?: "Gagal memuat riwayat membaca."
                        )
                    )
                }
            }
        }
        .catch { throwable ->
            emit(
                HistoryUiState.Error(
                    throwable.localizedMessage ?: "Terjadi kesalahan pada status root."
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
                        eventChannel.send(
                            HistoryEvent.ShowFeedback("File chapter sebelumnya tidak ditemukan. Membuka detail komik.")
                        )
                        eventChannel.send(HistoryEvent.NavigateToComicDetail(comicId))
                    }
                    is ChapterAvailabilityResult.PermissionDenied -> {
                        eventChannel.send(
                            HistoryEvent.ShowFeedback("Izin akses terputus: ${availability.message}")
                        )
                    }
                    is ChapterAvailabilityResult.Error -> {
                        eventChannel.send(
                            HistoryEvent.ShowFeedback("Gagal memeriksa ketersediaan chapter. Membuka detail komik.")
                        )
                        eventChannel.send(HistoryEvent.NavigateToComicDetail(comicId))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                eventChannel.send(HistoryEvent.NavigateToComicDetail(comicId))
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
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
                return HistoryViewModel(libraryRepository, rootFolderPreferences) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
