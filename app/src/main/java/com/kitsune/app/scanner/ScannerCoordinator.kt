package com.kitsune.app.scanner

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Koordinator untuk seluruh operasi pemindaian media di Kitsune.
 * Mengelola konkurensi melalui mutex dan melaporkan status progres ke UI.
 */
class ScannerCoordinator {
    private val comicMutex = Mutex()
    private val videoMutex = Mutex()

    private val _isScanningComics = MutableStateFlow(false)
    val isScanningComics: StateFlow<Boolean> = _isScanningComics.asStateFlow()

    private val _isScanningVideos = MutableStateFlow(false)
    val isScanningVideos: StateFlow<Boolean> = _isScanningVideos.asStateFlow()

    /**
     * Triggers an incremental scan for comics.
     * Uses a specific mutex to allow parallel execution with video scanning.
     */
    suspend fun performComicScan(rootUri: Uri, action: suspend (Uri) -> Unit) {
        if (comicMutex.isLocked) return
        comicMutex.withLock {
            _isScanningComics.value = true
            try {
                action(rootUri)
            } finally {
                _isScanningComics.value = false
            }
        }
    }

    /**
     * Triggers an incremental scan for videos.
     */
    suspend fun performVideoScan(rootUri: Uri, action: suspend (Uri) -> Unit) {
        if (videoMutex.isLocked) return
        videoMutex.withLock {
            _isScanningVideos.value = true
            try {
                action(rootUri)
            } finally {
                _isScanningVideos.value = false
            }
        }
    }

    /**
     * Triggers a full library scan (Comics & Videos) sequentially.
     * REVISION Masalah 1: Changed to sequential execution to reduce SAF Binder contention
     * and improve app responsiveness during manual scan.
     */
    suspend fun fullScan(
        rootUri: Uri,
        comicAction: suspend (Uri) -> Unit,
        videoAction: suspend (Uri) -> Unit
    ) {
        performComicScan(rootUri, comicAction)
        performVideoScan(rootUri, videoAction)
    }
}
