package com.kitsune.app.ui.video

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.kitsune.app.ui.library.ComicStatus

/**
 * Komponen kartu untuk menampilkan item Video di Library.
 * REVISION 7.8.4: Migrated to Unified Media Foundation.
 * REVISION 7.8.11: Added status badges for Bookmark and Playlist.
 * REVISION 7.9.2: Migrated to Unified Media Badges.
 * REVISION 8.3.3: Added Selection support.
 * REVISION 8.3.7: Simplified indicators by removing progress and finished badges.
 */
@Composable
fun VideoCard(
    state: VideoItemState,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val mediaUiModel = remember(state) { state.toMediaUiModel(state.statuses) }
    val isInPlaylist = remember(state.statuses) { state.statuses.contains(ComicStatus.IN_PLAYLIST) }

    MediaCardContainer(
        onClick = onClick,
        onLongClick = onLongClick,
        isSelected = isSelected
    ) {
        Box {
            MediaThumbnail(
                thumbnailUri = mediaUiModel.thumbnailUri,
                mediaType = mediaUiModel.mediaType,
                modifier = if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.shapes.medium
                ) else Modifier
            )

            // Dimmed Overlay for videos in a playlist (Task C)
            if (isInPlaylist && !isSelected) {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    color = Color.Black.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.medium
                ) {}
            }

            // Collection Badges (Top Start) - Filter out IN_PLAYLIST badge to remove Playlist icon badge from Video Library
            val filteredStatuses = remember(mediaUiModel.statuses) {
                mediaUiModel.statuses.filter { it != ComicStatus.IN_PLAYLIST }.toSet()
            }

            if (!isSelected && filteredStatuses.isNotEmpty()) {
                MediaCollectionBadges(
                    statuses = filteredStatuses,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }

            // Selection Indicator (Top End)
            if (isSelected) {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.medium
                ) {}
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                        .align(Alignment.TopEnd)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        MediaTitle(
            title = mediaUiModel.title,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
