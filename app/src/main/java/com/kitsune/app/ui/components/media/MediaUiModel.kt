package com.kitsune.app.ui.components.media

import androidx.compose.runtime.Immutable
import com.kitsune.app.domain.model.MediaType
import com.kitsune.app.ui.library.ComicStatus

/**
 * Model UI terpadu untuk merepresentasikan item media (Komik atau Video).
 * Digunakan sebagai kontrak data untuk komponen Media UI Foundation.
 * 
 * REVISION 7.8.11: Added statuses for Bookmark and Playlist indicators.
 * REVISION 11.2.3: Added displayTitle, author, and language.
 * REVISION 11.3.3: Added type.
 * REVISION 11.4.1: Added badgeText for flexible extra info.
 */
@Immutable
data class MediaUiModel(
    val id: String,
    val title: String, // Original
    val displayTitle: String, // Clean
    val author: String? = null,
    val language: String? = null,
    val type: String? = null,
    val badgeText: String? = null,
    val thumbnailUri: String?,
    val progress: Float = 0f,
    val isFinished: Boolean = false,
    val mediaType: MediaType,
    val statuses: Set<ComicStatus> = emptySet()
)
