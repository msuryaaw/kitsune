package com.example.kitsune.ui.screens.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ReadingProgressEntity
import com.example.kitsune.reader.CbzArchiveHandle
import com.example.kitsune.reader.CbzArchiveResult
import com.example.kitsune.reader.CbzPageDecoder
import com.example.kitsune.reader.CbzPageDecoderFactory
import com.example.kitsune.reader.CbzPageIndexer
import com.example.kitsune.reader.MonotonicTimestampGenerator
import com.example.kitsune.reader.PageDecodeResult
import com.example.kitsune.reader.PageIndexResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class ReaderViewModel(
    private val chapterId: Long,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val cbzPageIndexer: CbzPageIndexer,
    private val cbzPageDecoderFactory: CbzPageDecoderFactory
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    @Volatile
    private var activeDecoder: CbzPageDecoder? = null

    private var activeChapter: ChapterEntity? = null
    private val timestampGenerator = MonotonicTimestampGenerator()
    private val latestProgressSnapshot = AtomicReference<ReadingProgressEntity?>(null)
    private var progressSaveJob: Job? = null

    init {
        loadChapterAndInitialize()
    }

    private fun loadChapterAndInitialize() {
        viewModelScope.launch {
            _uiState.value = ReaderUiState.Loading
            var tempHandle: CbzArchiveHandle? = null

            try {
                val chapter = libraryRepository.getChapterById(chapterId)
                activeChapter = chapter

                when (val openResult = libraryRepository.openCbzArchive(chapterId)) {
                    is CbzArchiveResult.Success -> {
                        tempHandle = openResult.handle
                        when (val indexResult = cbzPageIndexer.buildIndex(chapterId, tempHandle.file)) {
                            is PageIndexResult.Success -> {
                                val decoder = cbzPageDecoderFactory.createDecoder(indexResult.pageIndex, tempHandle)
                                activeDecoder = decoder
                                tempHandle = null // Transfer of ownership complete!

                                val comicId = chapter?.comicId ?: 0L
                                val savedProgress = if (comicId > 0L) libraryRepository.getReadingProgress(comicId) else null

                                var initialPageIndex = 0
                                var initialScrollOffset = 0

                                if (savedProgress != null && savedProgress.lastReadChapterId == chapterId) {
                                    val totalPages = indexResult.pageIndex.totalPages
                                    if (totalPages > 0) {
                                        initialPageIndex = savedProgress.pageIndex.coerceIn(0, totalPages - 1)
                                        initialScrollOffset = savedProgress.scrollOffset
                                    }
                                    timestampGenerator.seed(savedProgress.updatedAt)
                                }

                                // Observe settings for readerMode & readingDirection
                                settingsRepository.observeSettings().collect { settings ->
                                    val currentState = _uiState.value
                                    val currentPos = if (currentState is ReaderUiState.Success) currentState.currentPageIndex else initialPageIndex
                                    val currentOffset = if (currentState is ReaderUiState.Success) currentState.scrollOffset else initialScrollOffset

                                    _uiState.value = ReaderUiState.Success(
                                        chapterId = chapterId,
                                        pageIndex = indexResult.pageIndex,
                                        readerMode = settings.readerMode,
                                        readingDirection = settings.readingDirection,
                                        currentPageIndex = currentPos,
                                        scrollOffset = currentOffset
                                    )
                                }
                            }
                            is PageIndexResult.NoImagesFound -> {
                                _uiState.value = ReaderUiState.Error("Tidak ada halaman gambar ditemukan dalam chapter")
                            }
                            is PageIndexResult.CorruptArchive -> {
                                _uiState.value = ReaderUiState.Error("Arsip chapter rusak: ${indexResult.message}")
                            }
                            is PageIndexResult.LimitExceeded -> {
                                _uiState.value = ReaderUiState.Error("Batas keamanan arsip terlampaui: ${indexResult.reason}")
                            }
                            is PageIndexResult.StorageError -> {
                                _uiState.value = ReaderUiState.Error("Gagal membaca indeks chapter: ${indexResult.exception.localizedMessage}")
                            }
                        }
                    }
                    is CbzArchiveResult.ChapterMissing -> {
                        _uiState.value = ReaderUiState.Error("File chapter tidak ditemukan di penyimpanan")
                    }
                    is CbzArchiveResult.PermissionDenied -> {
                        _uiState.value = ReaderUiState.Error("Izin akses penyimpanan terputus: ${openResult.message}")
                    }
                    is CbzArchiveResult.ArchiveTooLarge -> {
                        _uiState.value = ReaderUiState.Error("Ukuran arsip melebihi batas 500 MB")
                    }
                    is CbzArchiveResult.SpoolBudgetExceeded -> {
                        _uiState.value = ReaderUiState.Error("Kapasitas cache reader penuh (1.5 GB)")
                    }
                    is CbzArchiveResult.StorageError -> {
                        _uiState.value = ReaderUiState.Error("Gagal membuka arsip chapter: ${openResult.exception.localizedMessage}")
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.value = ReaderUiState.Error("Gagal mempersiapkan reader: ${e.localizedMessage}")
            } finally {
                tempHandle?.close()
            }
        }
    }

    suspend fun decodePage(pageIndex: Int, targetWidth: Int, targetHeight: Int): PageDecodeResult {
        val decoder = activeDecoder ?: return PageDecodeResult.ArchiveClosed
        return decoder.decodePage(pageIndex, targetWidth, targetHeight)
    }

    fun setReaderMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.setReaderMode(mode)
        }
    }

    fun setReadingDirection(direction: String) {
        viewModelScope.launch {
            settingsRepository.setReadingDirection(direction)
        }
    }

    fun goToNextPage() {
        val state = _uiState.value
        if (state is ReaderUiState.Success) {
            val totalPages = state.pageIndex.totalPages
            if (totalPages <= 0) return
            val maxIndex = totalPages - 1
            if (state.currentPageIndex < maxIndex) {
                setCurrentPageIndexAndOffset(state.currentPageIndex + 1, 0)
            }
        }
    }

    fun goToPreviousPage() {
        val state = _uiState.value
        if (state is ReaderUiState.Success) {
            val totalPages = state.pageIndex.totalPages
            if (totalPages <= 0) return
            if (state.currentPageIndex > 0) {
                setCurrentPageIndexAndOffset(state.currentPageIndex - 1, 0)
            }
        }
    }

    fun setCurrentPageIndex(index: Int) {
        setCurrentPageIndexAndOffset(index, 0)
    }

    fun setCurrentPageIndexAndOffset(index: Int, scrollOffset: Int) {
        val state = _uiState.value
        if (state is ReaderUiState.Success) {
            val totalPages = state.pageIndex.totalPages
            if (totalPages <= 0) {
                if (state.currentPageIndex != 0 || state.scrollOffset != 0) {
                    _uiState.value = state.copy(currentPageIndex = 0, scrollOffset = 0)
                }
                return
            }

            val safeIndex = index.coerceIn(0, totalPages - 1)
            val safeOffset = scrollOffset.coerceAtLeast(0)

            if (state.currentPageIndex != safeIndex || state.scrollOffset != safeOffset) {
                _uiState.value = state.copy(currentPageIndex = safeIndex, scrollOffset = safeOffset)

                // Schedule debounced progress save
                activeChapter?.let { chapter ->
                    val snapshot = ReadingProgressEntity(
                        comicId = chapter.comicId,
                        lastReadChapterId = chapter.id,
                        lastReadChapterNumber = chapter.chapterNumber,
                        chapterRelativePath = chapter.relativePath,
                        pageIndex = safeIndex,
                        scrollOffset = safeOffset,
                        updatedAt = timestampGenerator.nextTimestamp()
                    )
                    latestProgressSnapshot.set(snapshot)

                    progressSaveJob?.cancel()
                    progressSaveJob = viewModelScope.launch {
                        delay(500)
                        try {
                            libraryRepository.saveReadingProgress(snapshot)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressSaveJob?.cancel()
        latestProgressSnapshot.get()?.let { snapshot ->
            libraryRepository.flushReadingProgress(snapshot)
        }
        activeDecoder?.close()
        activeDecoder = null
    }

    class Factory(
        private val chapterId: Long,
        private val libraryRepository: LibraryRepository,
        private val settingsRepository: SettingsRepository,
        private val cbzPageIndexer: CbzPageIndexer,
        private val cbzPageDecoderFactory: CbzPageDecoderFactory
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
                return ReaderViewModel(chapterId, libraryRepository, settingsRepository, cbzPageIndexer, cbzPageDecoderFactory) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
