package com.example.kitsune.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.core.RootFolderPreferences
import com.example.kitsune.data.CoverCacheResult
import com.example.kitsune.data.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val libraryRepository: LibraryRepository,
    private val rootFolderPreferences: RootFolderPreferences
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = rootFolderPreferences
        .observeRootUriString()
        .flatMapLatest { rootUri ->
            if (rootUri.isNullOrBlank()) {
                flowOf<HomeUiState>(HomeUiState.Success(lastReadItem = null, libraryCount = 0, historyCount = 0))
            } else {
                combine(
                    libraryRepository.observeComicsByRoot(rootUri),
                    libraryRepository.observeRecentlyReadWithProgressByRoot(rootUri),
                    libraryRepository.observeRecentlyReadByRoot(rootUri)
                ) { comics, recentlyReadWithProgress, recentlyReadHistory ->
                    Triple(comics, recentlyReadWithProgress, recentlyReadHistory)
                }.flatMapLatest { (comics, recentlyReadWithProgress, recentlyReadHistory) ->
                    flow<HomeUiState> {
                        val libraryCount = comics.size
                        val historyCount = recentlyReadHistory.size

                        val latestItem = recentlyReadWithProgress
                            .filter { it.progress != null }
                            .maxByOrNull { it.progress?.updatedAt ?: 0L }

                        val lastReadInfo = if (latestItem != null && latestItem.progress != null) {
                            val progress = latestItem.progress
                            val chapter = if (progress.lastReadChapterId != null) {
                                libraryRepository.getChapterById(progress.lastReadChapterId)
                            } else null

                            val totalPages = chapter?.pageCount ?: 0
                            LastReadInfo(
                                comic = latestItem.comic,
                                chapter = chapter,
                                progress = progress,
                                totalPages = totalPages
                            )
                        } else null

                        emit(
                            HomeUiState.Success(
                                lastReadItem = lastReadInfo,
                                libraryCount = libraryCount,
                                historyCount = historyCount
                            )
                        )
                    }
                }
            }
        }
        .catch { throwable ->
            emit(HomeUiState.Error(throwable.localizedMessage ?: "Failed to load Home data."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HomeUiState.Loading
        )

    suspend fun getCoverFile(comicId: Long): CoverCacheResult =
        libraryRepository.getCoverFile(comicId)

    class Factory(
        private val libraryRepository: LibraryRepository,
        private val rootFolderPreferences: RootFolderPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(libraryRepository, rootFolderPreferences) as T
        }
    }
}
