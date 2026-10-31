package com.example.kitsune.ui.screens.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.kitsune.reader.PageDecodeResult

@Composable
fun SinglePageView(
    currentPageIndex: Int,
    readingDirection: String,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val targetWidthPx = with(density) { maxWidth.roundToPx() }.coerceIn(1, 2048)
        val targetHeightPx = with(density) { maxHeight.roundToPx() }.coerceIn(1, 2048)

        val renderState by produceState<PageRenderState>(
            initialValue = PageRenderState.Loading,
            key1 = currentPageIndex,
            key2 = targetWidthPx,
            key3 = targetHeightPx
        ) {
            value = PageRenderState.Loading
            when (val decodeResult = viewModel.decodePage(currentPageIndex, targetWidthPx, targetHeightPx)) {
                is PageDecodeResult.Success -> {
                    value = PageRenderState.Loaded(decodeResult.bitmap.asImageBitmap())
                }
                is PageDecodeResult.ImageCorrupt -> {
                    value = PageRenderState.Corrupt("Gagal membaca halaman ${currentPageIndex + 1}: ${decodeResult.message}")
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
                Image(
                    painter = BitmapPainter(state.imageBitmap),
                    contentDescription = "Halaman ${currentPageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is PageRenderState.Corrupt -> {
                PageErrorCard(message = state.message)
            }
            is PageRenderState.Error -> {
                PageErrorCard(message = state.message)
            }
        }

        // Tap gesture navigation zones mapped according to readingDirection
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        if (readingDirection == "rtl") {
                            viewModel.goToNextPage()
                        } else {
                            viewModel.goToPreviousPage()
                        }
                    }
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        if (readingDirection == "rtl") {
                            viewModel.goToPreviousPage()
                        } else {
                            viewModel.goToNextPage()
                        }
                    }
            )
        }

        // Explicit Previous and Next IconButton Navigation Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.goToPreviousPage() }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                    contentDescription = "Halaman Sebelumnya"
                )
            }
            Spacer(modifier = Modifier.width(32.dp))
            IconButton(
                onClick = { viewModel.goToNextPage() }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = "Halaman Berikutnya"
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
