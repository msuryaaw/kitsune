package com.kitsune.app.domain.model

/**
 * Model data generic untuk item di dalam koleksi (Bookmark/Playlist).
 * Menyatukan representasi Komik dan Video dalam konteks koleksi.
 */
data class MediaCollectionItem(
    val relativePath: String,
    val title: String,
    val thumbnailUri: String?,
    val mediaType: MediaType,
    val collectionType: CollectionType
)
