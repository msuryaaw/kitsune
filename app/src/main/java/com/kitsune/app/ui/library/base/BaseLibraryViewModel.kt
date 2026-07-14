package com.kitsune.app.ui.library.base

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

/**
 * Base ViewModel untuk layar Library di Kitsune.
 * Menyediakan fondasi untuk fitur umum seperti Pencarian, Refreshing, dan Snackbar.
 */
abstract class BaseLibraryViewModel : ViewModel() {

    protected val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    protected val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(FlowPreview::class)
    protected val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    protected val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    /**
     * Mengubah query pencarian.
     */
    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
    }

    /**
     * Memicu pemindaian ulang library.
     * Implementasi spesifik ditentukan oleh subclass (Comic vs Video).
     */
    abstract fun refreshLibrary()
}
