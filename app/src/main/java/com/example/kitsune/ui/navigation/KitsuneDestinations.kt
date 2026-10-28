package com.example.kitsune.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level navigation destinations for Kitsune application shell.
 */
enum class TopLevelDestination(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    LIBRARY("library", "Library", Icons.AutoMirrored.Filled.MenuBook),
    BOOKMARKS("bookmarks", "Bookmarks", Icons.Default.Bookmark),
    HISTORY("history", "History", Icons.Default.History),
    SETTINGS("settings", "Settings", Icons.Default.Settings);

    companion object {
        fun fromRoute(route: String?): TopLevelDestination =
            entries.firstOrNull { it.route == route } ?: LIBRARY
    }
}

/**
 * All navigation routes in Kitsune.
 */
object KitsuneDestinations {
    const val LIBRARY_ROUTE = "library"
    const val BOOKMARKS_ROUTE = "bookmarks"
    const val HISTORY_ROUTE = "history"
    const val SETTINGS_ROUTE = "settings"
    const val COMIC_DETAIL_ROUTE = "comic_detail/{comicId}"
    const val READER_ROUTE = "reader/{chapterId}"

    fun comicDetailRoute(comicId: Long): String = "comic_detail/$comicId"
    fun readerRoute(chapterId: Long): String = "reader/$chapterId"
}

