package com.example.kitsune.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level navigation destinations for Kitsune application shell (Phase 11).
 * Bottom navigation order: Bookmarks -> Home -> Settings.
 * Home is the initial start destination.
 */
enum class TopLevelDestination(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    BOOKMARKS("bookmarks", "Bookmarks", Icons.Default.Bookmark),
    HOME("home", "Home", Icons.Default.Home),
    SETTINGS("settings", "Settings", Icons.Default.Settings);

    companion object {
        fun fromRoute(route: String?): TopLevelDestination =
            when {
                route == null -> HOME
                route == BOOKMARKS.route || route.startsWith("bookmark_detail/") -> BOOKMARKS
                else -> entries.firstOrNull { it.route == route } ?: HOME
            }
    }
}

/**
 * All navigation routes in Kitsune.
 */
object KitsuneDestinations {
    const val HOME_ROUTE = "home"
    const val BOOKMARKS_ROUTE = "bookmarks"
    const val SETTINGS_ROUTE = "settings"
    const val LIBRARY_ROUTE = "library"
    const val HISTORY_ROUTE = "history"
    const val BOOKMARK_DETAIL_ROUTE = "bookmark_detail/{collectionId}"
    const val COMIC_DETAIL_ROUTE = "comic_detail/{comicId}"
    const val READER_ROUTE = "reader/{chapterId}"

    fun bookmarkDetailRoute(collectionId: Long): String = "bookmark_detail/$collectionId"
    fun comicDetailRoute(comicId: Long): String = "comic_detail/$comicId"
    fun readerRoute(chapterId: Long): String = "reader/$chapterId"
}
