package com.kitsune.app.ui.reader

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.data.repository.ReaderRepository
import com.kitsune.app.data.repository.ReadingProgressRepository
import com.kitsune.app.data.repository.ScannerRepository
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.domain.model.Chapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ViewModel untuk mengelola logika layar Reader.
 * Mendukung pembacaan progres per chapter, pemantauan pengaturan mode baca, dan navigasi antar chapter.
 * Dioptimasi untuk transisi chapter yang mulus (Phase 6.7.4).
 */
class ReaderViewModel(
    private val comicRelativePath: String,
    private var currentChapterPath: String,
    private val readerRepository: ReaderRepository,
    private val settingsRepository: SettingsRepository,
    private val progressRepository: ReadingProgressRepository,
    private val scannerRepository: ScannerRepository,
    private val storageHelper: StorageHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private var chapters: List<Chapter> = emptyList()
    private var currentChapterIndex: Int = -1

    // OPTIMIZATION: Reading Progress Debounce (Phase 6.6.4.1)
    private var debounceSaveJob: Job? = null
    private var pendingProgressUpdate: ProgressUpdate? = null

    private data class ProgressUpdate(
        val pageNumber: Int,
        val totalPages: Int,
        val chapterPath: String
    )

    init {
        loadChapter(currentChapterPath)
        observeSettings()
    }

    private fun loadChapter(chapterPath: String) {
        viewModelScope.launch {
            // Force save progres chapter sebelumnya secara sinkron (suspend) sebelum pindah
            forceSaveSync()

            // OPTIMIZATION (Phase 6.7.4): Jangan reset ke Loading jika kita berpindah antar chapter.
            // Biarkan UI menampilkan chapter lama sampai data chapter baru siap (seamless transition).
            val isInitialLoad = _uiState.value !is ReaderUiState.Success
            
            val targetChapterName = chapterPath.substringAfterLast('/').removeSuffix(".cbz")
            
            if (isInitialLoad) {
                _uiState.value = ReaderUiState.Loading
            }

            try {
                val settings = settingsRepository.settings.first()
                val rootUriString = settings?.rootFolderUri
                val readingMode = settings?.readingMode ?: "Vertical"

                if (rootUriString.isNullOrEmpty()) {
                    _uiState.value = ReaderUiState.Error("Library not configured")
                    return@launch
                }

                val rootUri = rootUriString.toUri()
                
                if (chapters.isEmpty()) {
                    chapters = scannerRepository.getChapters(rootUri, comicRelativePath)
                }
                currentChapterIndex = chapters.indexOfFirst { it.relativePath == chapterPath }

                val chapterDoc = storageHelper.findFileByRelativePath(rootUri, chapterPath)

                if (chapterDoc == null || !chapterDoc.exists()) {
                    _uiState.value = ReaderUiState.Error("Chapter file not found")
                    return@launch
                }

                val uri = chapterDoc.uri
                
                val cacheKey = "${chapterPath}:${chapterDoc.lastModified()}"
                val pages = readerRepository.getPages(uri, cacheKey)
                
                if (pages.isEmpty()) {
                    _uiState.value = ReaderUiState.Empty
                } else {
                    currentChapterPath = chapterPath
                    val savedProgress = progressRepository.getProgressByChapterSync(chapterPath)
                    val startPage = savedProgress?.pageNumber?.coerceIn(1, pages.size) ?: 1
                    
                    _currentPage.value = startPage

                    // Update UI State secara atomik dengan data chapter baru.
                    // Penambahan chapterUri ke dalam Success state memungkinkan UI melakukan transisi 
                    // tanpa flicker atau reset state navigasi yang prematur.
                    _uiState.value = ReaderUiState.Success(
                        pages = pages,
                        chapterName = targetChapterName,
                        readingMode = readingMode,
                        chapterUri = uri
                    )
                    
                    // Initial save untuk posisi awal di chapter baru
                    saveProgress(startPage, pages.size)
                }
            } catch (e: Exception) {
                if (isInitialLoad) {
                    _uiState.value = ReaderUiState.Error("Failed to load chapter: ${e.message}")
                }
            }
        }
    }

    private fun observeSettings() {
        settingsRepository.settings
            .map { it?.readingMode ?: "Vertical" }
            .distinctUntilChanged()
            .onEach { mode ->
                val current = _uiState.value
                if (current is ReaderUiState.Success && current.readingMode != mode) {
                    _uiState.value = current.copy(readingMode = mode)
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Menyimpan progres membaca dengan mekanisme debounce.
     * Menggunakan structured concurrency via finally block untuk memastikan penyimpanan saat pembatalan.
     */
    fun saveProgress(pageNumber: Int, totalPages: Int) {
        _currentPage.value = pageNumber
        
        val update = ProgressUpdate(
            pageNumber = pageNumber,
            totalPages = totalPages,
            chapterPath = currentChapterPath
        )
        pendingProgressUpdate = update

        debounceSaveJob?.cancel()
        debounceSaveJob = viewModelScope.launch {
            try {
                delay(1000L) // Debounce 1 detik
                performSave(update)
                pendingProgressUpdate = null // Berhasil disimpan
            } finally {
                // Structured Concurrency: Jika coroutine dibatalkan (misal ViewModel hancur)
                // dan masih ada progres tertunda, simpan menggunakan NonCancellable.
                if (pendingProgressUpdate != null && !isActive) {
                    val lastUpdate = pendingProgressUpdate!!
                    pendingProgressUpdate = null
                    withContext(NonCancellable + Dispatchers.IO) {
                        performSave(lastUpdate)
                    }
                }
            }
        }
    }

    /**
     * Memaksa penyimpanan progres secara sinkron (suspend).
     * Digunakan saat perpindahan chapter agar chapter sebelumnya tersimpan sebelum memuat yang baru.
     */
    private suspend fun forceSaveSync() {
        val update = pendingProgressUpdate ?: return
        pendingProgressUpdate = null // Ambil kepemilikan update
        debounceSaveJob?.cancelAndJoin()
        performSave(update)
    }

    /**
     * Memaksa penyimpanan progres secara asinkron.
     * Digunakan oleh UI layer untuk flush data saat aplikasi masuk ke background atau user keluar.
     */
    fun forceSaveAsync() {
        val update = pendingProgressUpdate ?: return
        pendingProgressUpdate = null // Ambil kepemilikan update
        debounceSaveJob?.cancel()
        // Jalankan di scope ViewModel dengan NonCancellable agar tetap jalan meskipun scope dibatalkan sesaat kemudian.
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            performSave(update)
        }
    }

    private suspend fun performSave(update: ProgressUpdate) {
        try {
            progressRepository.saveProgress(
                comicRelativePath = comicRelativePath,
                chapterRelativePath = update.chapterPath,
                pageNumber = update.pageNumber,
                totalPages = update.totalPages
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCleared() {
        // Debounce job akan menangani force save terakhirnya sendiri di block finally 
        // berkat pengecekan !isActive and NonCancellable.
        super.onCleared()
    }

    fun navigateToNextChapter() {
        if (currentChapterIndex < chapters.size - 1) {
            val nextChapter = chapters[currentChapterIndex + 1]
            loadChapter(nextChapter.relativePath)
        }
    }

    fun navigateToPreviousChapter() {
        if (currentChapterIndex > 0) {
            val prevChapter = chapters[currentChapterIndex - 1]
            loadChapter(prevChapter.relativePath)
        }
    }

    fun hasNextChapter() = currentChapterIndex < chapters.size - 1
    fun hasPreviousChapter() = currentChapterIndex > 0
    
    fun jumpToPage(pageNumber: Int) {
        val current = _uiState.value
        if (current is ReaderUiState.Success) {
            val validatedPage = pageNumber.coerceIn(1, current.pages.size)
            _currentPage.value = validatedPage
            saveProgress(validatedPage, current.pages.size)
        }
    }
}
