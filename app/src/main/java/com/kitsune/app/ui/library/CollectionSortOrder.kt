package com.kitsune.app.ui.library

/**
 * Pilihan pengurutan untuk kategori koleksi (Bookmark & Playlist).
 */
enum class CollectionSortOrder(val label: String) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)")
}

/**
 * Pilihan pengurutan spesifik untuk Komik.
 * REVISION 11.3.1: Implemented as per Mentor Requirements.
 */
enum class ComicSortOrder(val label: String) {
    TITLE_ASC("Title (A-Z)"),
    TITLE_DESC("Title (Z-A)"),
    AUTHOR_ASC("Author (A-Z)"),
    AUTHOR_DESC("Author (Z-A)"),
    DATE_ADDED_DESC("Date Added (Newest)")
}
