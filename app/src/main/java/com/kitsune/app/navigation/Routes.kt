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

    // History Screens
    object ReadHistory : Screen("read_history")
    object WatchHistory : Screen("watch_history")

    // Library Screens
    object ComicLibrary : Screen("comic_library")
    object VideoLibrary : Screen("video_library")

    // Detail Screens
    object ComicDetail : Screen("comic_detail/{comicRelativePath}") {
        fun createRoute(comicRelativePath: String): String {
            return "comic_detail/${Uri.encode(comicRelativePath)}"
        }
    }

    object VideoDetail : Screen("video_detail/{videoRelativePath}") {
        fun createRoute(videoRelativePath: String): String {
            return "video_detail/${Uri.encode(videoRelativePath)}"
        }
    }

    object VideoPlayer : Screen("video_player/{videoRelativePath}/{episodeRelativePath}") {
        fun createRoute(videoRelativePath: String, episodeRelativePath: String): String {
            return "video_player/${Uri.encode(videoRelativePath)}/${Uri.encode(episodeRelativePath)}"
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
