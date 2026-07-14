package com.kitsune.app.ui.components.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Komponen Grid Media Generic.
 * Bertanggung jawab atas layouting, spacing, dan performance grid tanpa mengetahui tipe media spesifik.
 * Menggunakan Slot API (itemContent) untuk me-render item di dalam grid.
 */
@Composable
fun <T> MediaGrid(
    items: List<T>,
    gridSize: Int,
    keySelector: (T) -> Any,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(12.dp),
    verticalSpacing: androidx.compose.ui.unit.Dp = 16.dp,
    horizontalSpacing: androidx.compose.ui.unit.Dp = 12.dp,
    contentType: (T) -> Any? = { null },
    itemContent: @Composable LazyGridItemScope.(T) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridSize),
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        modifier = modifier.fillMaxSize()
    ) {
        items(
            items = items,
            key = keySelector,
            contentType = contentType
        ) { item ->
            itemContent(item)
        }
    }
}
