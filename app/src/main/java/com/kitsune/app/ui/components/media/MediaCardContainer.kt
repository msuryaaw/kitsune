package com.kitsune.app.ui.components.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color

/**
 * Komponen pembungkus (Container) untuk kartu media.
 * Menangani aspek visual dasar seperti shape, klik, dan animasi skala saat interaksi.
 * Mengikuti prinsip pemisahan concern: Container hanya mengelola bingkai dan interaksi, bukan isi.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCardContainer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    // Animasi skala sederhana untuk feedback visual saat terpilih
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.95f else 1f,
        label = "MediaCardScale"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = Color.Transparent 
    ) {
        Column {
            content()
        }
    }
}
