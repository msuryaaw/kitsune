package com.kitsune.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.data.repository.*
import com.kitsune.app.database.AppDatabase
import com.kitsune.app.database.entity.ReadingProgressEntity
import com.kitsune.app.navigation.Screen
import com.kitsune.app.reader.CbzParser
import com.kitsune.app.scanner.ComicScanner
import com.kitsune.app.scanner.VideoScanner
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
import com.kitsune.app.ui.splash.SplashScreen
import com.kitsune.app.ui.splash.SplashViewModel

data class BottomNavItem(val label: String, val route: String, val icon: ImageVector)

@Composable
fun KitsuneTheme(
    isOled: Boolean = false,
    content: @Composable () -> Unit
) {
    val backgroundColor = if (isOled) Color.Black else Color(0xFF121212)
    val surfaceColor = if (isOled) Color.Black else Color(0xFF1E1E1E)
    
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFF9800), // Orange Accent
            background = backgroundColor,
            surface = surfaceColor,
            onBackground = Color.White,
            onSurface = Color.White,
            primaryContainer = Color(0xFFFF9800).copy(alpha = 0.2f),
            onPrimaryContainer = Color(0xFFFF9800)
        ),
        content = content
    )
}

class MainActivity : ComponentActivity() {

    private lateinit var readerRepository: ReaderRepository
    private lateinit var bookmarkRepository: BookmarkRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var scannerRepository: ScannerRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var progressRepository: ReadingProgressRepository
    private lateinit var storageHelper: StorageHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        readerRepository = (application as KitsuneApplication).readerRepository
        
        val database = AppDatabase.getDatabase(this)
        settingsRepository = SettingsRepository(database.settingsDao())
        storageHelper = StorageHelper(this)
        
        val comicScanner = ComicScanner(this)
        val videoScanner = VideoScanner(this)
        
        scannerRepository = ScannerRepository(
            comicScanner = comicScanner,
            comicDao = database.comicDao(),
            videoScanner = videoScanner,
            videoDao = database.videoDao()
        )

        progressRepository = ReadingProgressRepository(database.readingProgressDao(), database.comicDao())
        bookmarkRepository = BookmarkRepository(database.bookmarkDao())
        playlistRepository = PlaylistRepository(database.playlistDao())
        
        val splashViewModelInstance = SplashViewModel(settingsRepository, storageHelper)
        
        val libraryViewModelInstance = LibraryViewModel(
            scannerRepository, 
            settingsRepository, 
            bookmarkRepository,
            playlistRepository
        )

        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            val isOled = settings?.oledBlack ?: false
            
            KitsuneTheme(isOled = isOled) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    
                    NavHost(navController = navController, startDestination = Screen.Splash.route) {
                        composable(Screen.Splash.route) {
                            SplashScreen(
                                viewModel = splashViewModelInstance,
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
                                libraryViewModel = libraryViewModelInstance,
                                scannerRepository = scannerRepository,
                                settingsRepository = settingsRepository,
                                readerRepository = readerRepository,
                                progressRepository = progressRepository,
                                bookmarkRepository = bookmarkRepository,
                                playlistRepository = playlistRepository,
                                storageHelper = storageHelper
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainContainer(
    libraryViewModel: LibraryViewModel,
    scannerRepository: ScannerRepository,
    settingsRepository: SettingsRepository,
    readerRepository: ReaderRepository,
    progressRepository: ReadingProgressRepository,
    bookmarkRepository: BookmarkRepository,
    playlistRepository: PlaylistRepository,
    storageHelper: StorageHelper
) {
    val navController = rememberNavController()
    val items = listOf(
        BottomNavItem("Bookmark", Screen.Bookmark.route, Icons.Default.Star),
        BottomNavItem("Playlist", Screen.Playlist.route, Icons.AutoMirrored.Filled.List),
        BottomNavItem("Local", Screen.Local.route, Icons.Default.Home),
        BottomNavItem("Settings", Screen.Other.route, Icons.Default.Settings),
    )

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
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
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
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
            navController = navController,
            startDestination = Screen.Local.route,
            modifier = Modifier.padding(
                bottom = innerPadding.calculateBottomPadding()
            )
        ) {
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
                        navController.navigate(Screen.BookmarkDetail.createRoute(item.bookmark.id))
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
                                settingsRepository = settingsRepository,
                                playlistRepository = playlistRepository
                            ) as T
                        }
                    }
                )
                BookmarkDetailScreen(
                    viewModel = detailViewModel,
                    onComicClick = { comic ->
                        navController.navigate(Screen.ComicDetail.createRoute(comic.relativePath))
                    },
                    onBackClick = { navController.popBackStack() }
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
                        navController.navigate(Screen.PlaylistDetail.createRoute(item.playlist.id))
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
                                settingsRepository = settingsRepository,
                                bookmarkRepository = bookmarkRepository
                            ) as T
                        }
                    }
                )
                PlaylistDetailScreen(
                    viewModel = detailViewModel,
                    onComicClick = { comic ->
                        navController.navigate(Screen.ComicDetail.createRoute(comic.relativePath))
                    },
                    onBackClick = { navController.popBackStack() }
                )
            }
            
            composable(Screen.Local.route) { 
                val localViewModel = viewModel<LocalViewModel>(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return LocalViewModel(progressRepository) as T
                        }
                    }
                )
                LocalScreen(
                    viewModel = localViewModel,
                    onContinueReading = { lastRead ->
                        navController.navigate(
                            Screen.Reader.createRoute(
                                lastRead.progress.comicRelativePath,
                                lastRead.progress.chapterRelativePath
                            )
                        )
                    },
                    onComicsClick = { navController.navigate(Screen.ComicLibrary.route) },
                    onVideosClick = { /* Future */ }
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
                                progressRepository
                            ) as T
                        }
                    }
                )
                OtherScreen(viewModel = settingsViewModel, storageHelper = storageHelper)
            }
            
            composable(Screen.ComicLibrary.route) {
                ComicLibraryScreen(
                    viewModel = libraryViewModel,
                    onComicClick = { comic ->
                        navController.navigate(Screen.ComicDetail.createRoute(comic.relativePath))
                    },
                    onBackClick = { navController.popBackStack() }
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
                                playlistRepository = playlistRepository
                            ) as T
                        }
                    }
                )
                ComicDetailScreen(
                    viewModel = comicDetailViewModel,
                    onChapterClick = { chapter ->
                        navController.navigate(Screen.Reader.createRoute(comicRelativePath, chapter.relativePath))
                    },
                    onContinueClick = { progress: ReadingProgressEntity ->
                        navController.navigate(Screen.Reader.createRoute(comicRelativePath, progress.chapterRelativePath))
                    },
                    onBackClick = { navController.popBackStack() }
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
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}
