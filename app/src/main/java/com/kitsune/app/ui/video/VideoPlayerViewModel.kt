package com.kitsune.app.ui.video

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.database.entity.VideoProgressEntity
import com.kitsune.app.domain.model.Episode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.PrintWriter
import java.io.StringWriter

/**
 * State untuk siklus hidup gesture.
 */
enum class GestureState { IDLE, DETECTING, ACTIVE, FINISHED }

/**
 * Arah deteksi gesture.
 */
enum class GestureDirection { HORIZONTAL, VERTICAL, UNKNOWN }

/**
 * Area awal interaksi gesture.
 */
enum class GestureArea { LEFT, CENTER, RIGHT }

/**
 * Sumber operasi seek untuk menentukan kebijakan visibilitas UI.
 */
enum class SeekSource { GESTURE, SLIDER, RESUME, AUTO, NEXT_EPISODE }

/**
 * State untuk UI Player yang lebih terstruktur.
 */
sealed class PlayerUiState {
    object Loading : PlayerUiState()
    object Ready : PlayerUiState()
    data class Error(val message: String, val debugInfo: String) : PlayerUiState()
}

/**
 * ViewModel untuk mengelola instance ExoPlayer dan lifecycle pemutaran video secara sekuensial.
 * REVISION 8.2.5: Implementasi Vertical Brightness & Volume Gesture.
 * REVISION 8.3.5: Fixed Controls visibility after Horizontal Seek Gesture using SeekSource.
 * REVISION 11.0.1: Implemented Decoder Fallback and Detailed Error Logging for MKV Playback.
 * REVISION 11.2.1: Enhanced Source Error diagnostics and logging for SAF/IO issues.
 * REVISION 11.4.1: Optimized for 1080p MKV with structured PlayerUiState and buffer tuning.
 * REVISION 11.5.1: SAF I/O stability tuning and MTK hardware decoder crash recovery.
 */
class VideoPlayerViewModel(
    application: Application,
    private val videoRelativePath: String,
    private val episodeRelativePath: String,
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _playerUiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val playerUiState: StateFlow<PlayerUiState> = _playerUiState.asStateFlow()

    private val _isSoftwareFallbackActive = MutableStateFlow(false)
    val isSoftwareFallbackActive: StateFlow<Boolean> = _isSoftwareFallbackActive.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    // Sequential Navigation States
    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _currentEpisodeIndex = MutableStateFlow(-1)
    val currentEpisodeIndex: StateFlow<Int> = _currentEpisodeIndex.asStateFlow()

    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    val currentEpisode: StateFlow<Episode?> = _currentEpisode.asStateFlow()

    val hasNext = combine(_currentEpisodeIndex, _episodes) { index, list ->
        index < list.size - 1 && index != -1
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasPrevious = _currentEpisodeIndex.map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val episodeCount = _episodes.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Playback States
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private var playWhenReady = true
    private var positionPollingJob: Job? = null
    private var rootUri: Uri? = null

    // PRELOAD FOUNDATION (Phase 7.6.4)
    private var preloadedMediaItem: MediaItem? = null

    // PROGRESS SAVING FOUNDATION (Phase 7.7.1)
    private var lastSavedPosition = -1L

    // RESUME PLAYBACK FOUNDATION (Phase 7.7.2)
    private val _showResumeDialog = MutableStateFlow<VideoProgressEntity?>(null)
    val showResumeDialog: StateFlow<VideoProgressEntity?> = _showResumeDialog.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    // TRANSITION STABILIZATION (Phase 8.2.2)
    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    private var loadJob: Job? = null

    // GESTURE FOUNDATION (Phase 8.2.3)
    private val _gestureState = MutableStateFlow(GestureState.IDLE)
    val gestureState = _gestureState.asStateFlow()

    private val _gestureDirection = MutableStateFlow(GestureDirection.UNKNOWN)
    val gestureDirection = _gestureDirection.asStateFlow()

    private val _gestureArea = MutableStateFlow(GestureArea.CENTER)
    val gestureArea = _gestureArea.asStateFlow()

    // HORIZONTAL SEEK GESTURE (Phase 8.2.4)
    private val _seekPreviewPosition = MutableStateFlow(0L)
    val seekPreviewPosition = _seekPreviewPosition.asStateFlow()

    private val _seekPreviewDelta = MutableStateFlow(0L)
    val seekPreviewDelta = _seekPreviewDelta.asStateFlow()

    private var totalSeekDelta = 0f
    private var initialSeekPosition = 0L
    private var totalDx = 0f
    private var totalDy = 0f

    // VERTICAL BRIGHTNESS & VOLUME GESTURE (Phase 8.2.5)
    private val _brightnessPreview = MutableStateFlow(-1f)
    val brightnessPreview = _brightnessPreview.asStateFlow()

    private val _volumePreview = MutableStateFlow(-1)
    val volumePreview = _volumePreview.asStateFlow()

    private val _maxVolume = MutableStateFlow(0)
    val maxVolume = _maxVolume.asStateFlow()

    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var initialBrightnessValue = -1f
    private var verticalVolumeAccumulator = 0f

    private var lastVideoDecoderName: String? = null

    companion object {
        // GESTURE CONFIGURATION (Phase 8.2.4 & 8.2.5)
        private const val GESTURE_THRESHOLD = 30f 
        private const val SEEK_SENSITIVITY = 150L // ms per pixel drag
        private const val BRIGHTNESS_SENSITIVITY = 500f // pixels for full range
        private const val VOLUME_SENSITIVITY = 40f // pixels per volume step
    }

    // VISIBILITY FOUNDATION (Phase 8.1.2)
    private val _isControlsVisible = MutableStateFlow(true)
    val isControlsVisible: StateFlow<Boolean> = _isControlsVisible.asStateFlow()

    // AUTO HIDE FOUNDATION (Phase 8.1.3)
    private var autoHideJob: Job? = null

    // ORIENTATION FOUNDATION (Phase 8.1.5)
    private val _isLandscape = MutableStateFlow(false)
    val isLandscape: StateFlow<Boolean> = _isLandscape.asStateFlow()

    init {
        initializeSequentialPlayer()
        // Start auto-hide timer for initial state
        startAutoHideTimer()
    }

    private fun initializeSequentialPlayer() {
        viewModelScope.launch {
            try {
                _isTransitioning.value = true
                val settings = settingsRepository.settings.first()
                val rootUriString = settings?.rootFolderUri

                if (rootUriString.isNullOrEmpty()) {
                    _errorState.value = "Library not configured"
                    _isTransitioning.value = false
                    return@launch
                }

                rootUri = rootUriString.toUri()
                
                val episodeList = videoRepository.getEpisodes(rootUri!!, videoRelativePath)
                if (episodeList.isEmpty()) {
                    _errorState.value = "No episodes found"
                    _isTransitioning.value = false
                    return@launch
                }
                _episodes.value = episodeList

                val index = episodeList.indexOfFirst { it.relativePath == episodeRelativePath }
                if (index == -1) {
                    _errorState.value = "Episode not found in library"
                    _isTransitioning.value = false
                    return@launch
                }
                
                _currentEpisodeIndex.value = index
                val firstEpisode = episodeList[index]
                _currentEpisode.value = firstEpisode

                setupPlayer()
                
                // RESTORE PROGRESS (Phase 7.7.2): Hanya untuk inisialisasi manual (first open)
                val savedProgress = videoRepository.getVideoProgressSync(firstEpisode.relativePath)
                if (isValidProgress(savedProgress)) {
                    _isRestoring.value = true
                    _showResumeDialog.value = savedProgress
                    // Siapkan player tetapi jangan putar (playWhenReady = false)
                    loadMediaItem(firstEpisode, autoPlay = false)
                    // If resume dialog is shown, we probably want controls to stay visible
                    showControls()
                } else {
                    loadMediaItem(firstEpisode, autoPlay = true)
                }

            } catch (e: Exception) {
                _errorState.value = "Initialization Error: ${e.message}"
                _isTransitioning.value = false
            }
        }
    }

    private fun isValidProgress(progress: VideoProgressEntity?): Boolean {
        if (progress == null) return false
        return progress.lastPositionMs >= 3000L && 
               progress.durationMs > 0L && 
               progress.lastPositionMs < (progress.durationMs * 0.95)
    }

    @OptIn(UnstableApi::class)
    private fun setupPlayer() {
        // REVISION 11.5.1: Custom MediaCodecSelector to force software fallback if hardware fails
        val mediaCodecSelector = if (_isSoftwareFallbackActive.value) {
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoders = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                // REVISION 11.6.1: Explicitly blacklist MediaTek hardware and whitelist standard Android SW decoders
                decoders.filter { 
                    !it.name.contains("mtk", ignoreCase = true) && 
                    (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.") || it.softwareOnly)
                }.ifEmpty { decoders }
            }
        } else {
            MediaCodecSelector.DEFAULT
        }

        // Standard RenderersFactory with Decoder Fallback enabled for better compatibility.
        val renderersFactory = DefaultRenderersFactory(getApplication())
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(mediaCodecSelector)

        // REVISION 11.0.2: Explicit TrackSelector audit (default configuration is preferred)
        val trackSelector = DefaultTrackSelector(getApplication())

        // REVISION 11.5.1: Optimized LoadControl for SAF I/O stability (15s min, 30s max)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // minBufferMs
                30000, // maxBufferMs
                2500,  // bufferForPlaybackMs
                5000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(getApplication(), renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                this.playWhenReady = this@VideoPlayerViewModel.playWhenReady
                
                // AnalyticsListener to capture decoder and format information for debug logging
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        lastVideoDecoderName = decoderName
                        val isFfmpeg = decoderName.contains("ffmpeg", ignoreCase = true)
                        val decoderType = if (isFfmpeg) "Software (FFmpeg)" else "Hardware/System"
                        Log.i("KitsunePlayer", "Video Decoder Initialized: $decoderName ($decoderType)")
                    }

                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        Log.i("KitsunePlayer", "Audio Decoder Initialized: $decoderName")
                    }

                    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: androidx.media3.common.Tracks) {
                        // Log media info when tracks are selected (Playback Started)
                        val player = _player.value ?: return
                        val vFormat = player.videoFormat
                        Log.i("KitsunePlayer", "--- Playback Started ---")
                        Log.i("KitsunePlayer", "Container: ${vFormat?.containerMimeType ?: "N/A"}")
                        Log.i("KitsunePlayer", "MIME: ${vFormat?.sampleMimeType}")
                        Log.i("KitsunePlayer", "Codec: ${vFormat?.codecs}")
                        Log.i("KitsunePlayer", "Resolution: ${vFormat?.width}x${vFormat?.height}")
                        Log.i("KitsunePlayer", "-----------------------")
                    }
                })

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        _playbackState.value = state
                        _isBuffering.value = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_READY) {
                            _duration.value = duration
                            _playerUiState.value = PlayerUiState.Ready
                        }
                        
                        // AUTO NEXT LOGIC (Phase 7.6.4)
                        if (state == Player.STATE_ENDED) {
                            handlePlaybackEnded()
                        }

                        // SAVE TRIGGER: Stop / End
                        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                            viewModelScope.launch { saveCurrentProgress() }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                        if (isPlaying) {
                            startPollingPosition()
                        } else {
                            stopPollingPosition()
                            // SAVE TRIGGER: Pause / Background
                            viewModelScope.launch { saveCurrentProgress() }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // REVISION 11.5.1: Detect MTK Decoder Crash or SAF I/O failure
                        // REVISION 11.6.1: Included ERROR_CODE_DECODING_FAILED (4003) for hardware failure recovery
                        val isMtkCrash = (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED || 
                                         error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                         error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) &&
                                         (error.cause?.toString()?.contains("CryptoException", true) == true || 
                                          lastVideoDecoderName?.contains("mtk", true) == true)
                        
                        if (isMtkCrash && !_isSoftwareFallbackActive.value) {
                            Log.w("KitsunePlayer", "Detected Hardware Decoder Crash (MTK). Triggering Software Fallback...")
                            triggerSoftwareFallback()
                            return
                        }

                        // REVISION 11.2.1: Deep diagnostics for Source/IO Errors
                        // REVISION 11.4.1: Categorized errors for structured UI feedback
                        val format = videoFormat
                        val debugInfo = StringBuilder().apply {
                            append("--- DEBUG INFO ---\n")
                            append("Type: ${error.errorCodeName} (${error.errorCode})\n")
                            
                            format?.let {
                                append("Mime: ${it.sampleMimeType}\n")
                                append("Container: ${it.containerMimeType ?: "N/A"}\n")
                                append("Codec: ${it.codecs ?: "N/A"}\n")
                                append("Res: ${it.width}x${it.height}\n")
                            }
                            
                            append("Decoder: ${lastVideoDecoderName ?: "None (Selection failed)"}\n")
                            
                            // Log Cause and Stack Trace
                            val sw = StringWriter()
                            error.printStackTrace(PrintWriter(sw))
                            val stackTrace = sw.toString()
                            
                            append("\n--- CAUSE ---\n")
                            append(error.cause?.toString() ?: "No Cause")
                            
                            // SAF Diagnostics
                            val currentItem = currentEpisode.value
                            if (currentItem != null && rootUri != null) {
                                val uri = videoRepository.getEpisodeUri(rootUri!!, currentItem.relativePath)
                                append("\n\n--- SAF DIAGNOSTICS ---\n")
                                append("URI: $uri\n")
                                if (uri != null) {
                                    try {
                                        getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                            append("FD Check: SUCCESS (Size: ${pfd.statSize})\n")
                                        } ?: append("FD Check: FAILED (Null PFD)\n")
                                    } catch (e: Exception) {
                                        append("FD Check: FAILED (${e.message})\n")
                                    }
                                }
                            }
                            
                            append("\n\n--- STACK TRACE ---\n")
                            append(stackTrace.take(1000) + "...")
                        }.toString()
                        
                        val userMessage = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> 
                                "Video format not supported by your device hardware."
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                                "Video file could not be accessed."
                            else -> "An unexpected playback error occurred."
                        }
                        
                        Log.e("KitsunePlayer", "Error: $userMessage\n$debugInfo")
                        _playerUiState.value = PlayerUiState.Error(userMessage, debugInfo)
                        _errorState.value = userMessage // Keep old for compatibility if needed
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        _currentPosition.value = newPosition.positionMs
                    }
                })
            }
        _player.value = exoPlayer
    }

    /**
     * Menangani kondisi saat pemutaran video selesai (Phase 7.6.4).
     * REVISION 8.2.2: Perbaikan double save pada end-of-playback.
     */
    private fun handlePlaybackEnded() {
        if (hasNext.value) {
            // nextEpisode() akan menangani penyimpanan progress secara internal
            nextEpisode()
        } else {
            // Jika episode terakhir, lakukan final save
            viewModelScope.launch {
                saveCurrentProgress(isFinal = true)
            }
        }
    }

    /**
     * Menyimpan progres menonton video ke database (Phase 7.7.1).
     * REVISION 8.2.2: Pencegahan penyimpanan posisi tidak valid selama transisi.
     */
    private suspend fun saveCurrentProgress(isFinal: Boolean = false) {
        // JANGAN menyimpan progres jika sedang dalam proses restore dialog atau transisi (kecuali final save)
        if (_isRestoring.value) return
        if (_isTransitioning.value && !isFinal) return

        val player = _player.value ?: return
        val episode = _currentEpisode.value ?: return
        
        val position = player.currentPosition
        val duration = player.duration

        // Validasi posisi: Jangan simpan jika terlalu awal atau player sedang reset (0)
        if (position < 3000L && !isFinal) return
        if (duration <= 0L) return
        if (player.playbackState == Player.STATE_BUFFERING && !isFinal) return
        if (position == lastSavedPosition && !isFinal) return

        lastSavedPosition = position

        val context = if (isFinal) Dispatchers.IO + NonCancellable else Dispatchers.IO
        
        if (isFinal) {
            CoroutineScope(context).launch {
                videoRepository.saveVideoProgress(
                    videoPath = videoRelativePath,
                    episodePath = episode.relativePath,
                    positionMs = position,
                    durationMs = duration
                )
            }
        } else {
            viewModelScope.launch(context) {
                videoRepository.saveVideoProgress(
                    videoPath = videoRelativePath,
                    episodePath = episode.relativePath,
                    positionMs = position,
                    durationMs = duration
                )
            }
        }
    }

    /**
     * Resume dari posisi terakhir yang tersimpan (Phase 7.7.2).
     */
    fun onResumePlayback() {
        val progress = _showResumeDialog.value ?: return
        _showResumeDialog.value = null
        _isRestoring.value = false
        
        _player.value?.let {
            it.seekTo(progress.lastPositionMs)
            it.playWhenReady = true
            it.play()
        }
        userInteraction()
    }

    /**
     * Mulai menonton dari awal dan hapus progres lama (Phase 7.7.2).
     */
    fun onStartOver() {
        val episode = _currentEpisode.value ?: return
        _showResumeDialog.value = null
        _isRestoring.value = false
        
        viewModelScope.launch(Dispatchers.IO) {
            videoRepository.deleteEpisodeProgress(episode.relativePath)
        }
        
        _player.value?.let {
            it.seekTo(0)
            it.playWhenReady = true
            it.play()
        }
        userInteraction()
    }

    /**
     * Mengganti MediaItem pada instance player yang sudah ada.
     * REVISION 8.2.2: Implementasi Load Job Management untuk transisi serial.
     */
    private fun loadMediaItem(episode: Episode, autoPlay: Boolean = true) {
        val player = _player.value ?: return
        val currentRoot = rootUri ?: return

        // Batalkan proses loading sebelumnya jika ada (Rapid Navigation Safety)
        loadJob?.cancel()
        
        loadJob = viewModelScope.launch {
            try {
                // Resolusi URI dilakukan di IO thread
                val mediaItem = withContext(Dispatchers.IO) {
                    if (preloadedMediaItem != null && 
                        episode.relativePath == _episodes.value.getOrNull(_currentEpisodeIndex.value)?.relativePath) {
                        preloadedMediaItem!!
                    } else {
                        val videoUri = videoRepository.getEpisodeUri(currentRoot, episode.relativePath)
                        if (videoUri == null) {
                            null
                        } else {
                            MediaItem.fromUri(videoUri)
                        }
                    }
                }
                
                if (mediaItem == null) {
                    _errorState.value = "Video file not found: ${episode.name}"
                    return@launch
                }

                preloadedMediaItem = null
                
                player.setMediaItem(mediaItem)
                player.prepare()
                
                player.playWhenReady = autoPlay
                if (autoPlay) player.play()
                
                preloadNextEpisode()
            } catch (e: CancellationException) {
                // Diabaikan karena ini hasil dari loadJob.cancel()
            } catch (e: Exception) {
                _errorState.value = "Load Error: ${e.message}"
            } finally {
                // Reset flag transisi setelah proses selesai atau dibatalkan
                _isTransitioning.value = false
            }
        }
    }

    /**
     * Melakukan resolusi URI dan pembuatan MediaItem untuk episode berikutnya (Phase 7.6.4).
     */
    private fun preloadNextEpisode() {
        val nextIndex = _currentEpisodeIndex.value + 1
        if (nextIndex < _episodes.value.size) {
            val nextEpisode = _episodes.value[nextIndex]
            viewModelScope.launch {
                val currentRoot = rootUri ?: return@launch
                val videoUri = videoRepository.getEpisodeUri(currentRoot, nextEpisode.relativePath)
                if (videoUri != null) {
                    preloadedMediaItem = MediaItem.fromUri(videoUri)
                }
            }
        }
    }

    fun nextEpisode() {
        if (_isTransitioning.value) return
        val nextIndex = _currentEpisodeIndex.value + 1
        if (nextIndex < _episodes.value.size) {
            goToEpisode(nextIndex)
        }
        userInteraction()
    }

    fun previousEpisode() {
        if (_isTransitioning.value) return
        val prevIndex = _currentEpisodeIndex.value - 1
        if (prevIndex >= 0) {
            goToEpisode(prevIndex)
        }
        userInteraction()
    }

    /**
     * Navigasi ke episode tertentu berdasarkan index.
     * REVISION 8.2.2: Penambahan Transition Lock dan Atomic State Updates.
     */
    fun goToEpisode(index: Int) {
        if (_isTransitioning.value) return // Block rapid input

        val episodeList = _episodes.value
        if (index < 0 || index >= episodeList.size) return

        viewModelScope.launch {
            // 1. Kunci transisi
            _isTransitioning.value = true

            // 2. Simpan progres episode saat ini secara sinkron (suspend)
            saveCurrentProgress(isFinal = true)
            
            // 3. Update metadata
            val episode = episodeList[index]
            _currentEpisodeIndex.value = index
            _currentEpisode.value = episode
            
            _isRestoring.value = false
            _showResumeDialog.value = null
            
            // 4. Trigger pemuatan media baru
            loadMediaItem(episode, autoPlay = true)
        }
    }

    /**
     * Resets the player and re-initializes it with a software-only decoder selector.
     * REVISION 11.5.1: Critical recovery for MediaTek hardware decoder driver failures.
     */
    private fun triggerSoftwareFallback() {
        viewModelScope.launch {
            val currentPos = _player.value?.currentPosition ?: 0L
            val currentEpisode = _currentEpisode.value ?: return@launch
            
            _isSoftwareFallbackActive.value = true
            
            // Step 1: Inform UI we are recovering
            _playerUiState.value = PlayerUiState.Loading
            
            // Step 2: Full release of corrupted player instance
            _player.value?.let {
                it.stop()
                it.release()
            }
            _player.value = null
            
            // Step 3: Re-setup with filtered MediaCodecSelector (Standard Android SW Decoders)
            setupPlayer()
            
            // Step 4: Reload and seek back to last known position
            loadMediaItem(currentEpisode, autoPlay = true)
            _player.value?.seekTo(currentPos)
            
            Log.i("KitsunePlayer", "Recovery: Switched to Software Decoder at $currentPos ms")
        }
    }

    fun reloadCurrentEpisode() {
        _currentEpisode.value?.let { loadMediaItem(it) }
        userInteraction()
    }

    private fun startPollingPosition() {
        positionPollingJob?.cancel()
        positionPollingJob = viewModelScope.launch {
            var lastSaveTime = System.currentTimeMillis()
            while (isActive) {
                _player.value?.let { player ->
                    _currentPosition.value = player.currentPosition
                    _bufferedPosition.value = player.bufferedPosition
                    
                    // JANGAN polling save jika sedang dalam dialog restore atau transisi
                    if (!_isRestoring.value && !_isTransitioning.value && player.isPlaying && System.currentTimeMillis() - lastSaveTime >= 5000L) {
                        saveCurrentProgress()
                        lastSaveTime = System.currentTimeMillis()
                    }
                }
                delay(250L)
            }
        }
    }

    private fun stopPollingPosition() {
        positionPollingJob?.cancel()
        positionPollingJob = null
    }

    /**
     * Melakukan pemindahan posisi (seek) video.
     * @param positionMs Posisi target dalam milidetik.
     * @param source Sumber operasi seek untuk menentukan kebijakan visibilitas UI.
     */
    fun seekTo(positionMs: Long, source: SeekSource = SeekSource.SLIDER) {
        _player.value?.let {
            it.seekTo(positionMs)
            _currentPosition.value = positionMs
        }
        // Gunakan konteks isGesture jika sumbernya adalah Gesture
        userInteraction(isGesture = source == SeekSource.GESTURE)
    }

    fun togglePlayPause() {
        // JANGAN toggle jika sedang dalam dialog restore atau transisi
        if (_isRestoring.value || _isTransitioning.value) return

        _player.value?.let {
            val nextState = !it.playWhenReady
            playWhenReady = nextState
            it.playWhenReady = nextState
        }
        userInteraction()
    }

    fun replay() {
        _player.value?.let {
            it.seekTo(0)
            it.playWhenReady = true
            it.play()
        }
        userInteraction()
    }

    // --- GESTURE DISPATCHER (Phase 8.2.3) ---

    /**
     * Menangani event awal sentuhan.
     * Mengidentifikasi area interaksi (Left, Center, Right).
     */
    fun onGestureDown(x: Float, viewWidth: Float, currentBrightness: Float) {
        userInteraction(isGesture = true)
        _gestureState.value = GestureState.DETECTING
        
        _gestureArea.value = when {
            x < viewWidth / 3 -> GestureArea.LEFT
            x > viewWidth * 2 / 3 -> GestureArea.RIGHT
            else -> GestureArea.CENTER
        }

        // Initialize Gesture Data (Phase 8.2.4)
        totalDx = 0f
        totalDy = 0f
        totalSeekDelta = 0f
        initialSeekPosition = _currentPosition.value

        // Phase 8.2.5
        initialBrightnessValue = if (currentBrightness < 0) 0.5f else currentBrightness
        _maxVolume.value = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        _volumePreview.value = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        verticalVolumeAccumulator = 0f
    }

    /**
     * Menangani pergerakan gesture.
     * Mengidentifikasi arah pergerakan (Horizontal, Vertical).
     */
    fun onGestureMove(dx: Float, dy: Float) {
        userInteraction(isGesture = true)

        if (_gestureState.value == GestureState.DETECTING) {
            totalDx += dx
            totalDy += dy
            val absTotalDx = kotlin.math.abs(totalDx)
            val absTotalDy = kotlin.math.abs(totalDy)
            
            if (absTotalDx > GESTURE_THRESHOLD || absTotalDy > GESTURE_THRESHOLD) {
                _gestureState.value = GestureState.ACTIVE
                _gestureDirection.value = if (absTotalDx > absTotalDy) {
                    GestureDirection.HORIZONTAL
                } else {
                    GestureDirection.VERTICAL
                }

                // Initialize accumulators based on direction
                if (_gestureDirection.value == GestureDirection.HORIZONTAL) {
                    totalSeekDelta = totalDx
                } else {
                    verticalVolumeAccumulator = 0f
                }
            }
        }

        // Handle Horizontal Seek Logic (Phase 8.2.4)
        if (_gestureState.value == GestureState.ACTIVE && _gestureDirection.value == GestureDirection.HORIZONTAL) {
            totalSeekDelta += dx
            val offsetMs = (totalSeekDelta * SEEK_SENSITIVITY).toLong()
            val newPosition = (initialSeekPosition + offsetMs).coerceIn(0L, _duration.value)

            _seekPreviewPosition.value = newPosition
            _seekPreviewDelta.value = offsetMs
        }

        // Handle Vertical Brightness & Volume (Phase 8.2.5)
        if (_gestureState.value == GestureState.ACTIVE && _gestureDirection.value == GestureDirection.VERTICAL) {
            if (_gestureArea.value == GestureArea.LEFT) {
                // Brightness
                val delta = -dy / BRIGHTNESS_SENSITIVITY
                val current = _brightnessPreview.value.let { if (it < 0f) initialBrightnessValue else it }.coerceAtLeast(0f)
                _brightnessPreview.value = (current + delta).coerceIn(0.01f, 1f)
            } else if (_gestureArea.value == GestureArea.RIGHT || _gestureArea.value == GestureArea.CENTER) {
                // Volume
                verticalVolumeAccumulator -= dy
                if (kotlin.math.abs(verticalVolumeAccumulator) >= VOLUME_SENSITIVITY) {
                    val steps = (verticalVolumeAccumulator / VOLUME_SENSITIVITY).toInt()
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val newVol = (currentVol + steps).coerceIn(0, _maxVolume.value)
                    
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                    _volumePreview.value = newVol
                    verticalVolumeAccumulator -= steps * VOLUME_SENSITIVITY
                }
            }
        }
    }

    /**
     * Menangani penyelesaian gesture.
     * REVISION 8.2.4: Added Commit Seek logic.
     * REVISION 8.2.5: Added Vertical reset logic.
     * REVISION 8.3.5: Use SeekSource.GESTURE to maintain UI visibility.
     */
    fun onGestureUp() {
        if (_gestureState.value == GestureState.ACTIVE && _gestureDirection.value == GestureDirection.HORIZONTAL) {
            // REVISION 8.3.5: Specify source as GESTURE
            seekTo(_seekPreviewPosition.value, SeekSource.GESTURE)
        }

        _gestureState.value = GestureState.FINISHED
        
        viewModelScope.launch {
            delay(500) // Visual feedback duration placeholder
            if (_gestureState.value == GestureState.FINISHED) {
                _gestureState.value = GestureState.IDLE
                _gestureDirection.value = GestureDirection.UNKNOWN
                // Reset Preview States (Phase 8.2.4 & 8.2.5)
                _seekPreviewPosition.value = 0L
                _seekPreviewDelta.value = 0L
                _brightnessPreview.value = -1f
                _volumePreview.value = -1
            }
        }
    }

    override fun onCleared() {
        // FINAL SAVE TRIGGER
        val player = _player.value
        val episode = _currentEpisode.value
        val isRestoring = _isRestoring.value
        val isTransitioning = _isTransitioning.value

        if (!isRestoring && !isTransitioning && player != null && episode != null) {
            val pos = player.currentPosition
            val dur = player.duration
            if (pos >= 3000L && dur > 0L) {
                CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                    videoRepository.saveVideoProgress(
                        videoRelativePath,
                        episode.relativePath,
                        pos,
                        dur
                    )
                }
            }
        }

        super.onCleared()
        stopPollingPosition()
        autoHideJob?.cancel()
        _player.value?.let {
            it.stop()
            it.release()
        }
        _player.value = null
    }
    
    fun onPlayPause(play: Boolean) {
        // JANGAN force play/pause jika sedang dalam dialog restore atau transisi
        if (_isRestoring.value || _isTransitioning.value) return

        playWhenReady = play
        _player.value?.playWhenReady = play
    }

    // --- VISIBILITY API (Phase 8.1.3) ---

    /**
     * Mencatat interaksi pengguna untuk mengelola visibilitas kontrol.
     * @param isGesture Jika true, interaksi dianggap sebagai gesture dan tidak akan memicu munculnya kontrol.
     */
    fun userInteraction(isGesture: Boolean = false) {
        if (isGesture) {
            // REVISION 8.3.1: Gesture Policy
            // Jika controls sedang tampil, reset timer agar tidak menghilang saat sedang gesture.
            // Jika controls sedang sembunyi, tetap sembunyi (jangan panggil showControls).
            if (_isControlsVisible.value) {
                startAutoHideTimer()
            }
        } else {
            showControls()
        }
    }

    fun showControls() {
        _isControlsVisible.value = true
        startAutoHideTimer()
    }

    fun hideControls() {
        _isControlsVisible.value = false
        autoHideJob?.cancel()
    }

    fun toggleControls() {
        if (_isControlsVisible.value) {
            hideControls()
        } else {
            userInteraction()
        }
    }

    private fun startAutoHideTimer() {
        autoHideJob?.cancel()
        autoHideJob = viewModelScope.launch {
            delay(3500) // 3.5 seconds
            hideControls()
        }
    }

    // --- ORIENTATION API (Phase 8.1.5) ---

    fun updateOrientation(isLandscape: Boolean) {
        _isLandscape.value = isLandscape
    }
}
