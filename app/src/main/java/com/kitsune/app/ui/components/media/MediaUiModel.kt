package com.kitsune.app.ui.components.media

import androidx.compose.runtime.Immutable
import com.kitsune.app.domain.model.MediaType
import com.kitsune.app.ui.library.ComicStatus

/**
 * Model UI terpadu untuk merepresentasikan item media (Komik atau Video).
 * Digunakan sebagai kontrak data untuk komponen Media UI Foundation.
 * 
 * REVISION 7.8.11: Added statuses for Bookmark and Playlist indicators.
 */
@Immutable
data class MediaUiModel(
    val id: String,
    val title: String,
    val thumbnailUri: String?,
    val progress: Float = 0f,
    val isFinished: Boolean = false,
    val mediaType: MediaType,
    val statuses: Set<ComicStatus> = emptySet()
)
