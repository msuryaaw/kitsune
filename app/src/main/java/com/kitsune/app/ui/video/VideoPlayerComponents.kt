package com.kitsune.app.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

/**
 * Komponen overlay untuk kontrol pemutar video.
 * REVISION 7.6.3: Integrasi Navigasi Episode (Previous, Play/Pause, Next).
 */
@Composable
fun PlayerControls(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    playbackState: Int,
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    hasNext: Boolean,
    hasPrevious: Boolean,
    onPlayPauseToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onReplay: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
    ) {
        // Center Controls (Previous, Play/Pause, Next) - Phase 7.6.3
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Previous Episode Button
            IconButton(
                onClick = onPreviousClick,
                enabled = hasPrevious,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Episode",
                    tint = Color.White.copy(alpha = if (hasPrevious) 1f else 0.3f),
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Main Play/Pause/Replay Button
            if (playbackState == Player.STATE_ENDED) {
                ReplayButton(onClick = onReplay)
            } else {
                IconButton(
                    onClick = onPlayPauseToggle,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Next Episode Button
            IconButton(
                onClick = onNextClick,
                enabled = hasNext,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next Episode",
                    tint = Color.White.copy(alpha = if (hasNext) 1f else 0.3f),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Bottom Controls (Slider & Timer)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            PlaybackSlider(
                currentPosition = currentPosition,
                duration = duration,
                bufferedPosition = bufferedPosition,
                onSeek = onSeek
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PlaybackTimer(positionMs = currentPosition)
                PlaybackTimer(positionMs = duration)
            }
        }
    }
}

@Composable
fun PlaybackSlider(
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    onSeek: (Long) -> Unit
) {
    var sliderPosition by remember(currentPosition) { mutableFloatStateOf(currentPosition.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Buffer Indicator (Secondary Track)
        if (duration > 0) {
            LinearProgressIndicator(
                progress = { bufferedPosition.toFloat() / duration.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(horizontal = 12.dp),
                color = Color.White.copy(alpha = 0.3f),
                trackColor = Color.Transparent
            )
        }

        Slider(
            value = if (isDragging) sliderPosition else currentPosition.toFloat(),
            onValueChange = {
                isDragging = true
                sliderPosition = it
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(sliderPosition.toLong())
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
    }
}

@Composable
fun PlaybackTimer(positionMs: Long) {
    val totalSeconds = positionMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    val remainingMinutes = minutes % 60

    val timeString = if (hours > 0) {
        "%02d:%02d:%02d".format(hours, remainingMinutes, seconds)
    } else {
        "%02d:%02d".format(remainingMinutes, seconds)
    }

    Text(
        text = timeString,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White
    )
}

@Composable
fun ReplayButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Replay,
            contentDescription = "Replay",
            tint = Color.White,
            modifier = Modifier.fillMaxSize()
        )
    }
}
