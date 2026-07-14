package com.kitsune.app.ui.video

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * Screen untuk pemutaran video menggunakan ExoPlayer dengan kontrol kustom.
 * REVISION 8.1.6: Added Landscape UI Adaptation & Immersive Mode.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val player by viewModel.player.collectAsState()
    val errorState by viewModel.errorState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current

    // ORIENTATION FOUNDATION
    val isLandscape by viewModel.isLandscape.collectAsState()
    
    LaunchedEffect(configuration.orientation) {
        viewModel.updateOrientation(configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    // SYSTEM UI ADAPTATION (Phase 8.1.6)
    // Handle Immersive Mode for Landscape
    val activity = context as? Activity
    DisposableEffect(isLandscape) {
        if (isLandscape && activity != null) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else if (activity != null) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (isLandscape && activity != null) {
                val window = activity.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Visibility State (Phase 8.1.2)
    val isControlsVisible by viewModel.isControlsVisible.collectAsState()

    // Resume Dialog State (Phase 7.7.2)
    val showResumeDialog by viewModel.showResumeDialog.collectAsState()

    // PlayerView dibuat satu kali menggunakan remember
    val playerView = remember {
        PlayerView(context).apply {
            useController = false
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    // Hubungkan player ke PlayerView saat player siap
    LaunchedEffect(player) {
        playerView.player = player
    }

    // Lifecycle Management: Pause saat background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.onPlayPause(false)
                }
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onPlayPause(true)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Cleanup saat keluar dari komposisi
    DisposableEffect(Unit) {
        onDispose {
            playerView.player = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (errorState != null) {
            Text(
                text = errorState!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (player == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            // Player Surface
            AndroidView(
                factory = { playerView },
                modifier = Modifier.fillMaxSize()
            )

            // INTERACTION LAYER (Phase 8.1.4)
            PlayerInteractionLayer(
                onTap = { viewModel.toggleControls() }
            )

            // Buffering Indicator
            val isBuffering by viewModel.isBuffering.collectAsState()
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Custom Controls Overlay (Phase 8.1.6: Responsive Layout)
            val isPlaying by viewModel.isPlaying.collectAsState()
            val playbackState by viewModel.playbackState.collectAsState()
            val currentPosition by viewModel.currentPosition.collectAsState()
            val duration by viewModel.duration.collectAsState()
            val bufferedPosition by viewModel.bufferedPosition.collectAsState()
            
            // Sequential States (Phase 7.6.3)
            val hasNext by viewModel.hasNext.collectAsState()
            val hasPrevious by viewModel.hasPrevious.collectAsState()

            AnimatedVisibility(
                visible = isControlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                PlayerControls(
                    isPlaying = isPlaying,
                    playbackState = playbackState,
                    currentPosition = currentPosition,
                    duration = duration,
                    bufferedPosition = bufferedPosition,
                    hasNext = hasNext,
                    hasPrevious = hasPrevious,
                    isLandscape = isLandscape,
                    onPlayPauseToggle = { viewModel.togglePlayPause() },
                    onSeek = { viewModel.seekTo(it) },
                    onReplay = { viewModel.replay() },
                    onNextClick = { viewModel.nextEpisode() },
                    onPreviousClick = { viewModel.previousEpisode() },
                    onInteraction = { viewModel.userInteraction() },
                    onBackClick = onBackClick
                )
            }

            // Resume Playback Dialog (Phase 7.7.2)
            showResumeDialog?.let { progress ->
                AlertDialog(
                    onDismissRequest = { /* Force choice */ },
                    title = { Text("Continue watching?") },
                    text = {
                        val totalSeconds = progress.lastPositionMs / 1000
                        val minutes = totalSeconds / 60
                        val seconds = totalSeconds % 60
                        val hours = minutes / 60
                        val remainingMinutes = minutes % 60
                        val timeString = if (hours > 0) {
                            "%02d:%02d:%02d".format(hours, remainingMinutes, seconds)
                        } else {
                            "%02d:%02d".format(remainingMinutes, seconds)
                        }
                        Text("Resume from $timeString?")
                    },
                    confirmButton = {
                        Button(onClick = { viewModel.onResumePlayback() }) {
                            Text("Resume")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.onStartOver() }) {
                            Text("Start Over")
                        }
                    }
                )
            }
        }
    }
}
