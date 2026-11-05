package com.example.kitsune.ui.screens.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.kitsune.reader.PageDecodeResult

@Composable
fun SinglePageView(
    currentPageIndex: Int,
    readingDirection: String,
    pageFit: String = "fit_screen",
    viewModel: ReaderViewModel,
    onToggleControls: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 48.dp.toPx() }
    val horizontalDragOffset = remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(readingDirection) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        horizontalDragOffset.floatValue = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        horizontalDragOffset.floatValue += dragAmount
                    },
                    onDragEnd = {
                        val totalDx = horizontalDragOffset.floatValue
                        when (ReaderGestureHelper.resolveHorizontalSwipeAction(totalDx, swipeThresholdPx, readingDirection)) {
                            ReaderGestureHelper.SwipeAction.FORWARD -> viewModel.onSwipeForward()
                            ReaderGestureHelper.SwipeAction.BACKWARD -> viewModel.onSwipeBackward()
                            ReaderGestureHelper.SwipeAction.NONE -> { /* Below threshold or no-op */ }
                        }
                        horizontalDragOffset.floatValue = 0f
                    },
                    onDragCancel = {
                        // Gesture cancellation does not navigate
                        horizontalDragOffset.floatValue = 0f
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        // Center tap only (middle 50% width and height)
                        if (ReaderGestureHelper.isCenterTap(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())) {
                            onToggleControls()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val viewportHeightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val (targetWidthPx, targetHeightPx) = PageScaleCalculator.calculateTargetDecodeDimensions(
            viewportWidth = viewportWidthPx,
            viewportHeight = viewportHeightPx,
            pageFit = pageFit
        )
        val retryTrigger = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

        val contentScale = when (pageFit) {
            "fit_width" -> ContentScale.FillWidth
            "fit_height" -> ContentScale.Inside // Preserves aspect ratio, fits height, and prevents wide-image horizontal overflow
            else -> ContentScale.Fit
        }

        val renderState by produceState<PageRenderState>(
            initialValue = PageRenderState.Loading,
            currentPageIndex,
            targetWidthPx,
            targetHeightPx,
            retryTrigger.intValue
        ) {
            value = PageRenderState.Loading
            when (val decodeResult = viewModel.decodePage(currentPageIndex, targetWidthPx, targetHeightPx)) {
                is PageDecodeResult.Success -> {
                    value = PageRenderState.Loaded(decodeResult.bitmap.asImageBitmap())
                }
                is PageDecodeResult.ImageCorrupt -> {
                    value = PageRenderState.Corrupt("Header gambar tidak valid atau corrupt: ${decodeResult.message}")
                }
                is PageDecodeResult.PageNotFound -> {
                    value = PageRenderState.Error("Halaman ${currentPageIndex + 1} tidak ditemukan")
                }
                is PageDecodeResult.ArchiveClosed -> {
                    value = PageRenderState.Error("Reader telah ditutup")
                }
                is PageDecodeResult.Error -> {
                    value = PageRenderState.Error("Gagal mendecode halaman ${currentPageIndex + 1}: ${decodeResult.exception.localizedMessage}")
                }
            }
        }

        when (val state = renderState) {
            is PageRenderState.Loading -> {
                CircularProgressIndicator()
            }
            is PageRenderState.Loaded -> {
                if (pageFit == "fit_width") {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Image(
                            painter = BitmapPainter(state.imageBitmap),
                            contentDescription = "Halaman ${currentPageIndex + 1}",
                            contentScale = contentScale,
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                        )
                    }
                } else {
                    Image(
                        painter = BitmapPainter(state.imageBitmap),
                        contentDescription = "Halaman ${currentPageIndex + 1}",
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            is PageRenderState.Corrupt -> {
                PageErrorCard(
                    message = state.message,
                    onRetry = { retryTrigger.intValue++ },
                    onSkip = { viewModel.goToNextPage() }
                )
            }
            is PageRenderState.Error -> {
                PageErrorCard(
                    message = state.message,
                    onRetry = { retryTrigger.intValue++ },
                    onSkip = { viewModel.goToNextPage() }
                )
            }
        }
    }
}

sealed interface PageRenderState {
    object Loading : PageRenderState
    data class Loaded(val imageBitmap: androidx.compose.ui.graphics.ImageBitmap) : PageRenderState
    data class Corrupt(val message: String) : PageRenderState
    data class Error(val message: String) : PageRenderState
}

@Composable
private fun PageErrorCard(
    message: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Failed to Load Page",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = onRetry) {
                    Text("Retry")
                }
                androidx.compose.material3.OutlinedButton(onClick = onSkip) {
                    Text("Halaman Berikutnya")
                }
            }
        }
    }
}