package com.kitsune.app.ui.library.base

import com.kitsune.app.data.repository.ScannerRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

/**
 * Base ViewModel untuk layar Library di Kitsune.
 * Menyediakan fondasi untuk fitur umum seperti Pencarian, Refreshing, dan Seleksi.
 * REVISION 11.2.10: Connected to real scanning state via ScannerRepository.
 */
abstract class BaseLibraryViewModel(
    protected val scannerRepository: ScannerRepository
) : ViewModel() {

    /**
     * Observable refreshing state tied to engine.
     */
    val isRefreshing: StateFlow<Boolean> = scannerRepository.isScanning
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    protected val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(FlowPreview::class)
    protected val debouncedSearchQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    protected val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    // --- Selection State (Unified 8.3.3) ---

    protected val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    // --- Selection Methods ---

    fun toggleSelection(path: String) {
        val current = _selectedPaths.value
        if (current.contains(path)) {
            _selectedPaths.value = current - path
        } else {
            _selectedPaths.value = current + path
        }
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }
}
