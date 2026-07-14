package com.kitsune.app.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.ReadingProgressRepository
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.domain.model.LastReadComic
import com.kitsune.app.domain.model.LastWatchedVideo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface LocalUiState {
    object Loading : LocalUiState
    data class Success(
        val lastRead: LastReadComic?,
        val lastWatched: LastWatchedVideo?
    ) : LocalUiState
    data class Error(val message: String) : LocalUiState
}

class LocalViewModel(
    private val progressRepository: ReadingProgressRepository,
    private val videoRepository: VideoRepository
) : ViewModel() {

    val uiState: StateFlow<LocalUiState> = combine(
        progressRepository.getLatestReadComic(),
        videoRepository.getLatestWatchedVideo()
    ) { lastRead, lastWatched ->
        LocalUiState.Success(lastRead, lastWatched)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LocalUiState.Loading
    )
}
