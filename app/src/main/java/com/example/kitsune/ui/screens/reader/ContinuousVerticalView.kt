package com.example.kitsune.ui.screens.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.kitsune.reader.ChapterPageIndex
import com.example.kitsune.reader.PageDecodeResult
import com.example.kitsune.reader.PageMetadata
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ContinuousVerticalView(
    chapterPageIndex: ChapterPageIndex,
    initialPageIndex: Int,
    initialScrollOffset: Int = 0,
    pageFit: String = "fit_screen",
    readingDirection: String = "ltr",
    viewModel: ReaderViewModel,
    onPositionChanged: (pageIndex: Int, scrollOffset: Int) -> Unit,
    onToggleControls: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lazyListState = androidx.compose.runtime.key(chapterPageIndex.chapterId) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialPageIndex.coerceIn(0, (chapterPageIndex.totalPages - 1).coerceAtLeast(0)),
            initialFirstVisibleItemScrollOffset = initialScrollOffset.coerceAtLeast(0)
        )
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            Pair(lazyListState.firstVisibleItemIndex, lazyListState.firstVisibleItemScrollOffset)
        }.distinctUntilChanged().collect { (index, offset) ->
            onPositionChanged(index, offset)
        }
    }

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
                        val isAtTop = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
                        val isAtBottom = !lazyListState.canScrollForward

                        when (ReaderGestureHelper.resolveContinuousBoundaryAction(totalDx, swipeThresholdPx, readingDirection, isAtTop, isAtBottom)) {
                            ReaderGestureHelper.SwipeAction.FORWARD -> viewModel.navigateToNextChapter()
                            ReaderGestureHelper.SwipeAction.BACKWARD -> viewModel.navigateToPreviousChapter(startAtLastPage = true)
                            ReaderGestureHelper.SwipeAction.NONE -> { /* Ignored (middle of chapter or below threshold) */ }
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
        val (parentWidthPx, parentHeightPx) = PageScaleCalculator.calculateTargetDecodeDimensions(
            viewportWidth = viewportWidthPx,
            viewportHeight = viewportHeightPx,
            pageFit = "fit_width"
        )

        val visibleIndices by produceState<Set<Int>>(
            initialValue = setOf(initialPageIndex),
            key1 = lazyListState
        ) {
            snapshotFlow {
                lazyListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
            }.collect { value = it }
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(
                items = chapterPageIndex.pages,
                key = { it.entryName }
            ) { page ->
                val isVisible = page.pageIndex in visibleIndices
                ContinuousPageItem(
                    page = page,
                    isVisible = isVisible,
                    targetWidthPx = parentWidthPx,
                    targetHeightPx = parentHeightPx,
                    pageFit = pageFit,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun ContinuousPageItem(
    page: PageMetadata,
    isVisible: Boolean,
    targetWidthPx: Int,
    targetHeightPx: Int,
    pageFit: String,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    val contentScale = when (pageFit) {
        "fit_height" -> ContentScale.FillHeight
        "fit_screen" -> ContentScale.Fit
        "fit_width" -> ContentScale.FillWidth
        else -> ContentScale.FillWidth
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        val retryTrigger = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

        val renderState by produceState<PageRenderState>(
            initialValue = PageRenderState.Loading,
            page.pageIndex,
            targetWidthPx,
            targetHeightPx,
            isVisible,
            retryTrigger.intValue
        ) {
            if (!isVisible) {
                value = PageRenderState.Loading
                return@produceState
            }

            value = PageRenderState.Loading
            when (val decodeResult = viewModel.decodePage(page.pageIndex, targetWidthPx, targetHeightPx)) {
                is PageDecodeResult.Success -> {
                    value = PageRenderState.Loaded(decodeResult.bitmap.asImageBitmap())
                }
                is PageDecodeResult.ImageCorrupt -> {
                    value = PageRenderState.Corrupt("Header gambar tidak valid atau corrupt: ${decodeResult.message}")
                }
                is PageDecodeResult.PageNotFound -> {
                    value = PageRenderState.Error("Halaman ${page.pageNumber} tidak ditemukan")
                }
                is PageDecodeResult.ArchiveClosed -> {
                    value = PageRenderState.Error("Reader telah ditutup")
                }
                is PageDecodeResult.Error -> {
                    value = PageRenderState.Error("Gagal mendecode halaman ${page.pageNumber}: ${decodeResult.exception.localizedMessage}")
                }
            }
        }

        when (val state = renderState) {
            is PageRenderState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.padding(32.dp))
            }
            is PageRenderState.Loaded -> {
                Image(
                    painter = BitmapPainter(state.imageBitmap),
                    contentDescription = "Halaman ${page.pageNumber}",
                    contentScale = contentScale,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                )
            }
            is PageRenderState.Corrupt -> {
                ContinuousErrorCard(
                    message = state.message,
                    onRetry = { retryTrigger.intValue++ }
                )
            }
            is PageRenderState.Error -> {
                ContinuousErrorCard(
                    message = state.message,
                    onRetry = { retryTrigger.intValue++ }
                )
            }
        }
    }
}

@Composable
private fun ContinuousErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
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
            androidx.compose.material3.Button(onClick = onRetry) {
                Text("Coba Lagi")
            }
        }
    }
}