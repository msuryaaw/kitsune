package com.kitsune.app.ui.components.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kitsune.app.ui.library.ComicStatus

/**
 * Komponen badge reusable untuk menampilkan indikator status pada media (e.g. Finished, Bookmark).
 * Desain bersifat generic dan menerima ikon, warna, serta bentuk melalui parameter.
 */
@Composable
fun MediaBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black.copy(alpha = 0.7f),
    iconTint: Color = Color.White,
    shape: Shape = RoundedCornerShape(4.dp),
    contentDescription: String? = null
) {
    Surface(
        shape = shape,
        color = backgroundColor,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier
                .padding(4.dp)
                .size(16.dp)
        )
    }
}

/**
 * REVISION 7.9.2: Unified Collection Badges.
 * Renders a row of badges based on the media status (Bookmark, Playlist).
 */
@Composable
fun MediaCollectionBadges(
    statuses: Set<ComicStatus>,
    modifier: Modifier = Modifier
) {
    if (statuses.isEmpty()) return
    
    Row(
        modifier = modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        statuses.forEach { status ->
            val icon = when (status) {
                ComicStatus.BOOKMARKED -> Icons.Default.Bookmark
                ComicStatus.IN_PLAYLIST -> Icons.AutoMirrored.Filled.List
            }
            val tint = when (status) {
                ComicStatus.BOOKMARKED -> MaterialTheme.colorScheme.primary
                ComicStatus.IN_PLAYLIST -> Color(0xFF4CAF50) // Green for playlist
            }
            MediaBadge(
                icon = icon,
                iconTint = tint,
                contentDescription = status.name
            )
        }
    }
}

/**
 * REVISION 7.9.2: Unified Finished Badge.
 * Renders a "CheckCircle" badge at the corner of the media item.
 */
@Composable
fun MediaFinishedBadge(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(bottomStart = 8.dp)
) {
    MediaBadge(
        icon = Icons.Default.CheckCircle,
        backgroundColor = MaterialTheme.colorScheme.primary,
        iconTint = Color.White,
        shape = shape,
        modifier = modifier,
        contentDescription = "Finished"
    )
}
