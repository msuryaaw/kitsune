package com.kitsune.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.domain.model.LastWatchedVideo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface WatchHistoryUiState {
    data object Loading : WatchHistoryUiState
    data class Success(val history: List<LastWatchedVideo>) : WatchHistoryUiState
    data class Error(val message: String) : WatchHistoryUiState
}

class WatchHistoryViewModel(
    private val videoRepository: VideoRepository
) : ViewModel() {

    val uiState: StateFlow<WatchHistoryUiState> = videoRepository.getFullWatchHistory()
        .map { WatchHistoryUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = WatchHistoryUiState.Loading
        )
}
