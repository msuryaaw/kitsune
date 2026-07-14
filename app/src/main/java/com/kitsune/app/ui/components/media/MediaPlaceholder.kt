package com.kitsune.app.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kitsune.app.domain.model.MediaType

/**
 * Komponen placeholder untuk item media jika gambar gagal dimuat atau tidak tersedia.
 * Menampilkan ikon yang berbeda berdasarkan tipe media.
 */
@Composable
fun MediaPlaceholder(
    mediaType: MediaType,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E)),
        contentAlignment = Alignment.Center
    ) {
        val icon = when (mediaType) {
            MediaType.COMIC -> Icons.Default.Book
            MediaType.VIDEO -> Icons.Default.PlayArrow
        }
        
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Gray.copy(alpha = 0.5f),
            modifier = Modifier.size(48.dp)
        )
    }
}
