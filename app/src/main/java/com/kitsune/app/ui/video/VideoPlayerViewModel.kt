package com.kitsune.app.ui.video

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.kitsune.app.data.repository.SettingsRepository
import com.kitsune.app.data.repository.VideoRepository
import com.kitsune.app.database.entity.VideoProgressEntity
import com.kitsune.app.domain.model.Episode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ViewModel untuk mengelola instance ExoPlayer dan lifecycle pemutaran video secara sekuensial.
 * REVISION 7.7.2: Implementasi Resume Playback Foundation.
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

    init {
        initializeSequentialPlayer()
    }

    private fun initializeSequentialPlayer() {
        viewModelScope.launch {
            try {
                val settings = settingsRepository.settings.first()
                val rootUriString = settings?.rootFolderUri

                if (rootUriString.isNullOrEmpty()) {
                    _errorState.value = "Library not configured"
                    return@launch
                }

                rootUri = rootUriString.toUri()
                
                val episodeList = videoRepository.getEpisodes(rootUri!!, videoRelativePath)
                if (episodeList.isEmpty()) {
                    _errorState.value = "No episodes found"
                    return@launch
                }
                _episodes.value = episodeList

                val index = episodeList.indexOfFirst { it.relativePath == episodeRelativePath }
                if (index == -1) {
                    _errorState.value = "Episode not found in library"
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
                } else {
                    loadMediaItem(firstEpisode, autoPlay = true)
                }

            } catch (e: Exception) {
                _errorState.value = "Initialization Error: ${e.message}"
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
        val exoPlayer = ExoPlayer.Builder(getApplication())
            .build()
            .apply {
                this.playWhenReady = this@VideoPlayerViewModel.playWhenReady
                
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        _playbackState.value = state
                        _isBuffering.value = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_READY) {
                            _duration.value = duration
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
                        _errorState.value = "Playback Error: ${error.message}"
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
     */
    private fun handlePlaybackEnded() {
        viewModelScope.launch {
            saveCurrentProgress()
            if (hasNext.value) {
                nextEpisode()
            }
        }
    }

    /**
     * Menyimpan progres menonton video ke database (Phase 7.7.1).
     */
    private suspend fun saveCurrentProgress(isFinal: Boolean = false) {
        // JANGAN menyimpan progres jika sedang dalam proses restore dialog
        if (_isRestoring.value) return

        val player = _player.value ?: return
        val episode = _currentEpisode.value ?: return
        
        val position = player.currentPosition
        val duration = player.duration

        if (position < 3000L) return
        if (duration <= 0L) return
        if (player.playbackState == Player.STATE_BUFFERING) return
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
    }

    /**
     * Mengganti MediaItem pada instance player yang sudah ada.
     */
    private fun loadMediaItem(episode: Episode, autoPlay: Boolean = true) {
        val player = _player.value ?: return
        val currentRoot = rootUri ?: return

        val mediaItem = if (preloadedMediaItem != null && 
            episode.relativePath == _episodes.value.getOrNull(_currentEpisodeIndex.value)?.relativePath) {
            preloadedMediaItem!!
        } else {
            val videoUri = videoRepository.getEpisodeUri(currentRoot, episode.relativePath)
            if (videoUri == null) {
                _errorState.value = "Video file not found: ${episode.name}"
                return
            }
            MediaItem.fromUri(videoUri)
        }
        
        preloadedMediaItem = null
        
        player.setMediaItem(mediaItem)
        player.prepare()
        
        player.playWhenReady = autoPlay
        if (autoPlay) player.play()
        
        preloadNextEpisode()
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
        val nextIndex = _currentEpisodeIndex.value + 1
        if (nextIndex < _episodes.value.size) {
            goToEpisode(nextIndex)
        }
    }

    fun previousEpisode() {
        val prevIndex = _currentEpisodeIndex.value - 1
        if (prevIndex >= 0) {
            goToEpisode(prevIndex)
        }
    }

    /**
     * Navigasi ke episode tertentu berdasarkan index.
     * Resume Dialog TIDAK muncul di sini (hanya saat first open).
     */
    fun goToEpisode(index: Int) {
        val episodeList = _episodes.value
        if (index < 0 || index >= episodeList.size) return

        viewModelScope.launch {
            saveCurrentProgress()
            
            val episode = episodeList[index]
            _currentEpisodeIndex.value = index
            _currentEpisode.value = episode
            
            // Nonaktifkan restore flag jika pindah episode manual/auto
            _isRestoring.value = false
            _showResumeDialog.value = null
            
            loadMediaItem(episode, autoPlay = true)
        }
    }

    fun reloadCurrentEpisode() {
        _currentEpisode.value?.let { loadMediaItem(it) }
    }

    private fun startPollingPosition() {
        positionPollingJob?.cancel()
        positionPollingJob = viewModelScope.launch {
            var lastSaveTime = System.currentTimeMillis()
            while (isActive) {
                _player.value?.let { player ->
                    _currentPosition.value = player.currentPosition
                    _bufferedPosition.value = player.bufferedPosition
                    
                    // JANGAN polling save jika sedang dalam dialog restore
                    if (!_isRestoring.value && player.isPlaying && System.currentTimeMillis() - lastSaveTime >= 5000L) {
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

    fun seekTo(positionMs: Long) {
        _player.value?.let {
            it.seekTo(positionMs)
            _currentPosition.value = positionMs
        }
    }

    fun togglePlayPause() {
        // JANGAN toggle jika sedang dalam dialog restore
        if (_isRestoring.value) return

        _player.value?.let {
            val nextState = !it.playWhenReady
            playWhenReady = nextState
            it.playWhenReady = nextState
        }
    }

    fun replay() {
        _player.value?.let {
            it.seekTo(0)
            it.playWhenReady = true
            it.play()
        }
    }

    override fun onCleared() {
        // FINAL SAVE TRIGGER
        val player = _player.value
        val episode = _currentEpisode.value
        val isRestoring = _isRestoring.value

        if (!isRestoring && player != null && episode != null) {
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
        _player.value?.let {
            it.stop()
            it.release()
        }
        _player.value = null
    }
    
    fun onPlayPause(play: Boolean) {
        // JANGAN force play/pause jika sedang dalam dialog restore
        if (_isRestoring.value) return

        playWhenReady = play
        _player.value?.playWhenReady = play
    }
}
