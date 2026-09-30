package com.kitsune.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.core.DateUtils
import com.kitsune.app.core.HistoryTimeGroup
import com.kitsune.app.data.repository.ReadingProgressRepository
import com.kitsune.app.domain.model.LastReadComic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ReadHistoryUiState {
    data object Loading : ReadHistoryUiState
    data class Success(
        val history: List<LastReadComic>,
        val groupedHistory: Map<HistoryTimeGroup, List<LastReadComic>> = emptyMap()
    ) : ReadHistoryUiState
    data class Error(val message: String) : ReadHistoryUiState
}

class ReadHistoryViewModel(
    private val progressRepository: ReadingProgressRepository
) : ViewModel() {

    val uiState: StateFlow<ReadHistoryUiState> = progressRepository.getFullReadHistory()
        .map { list ->
            val grouped = list.groupBy { item ->
                DateUtils.getHistoryTimeGroup(item.progress.lastReadAt)
            }.filterValues { it.isNotEmpty() }

            ReadHistoryUiState.Success(
                history = list,
                groupedHistory = grouped
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ReadHistoryUiState.Loading
        )

    /**
     * Removes a single comic entry from read history (TASK-05).
     */
    fun deleteReadHistory(comicPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            progressRepository.deleteProgress(comicPath)
        }
    }
}
