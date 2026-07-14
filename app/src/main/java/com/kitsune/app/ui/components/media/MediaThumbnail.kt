package com.kitsune.app.ui.components.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kitsune.app.domain.model.MediaType
import androidx.compose.ui.unit.dp

/**
 * Komponen untuk menampilkan thumbnail media (cover/poster).
 * Menangani loading gambar dan fallback placeholder menggunakan Box stack untuk efisiensi.
 */
@Composable
fun MediaThumbnail(
    thumbnailUri: String?,
    mediaType: MediaType,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 5f / 7f
) {
    val context = LocalContext.current
    
    val imageRequest = remember(thumbnailUri) {
        ImageRequest.Builder(context)
            .data(thumbnailUri)
            .crossfade(true)
            .size(300, 420) // Optimasi memori
            .build()
    }

    Card(
        modifier = modifier.aspectRatio(aspectRatio),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Placeholder diletakkan di bawah sebagai dasar
            MediaPlaceholder(mediaType = mediaType)
            
            // Gambar dimuat di atasnya
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
