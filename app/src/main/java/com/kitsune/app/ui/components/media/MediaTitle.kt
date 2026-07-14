package com.kitsune.app.ui.components.media

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * Komponen reusable untuk menampilkan judul media.
 * Mendukung pembatasan baris dan penyesuaian gaya visual secara generic.
 */
@Composable
fun MediaTitle(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = 2
) {
    Text(
        text = title,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
