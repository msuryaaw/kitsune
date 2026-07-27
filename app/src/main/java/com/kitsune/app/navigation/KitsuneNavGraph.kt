package com.kitsune.app.navigation

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.data.metadata.MetadataManager
import com.kitsune.app.data.repository.*
import com.kitsune.app.ui.bookmark.BookmarkDetailScreen
import com.kitsune.app.ui.bookmark.BookmarkDetailViewModel
import com.kitsune.app.ui.bookmark.BookmarkScreen
import com.kitsune.app.ui.bookmark.BookmarkViewModel
import com.kitsune.app.ui.comicdetail.ComicDetailScreen
import com.kitsune.app.ui.comicdetail.ComicDetailViewModel
import com.kitsune.app.ui.library.ComicLibraryScreen
import com.kitsune.app.ui.library.LibraryViewModel
import com.kitsune.app.ui.local.LocalScreen
import com.kitsune.app.ui.local.LocalViewModel
import com.kitsune.app.ui.playlist.PlaylistDetailScreen
import com.kitsune.app.ui.playlist.PlaylistDetailViewModel
import com.kitsune.app.ui.playlist.PlaylistScreen
import com.kitsune.app.ui.playlist.PlaylistViewModel
import com.kitsune.app.ui.reader.ReaderScreen
import com.kitsune.app.ui.reader.ReaderViewModel
import com.kitsune.app.ui.settings.OtherScreen
import com.kitsune.app.ui.settings.SettingsViewModel
import com.kitsune.app.ui.video.*
import com.kitsune.app.ui.splash.SplashScreen
import com.kitsune.app.ui.splash.SplashViewModel

/**
 * Data class for Bottom Navigation Items.
 */
data class BottomNavItem(val label: String, val route: String, val icon: ImageVector)

/**
 * REVISION 7.9.3: Unified Navigation Graph.
 * Segregates navigation logic from MainActivity into a modular structure.
 * REVISION 9.2.1: Added MetadataManager.
 */
@Composable
fun KitsuneNavGraph(
    navController: NavHostController,
    application: Application,
    storageHelper: StorageHelper,
    scannerRepository: ScannerRepository,
    videoRepository: VideoRepository,
    settingsRepository: SettingsRepository,
    readerRepository: ReaderRepository,
    progressRepository: ReadingProgressRepository,
    bookmarkRepository: BookmarkRepository,
    playlistRepository: PlaylistRepository,
    collectionRepository: CollectionRepository,
    metadataManager: MetadataManager,
    libraryViewModel: LibraryViewModel,
    splashViewModel: SplashViewModel
) {
    NavHost(navController = navController, startDestination = Screen.Splash.route) {
        composable(Screen.Splash.route) {
            SplashScreen(
                viewModel = splashViewModel,
                storageHelper = storageHelper,
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Screen.Main.route) {
            MainContainer(
                application = application,
                libraryViewModel = libraryViewModel,
                scannerRepository = scannerRepository,
                videoRepository = videoRepository,
                settingsRepository = settingsRepository,
                readerRepository = readerRepository,
                progressRepository = progressRepository,
                bookmarkRepository = bookmarkRepository,
                playlistRepository = playlistRepository,
                collectionRepository = collectionRepository,
                metadataManager = metadataManager,
                storageHelper = storageHelper
            )
        }
    }
}

@Composable
fun MainContainer(
    application: Application,
    libraryViewModel: LibraryViewModel,
    scannerRepository: ScannerRepository,
    videoRepository: VideoRepository,
    settingsRepository: SettingsRepository,
    readerRepository: ReaderRepository,
    progressRepository: ReadingProgressRepository,
    bookmarkRepository: BookmarkRepository,
    playlistRepository: PlaylistRepository,
    collectionRepository: CollectionRepository,
    metadataManager: MetadataManager,
    storageHelper: StorageHelper
) {
    val innerNavController = androidx.navigation.compose.rememberNavController()
    val items = listOf(
        BottomNavItem("Bookmark", Screen.Bookmark.route, Icons.Default.Star),
        BottomNavItem("Playlist", Screen.Playlist.route, Icons.AutoMirrored.Filled.List),
        BottomNavItem("Local", Screen.Local.route, Icons.Default.Home),
        BottomNavItem("Settings", Screen.Other.route, Icons.Default.Settings),
    )

    Scaffold(
        bottomBar = {
            val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            
            val rootRoutes = items.map { it.route }
            val showBottomBar = currentDestination?.route in rootRoutes

            if (showBottomBar) {
                NavigationBar {
                    items.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = {
                                innerNavController.navigate(item.route) {
                                    popUpTo(innerNavController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = innerNavController,
            startDestination = Screen.Local.route,
            modifier = Modifier.padding(
                bottom = innerPadding.calculateBottomPadding()
            )
        ) {
            // --- Collection Routes ---
            composable(Screen.Bookmark.route) { 
                val bookmarkViewModel: BookmarkViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return BookmarkViewModel(bookmarkRepository) as T
                        }
                    }
                )
                BookmarkScreen(
                    viewModel = bookmarkViewModel,
                    onCategoryClick = { item ->
                        innerNavController.navigate(Screen.BookmarkDetail.createRoute(item.bookmark.id))
                    }
                ) 
            }
            
            composable(
                route = Screen.BookmarkDetail.route,
                arguments = listOf(navArgument("bookmarkId") { type = NavType.LongType })
            ) { backStackEntry ->
                val bookmarkId = backStackEntry.arguments?.getLong("bookmarkId") ?: -1L
                val detailViewModel: BookmarkDetailViewModel = viewModel(
                    key = "bookmark_$bookmarkId",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return BookmarkDetailViewModel(
                                bookmarkId = bookmarkId,
                                bookmarkRepository = bookmarkRepository,
                                scannerRepository = scannerRepository,
                                settingsRepository = settingsRepository
                            ) as T
                        }
                    }
                )
                BookmarkDetailScreen(
                    viewModel = detailViewModel,
                    onComicClick = { comic ->
                        innerNavController.navigate(Screen.ComicDetail.createRoute(comic.relativePath))
                    },
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            composable(Screen.Playlist.route) { 
                val playlistViewModel: PlaylistViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return PlaylistViewModel(playlistRepository) as T
                        }
                    }
                )
                PlaylistScreen(
                    viewModel = playlistViewModel,
                    onCategoryClick = { item ->
                        innerNavController.navigate(Screen.PlaylistDetail.createRoute(item.playlist.id))
                    }
                ) 
            }

            composable(
                route = Screen.PlaylistDetail.route,
                arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: -1L
                val detailViewModel: PlaylistDetailViewModel = viewModel(
                    key = "playlist_$playlistId",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return PlaylistDetailViewModel(
                                playlistId = playlistId,
                                playlistRepository = playlistRepository,
                                scannerRepository = scannerRepository,
                                videoRepository = videoRepository,
                                settingsRepository = settingsRepository,
                                bookmarkRepository = bookmarkRepository
                            ) as T
                        }
                    }
                )
                PlaylistDetailScreen(
                    viewModel = detailViewModel,
                    onItemClick = { item ->
                        if (item.mediaType == com.kitsune.app.domain.model.MediaType.COMIC) {
                            innerNavController.navigate(Screen.ComicDetail.createRoute(item.id))
                        } else {
                            innerNavController.navigate(Screen.VideoDetail.createRoute(item.id))
                        }
                    },
                    onBackClick = { innerNavController.popBackStack() }
                )
            }
            
            // --- Home & Settings ---
            composable(Screen.Local.route) { 
                val localViewModel = viewModel<LocalViewModel>(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return LocalViewModel(progressRepository, videoRepository) as T
                        }
                    }
                )
                LocalScreen(
                    viewModel = localViewModel,
                    onContinueReading = { lastRead ->
                        innerNavController.navigate(
                            Screen.Reader.createRoute(
                                lastRead.progress.comicRelativePath,
                                lastRead.progress.chapterRelativePath
                            )
                        )
                    },
                    onContinueWatching = { lastWatched ->
                        innerNavController.navigate(
                            Screen.VideoPlayer.createRoute(
                                lastWatched.video.relativePath,
                                lastWatched.episodeRelativePath
                            )
                        )
                    },
                    onComicsClick = { innerNavController.navigate(Screen.ComicLibrary.route) },
                    onVideosClick = { innerNavController.navigate(Screen.VideoLibrary.route) }
                ) 
            }

            composable(Screen.Other.route) { 
                val settingsViewModel = viewModel<SettingsViewModel>(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return SettingsViewModel(
                                settingsRepository, 
                                scannerRepository, 
                                bookmarkRepository, 
                                playlistRepository,
                                progressRepository,
                                videoRepository
                            ) as T
                        }
                    }
                )
                OtherScreen(viewModel = settingsViewModel, storageHelper = storageHelper)
            }
            
            // --- Libraries ---
            composable(Screen.ComicLibrary.route) {
                ComicLibraryScreen(
                    viewModel = libraryViewModel,
                    onComicClick = { comic ->
                        innerNavController.navigate(Screen.ComicDetail.createRoute(comic.relativePath))
                    },
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            composable(Screen.VideoLibrary.route) {
                val videoLibraryViewModel: VideoLibraryViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return VideoLibraryViewModel(
                                videoRepository = videoRepository, 
                                settingsRepository = settingsRepository,
                                collectionRepository = collectionRepository,
                                playlistRepository = playlistRepository
                            ) as T
                        }
                    }
                )
                VideoLibraryScreen(
                    viewModel = videoLibraryViewModel,
                    onVideoClick = { video ->
                        innerNavController.navigate(Screen.VideoDetail.createRoute(video.relativePath))
                    },
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            // --- Detail & Player ---
            composable(
                route = Screen.VideoDetail.route,
                arguments = listOf(navArgument("videoRelativePath") { type = NavType.StringType })
            ) { backStackEntry ->
                val videoRelativePath = backStackEntry.arguments?.getString("videoRelativePath") ?: ""
                val videoDetailViewModel: VideoDetailViewModel = viewModel(
                    key = videoRelativePath,
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return VideoDetailViewModel(
                                videoRelativePath = videoRelativePath,
                                videoRepository = videoRepository,
                                settingsRepository = settingsRepository,
                                collectionRepository = collectionRepository,
                                metadataManager = metadataManager
                            ) as T
                        }
                    }
                )
                VideoDetailScreen(
                    viewModel = videoDetailViewModel,
                    onEpisodeClick = { episode ->
                        innerNavController.navigate(Screen.VideoPlayer.createRoute(videoRelativePath, episode.relativePath))
                    },
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            composable(
                route = Screen.VideoPlayer.route,
                arguments = listOf(
                    navArgument("videoRelativePath") { type = NavType.StringType },
                    navArgument("episodeRelativePath") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val videoRelativePath = backStackEntry.arguments?.getString("videoRelativePath") ?: ""
                val episodeRelativePath = backStackEntry.arguments?.getString("episodeRelativePath") ?: ""
                
                val videoPlayerViewModel: VideoPlayerViewModel = viewModel(
                    key = episodeRelativePath,
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return VideoPlayerViewModel(
                                application = application,
                                videoRelativePath = videoRelativePath,
                                episodeRelativePath = episodeRelativePath,
                                videoRepository = videoRepository,
                                settingsRepository = settingsRepository
                            ) as T
                        }
                    }
                )
                
                VideoPlayerScreen(
                    viewModel = videoPlayerViewModel,
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            composable(
                route = Screen.ComicDetail.route,
                arguments = listOf(navArgument("comicRelativePath") { type = NavType.StringType })
            ) { backStackEntry ->
                val comicRelativePath = backStackEntry.arguments?.getString("comicRelativePath") ?: ""
                val comicDetailViewModel: ComicDetailViewModel = viewModel(
                    key = comicRelativePath,
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ComicDetailViewModel(
                                comicRelativePath = comicRelativePath,
                                scannerRepository = scannerRepository,
                                settingsRepository = settingsRepository,
                                progressRepository = progressRepository,
                                bookmarkRepository = bookmarkRepository,
                                metadataManager = metadataManager
                            ) as T
                        }
                    }
                )
                ComicDetailScreen(
                    viewModel = comicDetailViewModel,
                    onChapterClick = { chapter ->
                        innerNavController.navigate(Screen.Reader.createRoute(comicRelativePath, chapter.relativePath))
                    },
                    onContinueClick = { progress ->
                        innerNavController.navigate(Screen.Reader.createRoute(comicRelativePath, progress.chapterRelativePath))
                    },
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            composable(
                route = Screen.Reader.route,
                arguments = listOf(
                    navArgument("comicRelativePath") { type = NavType.StringType },
                    navArgument("chapterRelativePath") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val comicRelativePath = backStackEntry.arguments?.getString("comicRelativePath") ?: ""
                val chapterRelativePath = backStackEntry.arguments?.getString("chapterRelativePath") ?: ""
                
                val readerViewModel: ReaderViewModel = viewModel(
                    key = chapterRelativePath,
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ReaderViewModel(
                                comicRelativePath = comicRelativePath,
                                currentChapterPath = chapterRelativePath,
                                readerRepository = readerRepository,
                                settingsRepository = settingsRepository,
                                progressRepository = progressRepository,
                                scannerRepository = scannerRepository,
                                storageHelper = storageHelper
                            ) as T
                        }
                    }
                )
                
                ReaderScreen(
                    viewModel = readerViewModel,
                    onBackClick = { innerNavController.popBackStack() }
                )
            }
        }
    }
}
