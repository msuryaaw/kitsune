package com.example.kitsune.ui.screens.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.data.entity.ChapterEntity
import com.example.kitsune.data.entity.ComicEntity
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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

    private val currentChapterIdState = MutableStateFlow(chapterId)
    private var activeChapter: ChapterEntity? = null
    private val timestampGenerator = MonotonicTimestampGenerator()
    private val latestProgressSnapshot = AtomicReference<ReadingProgressEntity?>(null)
    private var progressSaveJob: Job? = null
    private var requestGeneration = 0

    private val isTransitioningChapter = AtomicBoolean(false)

    init {
        loadChapterAndInitialize(chapterId)
    }

    private fun loadChapterAndInitialize(
        targetChapterId: Long = currentChapterIdState.value,
        startAtLastPage: Boolean = false
    ) {
        val currentGen = ++requestGeneration
        viewModelScope.launch {
            _uiState.value = ReaderUiState.Loading
            var tempHandle: CbzArchiveHandle? = null

            try {
                val chapter = libraryRepository.getChapterById(targetChapterId)
                if (currentGen != requestGeneration) return@launch
                activeChapter = chapter

                when (val openResult = libraryRepository.openCbzArchive(targetChapterId)) {
                    is CbzArchiveResult.Success -> {
                        tempHandle = openResult.handle
                        if (currentGen != requestGeneration) {
                            tempHandle.close()
                            return@launch
                        }

                        when (val indexResult = cbzPageIndexer.buildIndex(targetChapterId, tempHandle.file)) {
                            is PageIndexResult.Success -> {
                                if (currentGen != requestGeneration) {
                                    tempHandle.close()
                                    return@launch
                                }

                                val decoder = cbzPageDecoderFactory.createDecoder(indexResult.pageIndex, tempHandle)
                                activeDecoder = decoder
                                tempHandle = null // Transfer of ownership complete!

                                val comicId = chapter?.comicId ?: 0L
                                val savedProgress = if (comicId > 0L) libraryRepository.getReadingProgress(comicId) else null

                                val totalPages = indexResult.pageIndex.totalPages
                                var initialPageIndex = if (startAtLastPage && totalPages > 0) totalPages - 1 else 0
                                var initialScrollOffset = 0

                                if (!startAtLastPage && savedProgress != null && savedProgress.lastReadChapterId == targetChapterId) {
                                    if (totalPages > 0) {
                                        initialPageIndex = savedProgress.pageIndex.coerceIn(0, totalPages - 1)
                                        initialScrollOffset = savedProgress.scrollOffset
                                    }
                                    timestampGenerator.seed(savedProgress.updatedAt)
                                }

                                val chaptersFlow = if (comicId > 0L) {
                                    libraryRepository.observeChaptersByComicId(comicId)
                                } else {
                                    kotlinx.coroutines.flow.flowOf(emptyList())
                                }

                                val comicFlow = if (comicId > 0L) {
                                    libraryRepository.observeComicById(comicId)
                                } else {
                                    kotlinx.coroutines.flow.flowOf(null)
                                }

                                combine(
                                    settingsRepository.observeSettings(),
                                    chaptersFlow,
                                    comicFlow
                                ) { settings: com.example.kitsune.data.entity.SettingsEntity, rawChapters: List<ChapterEntity>, comic: ComicEntity? ->
                                    Triple(settings, rawChapters, comic)
                                }.collect { (settings, rawChapters, comic) ->
                                    if (currentGen != requestGeneration) return@collect
                                    val currentState = _uiState.value
                                    val currentPos = if (currentState is ReaderUiState.Success && currentState.chapterId == targetChapterId) currentState.currentPageIndex else initialPageIndex
                                    val currentOffset = if (currentState is ReaderUiState.Success && currentState.chapterId == targetChapterId) currentState.scrollOffset else initialScrollOffset

                                    val validChapters = rawChapters.filter { it.isValid }.sortedWith(
                                        Comparator<ChapterEntity> { ch1, ch2 ->
                                            com.example.kitsune.scanner.NaturalOrderComparator.compare(ch1.displayName, ch2.displayName)
                                        }.thenBy { it.chapterNumber }
                                    )

                                    val chIndex = validChapters.indexOfFirst { it.id == targetChapterId }
                                    val hasPrev = chIndex > 0
                                    val hasNext = chIndex >= 0 && chIndex < validChapters.size - 1

                                    val parentComicTitle = comic?.title?.ifBlank { null }
                                        ?: activeChapter?.displayName?.removeSuffix(".cbz")
                                        ?: ""

                                    _uiState.value = ReaderUiState.Success(
                                        chapterId = targetChapterId,
                                        pageIndex = indexResult.pageIndex,
                                        readerMode = settings.readerMode,
                                        readingDirection = settings.readingDirection,
                                        pageFit = settings.pageFit,
                                        readingMode = settings.readingMode,
                                        keepScreenAwake = settings.keepScreenAwake,
                                        currentPageIndex = currentPos,
                                        scrollOffset = currentOffset,
                                        chapters = validChapters,
                                        currentChapterIndex = chIndex,
                                        hasPreviousChapter = hasPrev,
                                        hasNextChapter = hasNext,
                                        comicTitle = parentComicTitle
                                    )
                                    isTransitioningChapter.set(false)
                                }
                            }
                            is PageIndexResult.NoImagesFound -> {
                                isTransitioningChapter.set(false)
                                if (currentGen == requestGeneration) {
                                    _uiState.value = ReaderUiState.Error("Tidak ada halaman gambar ditemukan dalam chapter", ReaderErrorType.NO_IMAGES)
                                }
                            }
                            is PageIndexResult.CorruptArchive -> {
                                isTransitioningChapter.set(false)
                                if (currentGen == requestGeneration) {
                                    _uiState.value = ReaderUiState.Error("Arsip chapter rusak: ${indexResult.message}", ReaderErrorType.CORRUPT_ARCHIVE)
                                }
                            }
                            is PageIndexResult.LimitExceeded -> {
                                isTransitioningChapter.set(false)
                                if (currentGen == requestGeneration) {
                                    _uiState.value = ReaderUiState.Error("Batas keamanan arsip terlampaui: ${indexResult.reason}", ReaderErrorType.UNSUPPORTED_ARCHIVE)
                                }
                            }
                            is PageIndexResult.StorageError -> {
                                isTransitioningChapter.set(false)
                                if (currentGen == requestGeneration) {
                                    _uiState.value = ReaderUiState.Error("Gagal membaca indeks chapter: ${indexResult.exception.localizedMessage}", ReaderErrorType.STORAGE_ERROR)
                                }
                            }
                        }
                    }
                    is CbzArchiveResult.ChapterMissing -> {
                        isTransitioningChapter.set(false)
                        if (currentGen == requestGeneration) {
                            _uiState.value = ReaderUiState.Error("File chapter tidak ditemukan di penyimpanan", ReaderErrorType.CHAPTER_MISSING)
                        }
                    }
                    is CbzArchiveResult.PermissionDenied -> {
                        isTransitioningChapter.set(false)
                        if (currentGen == requestGeneration) {
                            val errorType = if (openResult.message.contains("Root", ignoreCase = true) || openResult.message.contains("dikonfigurasi", ignoreCase = true)) {
                                ReaderErrorType.ROOT_NOT_CONFIGURED
                            } else {
                                ReaderErrorType.PERMISSION_DENIED
                            }
                            _uiState.value = ReaderUiState.Error("Izin akses penyimpanan terputus: ${openResult.message}", errorType)
                        }
                    }
                    is CbzArchiveResult.ArchiveTooLarge -> {
                        isTransitioningChapter.set(false)
                        if (currentGen == requestGeneration) {
                            _uiState.value = ReaderUiState.Error("Ukuran arsip melebihi batas 500 MB", ReaderErrorType.UNSUPPORTED_ARCHIVE)
                        }
                    }
                    is CbzArchiveResult.SpoolBudgetExceeded -> {
                        isTransitioningChapter.set(false)
                        if (currentGen == requestGeneration) {
                            _uiState.value = ReaderUiState.Error("Kapasitas cache reader penuh (1.5 GB)", ReaderErrorType.STORAGE_ERROR)
                        }
                    }
                    is CbzArchiveResult.StorageError -> {
                        isTransitioningChapter.set(false)
                        if (currentGen == requestGeneration) {
                            _uiState.value = ReaderUiState.Error("Gagal membuka arsip chapter: ${openResult.exception.localizedMessage}", ReaderErrorType.STORAGE_ERROR)
                        }
                    }
                }
            } catch (ce: CancellationException) {
                isTransitioningChapter.set(false)
                throw ce
            } catch (e: Exception) {
                isTransitioningChapter.set(false)
                if (currentGen == requestGeneration) {
                    _uiState.value = ReaderUiState.Error("Gagal mempersiapkan reader: ${e.localizedMessage}")
                }
            } finally {
                tempHandle?.close()
            }
        }
    }

    fun retryLoadChapter() {
        activeDecoder?.close()
        activeDecoder = null
        loadChapterAndInitialize()
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

    fun setReadingMode(readingMode: String) {
        viewModelScope.launch {
            settingsRepository.setReadingMode(readingMode)
        }
    }

    fun navigateToChapter(targetChapterId: Long, startAtLastPage: Boolean = false) {
        val currentState = _uiState.value
        if (currentState is ReaderUiState.Success && currentState.chapterId == targetChapterId) {
            return
        }
        if (!isTransitioningChapter.compareAndSet(false, true)) {
            return
        }

        viewModelScope.launch {
            latestProgressSnapshot.get()?.let { snapshot ->
                try {
                    libraryRepository.saveReadingProgress(snapshot)
                } catch (_: Exception) {}
            }

            activeDecoder?.close()
            activeDecoder = null

            currentChapterIdState.value = targetChapterId
            loadChapterAndInitialize(targetChapterId, startAtLastPage = startAtLastPage)
        }
    }

    fun navigateToPreviousChapter(startAtLastPage: Boolean = false) {
        val state = _uiState.value
        if (state is ReaderUiState.Success && state.hasPreviousChapter) {
            val prevChapter = state.chapters.getOrNull(state.currentChapterIndex - 1)
            if (prevChapter != null) {
                navigateToChapter(prevChapter.id, startAtLastPage = startAtLastPage)
            }
        }
    }

    fun navigateToNextChapter() {
        val state = _uiState.value
        if (state is ReaderUiState.Success && state.hasNextChapter) {
            val nextChapter = state.chapters.getOrNull(state.currentChapterIndex + 1)
            if (nextChapter != null) {
                navigateToChapter(nextChapter.id, startAtLastPage = false)
            }
        }
    }

    fun onSwipeForward() {
        val state = _uiState.value
        if (state is ReaderUiState.Success) {
            val totalPages = state.pageIndex.totalPages
            if (totalPages <= 0) return
            if (state.currentPageIndex < totalPages - 1) {
                goToNextPage()
            } else if (state.hasNextChapter) {
                navigateToNextChapter()
            }
        }
    }

    fun onSwipeBackward() {
        val state = _uiState.value
        if (state is ReaderUiState.Success) {
            if (state.currentPageIndex > 0) {
                goToPreviousPage()
            } else if (state.hasPreviousChapter) {
                navigateToPreviousChapter(startAtLastPage = true)
            }
        }
    }

    fun setPageFit(pageFit: String) {
        viewModelScope.launch {
            settingsRepository.setPageFit(pageFit)
        }
    }

    fun setKeepScreenAwake(keepScreenAwake: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKeepScreenAwake(keepScreenAwake)
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
