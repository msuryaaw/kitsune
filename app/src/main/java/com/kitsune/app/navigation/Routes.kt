package com.kitsune.app.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Main : Screen("main") // Container for bottom nav screens
    
    // Bottom Nav Destinations
    object Bookmark : Screen("bookmark")
    object Playlist : Screen("playlist")
    object Local : Screen("local")
    object Other : Screen("other")

    // Library Screens
    object ComicLibrary : Screen("comic_library")
    object VideoLibrary : Screen("video_library")

    // Detail Screens
    object ComicDetail : Screen("comic_detail/{comicRelativePath}") {
        fun createRoute(comicRelativePath: String): String {
            return "comic_detail/${Uri.encode(comicRelativePath)}"
        }
    }

    object BookmarkDetail : Screen("bookmark_detail/{bookmarkId}") {
        fun createRoute(bookmarkId: Long): String = "bookmark_detail/$bookmarkId"
    }

    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: Long): String = "playlist_detail/$playlistId"
    }

    object Reader : Screen("reader/{comicRelativePath}/{chapterRelativePath}") {
        fun createRoute(comicRelativePath: String, chapterRelativePath: String): String {
            return "reader/${Uri.encode(comicRelativePath)}/${Uri.encode(chapterRelativePath)}"
        }
    }
}
