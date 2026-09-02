package com.kitsune.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.data.repository.ReadingProgressRepository
import com.kitsune.app.domain.model.LastReadComic
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface ReadHistoryUiState {
    data object Loading : ReadHistoryUiState
    data class Success(val history: List<LastReadComic>) : ReadHistoryUiState
    data class Error(val message: String) : ReadHistoryUiState
}

class ReadHistoryViewModel(
    private val progressRepository: ReadingProgressRepository
) : ViewModel() {

    val uiState: StateFlow<ReadHistoryUiState> = progressRepository.getFullReadHistory()
        .map { ReadHistoryUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ReadHistoryUiState.Loading
        )
}
