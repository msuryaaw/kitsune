package com.kitsune.app.ui.reader

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
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
 * ViewModel untuk mengelola logika pada layar Reader.
 * Menangani pemuatan chapter, manajemen progres membaca, dan navigasi antar halaman/chapter.
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

    // Cache folder induk untuk mempercepat resolusi URI saat navigasi antar chapter
    private var parentFolderDoc: DocumentFile? = null

    private var isNextChapterPreloaded = false

    // Job untuk menyimpan progres secara berkala (debounced) agar tidak membebani database
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

            // Reset preload flag saat pindah chapter
            isNextChapterPreloaded = false

            // Gunakan state saat ini untuk menentukan apakah ini pemuatan pertama kali.
            // Jika kita hanya berpindah chapter, kita tidak ingin menampilkan layar loading (seamless transition).
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
                    throw Exception("Library not configured")
                }

                val rootUri = rootUriString.toUri()

                // Pastikan izin akses folder masih valid sebelum mencoba membaca file
                if (!storageHelper.isUriPermissionValid(rootUriString)) {
                    throw Exception("Izin akses folder telah hilang. Silakan pilih ulang folder library.")
                }
                
                if (chapters.isEmpty()) {
                    chapters = scannerRepository.getChapters(rootUri, comicRelativePath)
                }
                currentChapterIndex = chapters.indexOfFirst { it.relativePath == chapterPath }

                // Gunakan cache parent folder jika tersedia untuk menghindari traversal SAF yang lambat
                if (parentFolderDoc == null || !parentFolderDoc!!.exists()) {
                    parentFolderDoc = storageHelper.findFileByRelativePath(rootUri, comicRelativePath)
                }

                val fileName = chapterPath.substringAfterLast('/')
                val chapterDoc = parentFolderDoc?.findFile(fileName)

                if (chapterDoc == null || !chapterDoc.exists()) {
                    // Fallback: jika cache gagal, coba traversal penuh sekali lagi
                    val fallbackDoc = storageHelper.findFileByRelativePath(rootUri, chapterPath)
                    if (fallbackDoc == null || !fallbackDoc.exists()) {
                        _uiState.value = ReaderUiState.Error("Chapter file not found")
                        return@launch
                    }
                    val uri = fallbackDoc.uri
                    processChapterPages(uri, chapterPath, targetChapterName, readingMode, fallbackDoc.lastModified())
                } else {
                    processChapterPages(chapterDoc.uri, chapterPath, targetChapterName, readingMode, chapterDoc.lastModified())
                }
            } catch (e: Exception) {
                Log.e("KitsuneReader", "Error loading chapter: $chapterPath", e)
                if (isInitialLoad) {
                    val errorMessage = e.message ?: e.javaClass.simpleName
                    _uiState.value = ReaderUiState.Error("Failed to load chapter: $errorMessage")
                }
            }
        }
    }

    private suspend fun processChapterPages(
        uri: Uri,
        chapterPath: String,
        chapterName: String,
        readingMode: String,
        lastModified: Long
    ) {
        val cacheKey = "${chapterPath}:${lastModified}"
        val pages = try {
            readerRepository.getPages(uri, cacheKey)
        } catch (e: Exception) {
            Log.e("KitsuneReader", "Failed to parse ZIP entries for $chapterPath", e)
            val errorMessage = e.localizedMessage ?: e.javaClass.simpleName
            throw Exception("Gagal membaca komik: $errorMessage")
        }
        
        if (pages.isEmpty()) {
            _uiState.value = ReaderUiState.Error("File komik kosong atau tidak berisi gambar valid.")
        } else {
            currentChapterPath = chapterPath
            val savedProgress = progressRepository.getProgressByChapterSync(chapterPath)
            val startPage = savedProgress?.pageNumber?.coerceIn(1, pages.size) ?: 1
            
            _currentPage.value = startPage

            _uiState.value = ReaderUiState.Success(
                pages = pages,
                chapterName = chapterName,
                readingMode = readingMode,
                chapterUri = uri
            )
            
            saveProgress(startPage, pages.size)
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
     * Menyimpan progres membaca ke database. Menggunakan mekanisme debounce 1 detik
     * untuk mencegah penulisan berlebihan saat user menggulir halaman dengan cepat.
     */
    fun saveProgress(pageNumber: Int, totalPages: Int) {
        _currentPage.value = pageNumber
        
        // Picu preloading metadata chapter berikutnya saat user hampir selesai (80% - 90%)
        val progressPercent = if (totalPages > 0) pageNumber.toFloat() / totalPages.toFloat() else 0f
        if (progressPercent >= 0.8f && !isNextChapterPreloaded) {
            preloadNextChapterMetadata()
        }

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
     * Memuat metadata chapter berikutnya ke dalam cache secara spekulatif (preloading).
     */
    private fun preloadNextChapterMetadata() {
        if (currentChapterIndex < chapters.size - 1) {
            isNextChapterPreloaded = true
            val nextChapter = chapters[currentChapterIndex + 1]
            val nextChapterPath = nextChapter.relativePath

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val settings = settingsRepository.settings.first()
                    val rootUriString = settings?.rootFolderUri ?: return@launch
                    val rootUri = rootUriString.toUri()

                    // Pastikan parent folder cache tersedia
                    if (parentFolderDoc == null || !parentFolderDoc!!.exists()) {
                        parentFolderDoc = storageHelper.findFileByRelativePath(rootUri, comicRelativePath)
                    }

                    val fileName = nextChapterPath.substringAfterLast('/')
                    val nextChapterDoc = parentFolderDoc?.findFile(fileName)

                    if (nextChapterDoc != null && nextChapterDoc.exists()) {
                        val cacheKey = "${nextChapterPath}:${nextChapterDoc.lastModified()}"
                        // Memanggil getPages akan mengisi LruCache di ReaderRepository secara spekulatif
                        readerRepository.getPages(nextChapterDoc.uri, cacheKey)
                    }
                } catch (e: Exception) {
                    // Abaikan galat preload agar tidak mengganggu alur utama
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
        super.onCleared()
        // Pastikan resource ZipFile ditutup saat ViewModel dihancurkan
        readerRepository.closeCurrentChapter()
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

    /**
     * Memperbarui mode membaca (Vertical, LTR, RTL) dan menyimpannya ke pengaturan.
     */
    fun updateReadingMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.updateReadingMode(mode)
        }
    }
}
