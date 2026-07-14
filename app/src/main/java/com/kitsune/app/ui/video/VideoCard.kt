package com.kitsune.app.ui.video

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kitsune.app.ui.components.media.*
import com.kitsune.app.ui.components.media.mapper.toMediaUiModel

/**
 * Komponen kartu untuk menampilkan item Video di Library.
 * REVISION 7.8.4: Migrated to Unified Media Foundation.
 * REVISION 7.8.11: Added status badges for Bookmark and Playlist.
 * REVISION 7.9.2: Migrated to Unified Media Badges.
 */
@Composable
fun VideoCard(
    state: VideoItemState,
    onClick: () -> Unit
) {
    val mediaUiModel = remember(state) { state.toMediaUiModel(state.statuses) }

    MediaCardContainer(
        onClick = onClick
    ) {
        Box {
            MediaThumbnail(
                thumbnailUri = mediaUiModel.thumbnailUri,
                mediaType = mediaUiModel.mediaType
            )

            // Collection Badges (Top Start) - Unified 7.9.2
            MediaCollectionBadges(
                statuses = mediaUiModel.statuses,
                modifier = Modifier.align(Alignment.TopStart)
            )

            // Finished Badge (Top End) - Unified 7.9.2
            if (state.isFinished) {
                MediaFinishedBadge(
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }

            // Progress Bar (Bottom) - Phase 7.7.3.3 (Unified via MediaProgressBar)
            MediaProgressBar(
                progress = state.watchedPercentage,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        MediaTitle(
            title = mediaUiModel.title,
            fontWeight = if (state.watchedPercentage > 0) FontWeight.Bold else FontWeight.Normal,
            color = if (state.isFinished) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.White,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
