package com.kitsune.app.ui.local

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LocalUiState {
    data object Success : LocalUiState
}

/**
 * ViewModel for Local Screen.
 * Simplified to act as a navigation hub for History and Libraries.
 */
class LocalViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<LocalUiState>(LocalUiState.Success)
    val uiState: StateFlow<LocalUiState> = _uiState.asStateFlow()
}
