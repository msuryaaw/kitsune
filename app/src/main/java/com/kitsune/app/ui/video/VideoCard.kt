package com.kitsune.app.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kitsune.app.domain.model.Video

/**
 * Komponen kartu untuk menampilkan item Video di Library.
 * Mengikuti optimasi performa Phase 6 dari Comic Library.
 */
@Composable
fun VideoCard(
    video: Video,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    
    // OPTIMIZATION: Optimized ImageRequest with remember and specific sizing (Phase 6 strategy)
    val imageRequest = remember(video.coverUri) {
        ImageRequest.Builder(context)
            .data(video.coverUri)
            .crossfade(true)
            .size(300, 420) // Target size for consistency and memory efficiency
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(5f / 7f), // Maintain same aspect ratio as Comics for UI consistency
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = video.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Normal,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
