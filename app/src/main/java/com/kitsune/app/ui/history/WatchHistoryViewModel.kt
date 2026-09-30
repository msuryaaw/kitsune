package com.kitsune.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.core.DateUtils
import com.kitsune.app.core.HistoryTimeGroup
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.domain.model.LastWatchedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface WatchHistoryUiState {
    data object Loading : WatchHistoryUiState
    data class Success(
        val history: List<LastWatchedVideo>,
        val groupedHistory: Map<HistoryTimeGroup, List<LastWatchedVideo>> = emptyMap()
    ) : WatchHistoryUiState
    data class Error(val message: String) : WatchHistoryUiState
}

class WatchHistoryViewModel(
    private val videoRepository: VideoRepository
) : ViewModel() {

    val uiState: StateFlow<WatchHistoryUiState> = videoRepository.getFullWatchHistory()
        .map { list ->
            val grouped = list.groupBy { item ->
                DateUtils.getHistoryTimeGroup(item.lastWatchedAt)
            }.filterValues { it.isNotEmpty() }

            WatchHistoryUiState.Success(
                history = list,
                groupedHistory = grouped
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = WatchHistoryUiState.Loading
        )

    /**
     * Removes a single video entry from watch history (TASK-05).
     */
    fun deleteWatchHistory(videoPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            videoRepository.deleteVideoProgress(videoPath)
        }
    }
}
