package com.example.kitsune.ui.screens.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
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
    viewModel: ReaderViewModel,
    onPositionChanged: (pageIndex: Int, scrollOffset: Int) -> Unit,
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

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val parentWidthPx = with(density) { maxWidth.roundToPx() }.coerceIn(1, 2048)
        val parentHeightPx = with(density) { maxHeight.roundToPx() }.coerceIn(1, 2048)

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
            contentPadding = PaddingValues(vertical = 16.dp)
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
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        val renderState by produceState<PageRenderState>(
            initialValue = PageRenderState.Loading,
            page.pageIndex,
            targetWidthPx,
            targetHeightPx,
            isVisible
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
                    value = PageRenderState.Corrupt("Gagal membaca halaman ${page.pageNumber}: ${decodeResult.message}")
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
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is PageRenderState.Corrupt -> {
                ContinuousErrorCard(message = state.message)
            }
            is PageRenderState.Error -> {
                ContinuousErrorCard(message = state.message)
            }
        }
    }
}

@Composable
private fun ContinuousErrorCard(
    message: String,
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
                text = "Gagal Memuat Halaman",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
