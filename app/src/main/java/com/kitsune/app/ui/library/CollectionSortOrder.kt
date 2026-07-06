package com.kitsune.app.ui.library

/**
 * Pilihan pengurutan untuk kategori koleksi (Bookmark & Playlist).
 * Didesain agar mudah diperluas di masa depan (Phase 6.7.5).
 */
enum class CollectionSortOrder(val label: String) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    // Placeholder untuk ekspansi masa depan
    // NEWEST("Newest"),
    // OLDEST("Oldest"),
    // MOST_COMICS("Most Comics"),
    // RECENTLY_UPDATED("Recently Updated")
}
