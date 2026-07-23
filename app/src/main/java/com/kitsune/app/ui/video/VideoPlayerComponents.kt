package com.kitsune.app.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player

/**
 * Komponen overlay untuk kontrol pemutar video.
 * REVISION 8.2.3: Added Gesture Foundation Support to Interaction Layer.
 * REVISION 8.2.6: Added GesturePreviewOverlay for Polished UX.
 * REVISION 8.3.2: Added Manual Orientation Toggle Button.
 * REVISION 8.3.4: Moved Playback Controls to Bottom Area for improved visibility.
 */

/**
 * Lapisan interaksi utama untuk Video Player.
 * Mendukung Tap untuk Show/Hide Controls dan Drag untuk Gesture Engine.
 */
@Composable
fun PlayerInteractionLayer(
    onTap: () -> Unit,
    onDown: (x: Float, width: Float) -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Gesture Foundation: Pointer Input untuk deteksi Drag/Swipe
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onDown(offset.x, size.width.toFloat())
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onMove(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = { onUp() },
                    onDragCancel = { onUp() }
                )
            }
            // Tap Foundation: Pointer Input terpisah untuk deteksi Tap
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() }
                )
            }
    )
}

@Composable
fun GesturePreviewOverlay(
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    gestureDirection: GestureDirection,
    gestureArea: GestureArea,
    seekPreviewDelta: Long,
    seekPreviewPosition: Long,
    currentPosition: Long,
    duration: Long,
    brightnessPreview: Float,
    volumePreview: Int,
    maxVolume: Int
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when (gestureDirection) {
                GestureDirection.HORIZONTAL -> {
                    SeekPreviewContent(seekPreviewDelta, seekPreviewPosition, currentPosition, duration)
                }
                GestureDirection.VERTICAL -> {
                    if (gestureArea == GestureArea.LEFT) {
                        BrightnessPreviewContent(brightnessPreview)
                    } else {
                        VolumePreviewContent(volumePreview, maxVolume)
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun SeekPreviewContent(delta: Long, target: Long, current: Long, duration: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val isForward = delta >= 0
        Icon(
            imageVector = if (isForward) Icons.Default.FastForward else Icons.Default.FastRewind,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        val deltaSeconds = delta / 1000
        val sign = if (isForward) "+" else ""
        Text(
            text = "$sign${deltaSeconds}s",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTimestamp(target),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = " / ",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp
            )
            Text(
                text = formatTimestamp(duration),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }
        
        Text(
            text = "From: ${formatTimestamp(current)}",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun BrightnessPreviewContent(brightness: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val icon = when {
            brightness > 0.7f -> Icons.Default.BrightnessHigh
            brightness > 0.3f -> Icons.Default.BrightnessMedium
            else -> Icons.Default.BrightnessLow
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Brightness",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp
        )
        
        val percent = (brightness * 100).toInt()
        Text(
            text = "$percent%",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun VolumePreviewContent(volume: Int, max: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val icon = when {
            volume == 0 -> Icons.AutoMirrored.Filled.VolumeOff
            volume > max * 0.7f -> Icons.AutoMirrored.Filled.VolumeUp
            else -> Icons.AutoMirrored.Filled.VolumeDown
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Volume",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp
        )
        
        val percent = if (max > 0) (volume.toFloat() / max.toFloat() * 100).toInt() else 0
        Text(
            text = "$percent%",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    val remainingMinutes = minutes % 60

    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, remainingMinutes, seconds)
    } else {
        "%02d:%02d".format(remainingMinutes, seconds)
    }
}

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
    isLandscape: Boolean,
    onPlayPauseToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onReplay: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onToggleOrientation: () -> Unit,
    onInteraction: () -> Unit,
    onBackClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
    ) {
        // Top Controls (Back Button)
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(if (isLandscape) 16.dp else 8.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }

        // Area Tengah sengaja dikosongkan untuk Gesture Overlay & Buffering Indicator

        // Bottom Controls (Slider, Buttons & Timer)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = if (isLandscape) 32.dp else 16.dp)
                .padding(bottom = if (isLandscape) 24.dp else 16.dp)
                .navigationBarsPadding()
        ) {
            // Section 1: Progress Slider
            PlaybackSlider(
                currentPosition = currentPosition,
                duration = duration,
                bufferedPosition = bufferedPosition,
                onSeek = onSeek,
                onInteraction = onInteraction
            )

            // Section 2: Playback Buttons (Moved from Center)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (isLandscape) 12.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    space = if (isLandscape) 64.dp else 32.dp,
                    alignment = Alignment.CenterHorizontally
                )
            ) {
                // Previous Episode Button
                IconButton(
                    onClick = { 
                        onPreviousClick()
                        onInteraction()
                    },
                    enabled = hasPrevious,
                    modifier = Modifier.size(if (isLandscape) 56.dp else 48.dp)
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
                    ReplayButton(
                        onClick = {
                            onReplay()
                            onInteraction()
                        },
                        isLandscape = isLandscape
                    )
                } else {
                    IconButton(
                        onClick = {
                            onPlayPauseToggle()
                            onInteraction()
                        },
                        modifier = Modifier.size(if (isLandscape) 84.dp else 64.dp)
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
                    onClick = {
                        onNextClick()
                        onInteraction()
                    },
                    enabled = hasNext,
                    modifier = Modifier.size(if (isLandscape) 56.dp else 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Episode",
                        tint = Color.White.copy(alpha = if (hasNext) 1f else 0.3f),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            
            // Section 3: Timers & Orientation Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaybackTimer(positionMs = currentPosition)
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlaybackTimer(positionMs = duration)
                    
                    Spacer(modifier = Modifier.width(if (isLandscape) 16.dp else 8.dp))
                    
                    // REVISION 8.3.2: Manual Orientation Toggle Button
                    IconButton(
                        onClick = {
                            onToggleOrientation()
                            onInteraction()
                        }
                    ) {
                        Icon(
                            imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Toggle Orientation",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaybackSlider(
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit
) {
    var sliderPosition by remember(currentPosition) { mutableFloatStateOf(currentPosition.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Buffer Indicator
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
                onInteraction() // Reset timer during drag
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(sliderPosition.toLong())
                onInteraction() // Reset timer after drag
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
    Text(
        text = formatTimestamp(positionMs),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White
    )
}

@Composable
fun ReplayButton(
    onClick: () -> Unit,
    isLandscape: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(if (isLandscape) 84.dp else 64.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Replay,
            contentDescription = "Replay",
            tint = Color.White,
            modifier = Modifier.fillMaxSize()
        )
    }
}
