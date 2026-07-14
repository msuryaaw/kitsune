package com.kitsune.app.ui.components.media

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * Komponen reusable untuk menampilkan indikator progres (0.0f - 1.0f).
 * Hanya muncul jika nilai progres di atas 0.
 */
@Composable
fun MediaProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.Transparent
) {
    if (progress > 0f) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier
                .fillMaxWidth()
                .height(3.dp),
            color = color,
            trackColor = trackColor,
            strokeCap = StrokeCap.Butt
        )
    }
}
