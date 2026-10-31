package com.example.kitsune.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.kitsune.core.AppContainer
import com.example.kitsune.ui.navigation.KitsuneDestinations
import com.example.kitsune.ui.navigation.TopLevelDestination
import com.example.kitsune.ui.screens.PlaceholderScreen
import com.example.kitsune.ui.screens.SettingsScreen
import com.example.kitsune.ui.screens.library.LibraryScreen
import com.example.kitsune.ui.screens.library.LibraryViewModel

@Composable
fun KitsuneApp(
    appContainer: AppContainer,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    val isTopLevelDestination = TopLevelDestination.entries.any { it.route == currentRoute }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (isTopLevelDestination) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != destination.route) {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.title
                                )
                            },
                            label = { Text(destination.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = KitsuneDestinations.LIBRARY_ROUTE,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(KitsuneDestinations.LIBRARY_ROUTE) {
                val libraryViewModel: LibraryViewModel = viewModel(
                    factory = LibraryViewModel.Factory(
                        libraryRepository = appContainer.libraryRepository,
                        rootFolderPreferences = appContainer.rootFolderPreferences,
                        settingsRepository = appContainer.settingsRepository,
                        comicsDirectoryDetector = appContainer.comicsDirectoryDetector
                    )
                )
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onComicClick = { comicId ->
                        navController.navigate(KitsuneDestinations.comicDetailRoute(comicId))
                    },
                    onNavigateToSettings = {
                        navController.navigate(KitsuneDestinations.SETTINGS_ROUTE)
                    }
                )
            }
            composable(KitsuneDestinations.BOOKMARKS_ROUTE) {
                val bookmarkViewModel: com.example.kitsune.ui.screens.bookmark.BookmarkViewModel = viewModel(
                    factory = com.example.kitsune.ui.screens.bookmark.BookmarkViewModel.Factory(
                        libraryRepository = appContainer.libraryRepository,
                        rootFolderPreferences = appContainer.rootFolderPreferences,
                        settingsRepository = appContainer.settingsRepository
                    )
                )
                com.example.kitsune.ui.screens.bookmark.BookmarkScreen(
                    viewModel = bookmarkViewModel,
                    onComicClick = { comicId ->
                        navController.navigate(KitsuneDestinations.comicDetailRoute(comicId))
                    },
                    onNavigateToSettings = {
                        navController.navigate(KitsuneDestinations.SETTINGS_ROUTE)
                    }
                )
            }
            composable(KitsuneDestinations.HISTORY_ROUTE) {
                val historyViewModel: com.example.kitsune.ui.screens.history.HistoryViewModel = viewModel(
                    factory = com.example.kitsune.ui.screens.history.HistoryViewModel.Factory(
                        libraryRepository = appContainer.libraryRepository,
                        rootFolderPreferences = appContainer.rootFolderPreferences
                    )
                )
                com.example.kitsune.ui.screens.history.HistoryScreen(
                    viewModel = historyViewModel,
                    onComicClick = { comicId ->
                        navController.navigate(KitsuneDestinations.comicDetailRoute(comicId))
                    },
                    onContinueReadingClick = { chapterId ->
                        navController.navigate(KitsuneDestinations.readerRoute(chapterId))
                    },
                    onNavigateToSettings = {
                        navController.navigate(KitsuneDestinations.SETTINGS_ROUTE)
                    }
                )
            }
            composable(KitsuneDestinations.SETTINGS_ROUTE) {
                SettingsScreen(rootFolderPreferences = appContainer.rootFolderPreferences)
            }
            composable(
                route = KitsuneDestinations.COMIC_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument("comicId") { type = NavType.LongType }
                )
            ) { backStackEntry ->
                val comicId = backStackEntry.arguments?.getLong("comicId") ?: 0L
                val detailViewModel: com.example.kitsune.ui.screens.detail.ComicDetailViewModel = viewModel(
                    factory = com.example.kitsune.ui.screens.detail.ComicDetailViewModel.Factory(
                        comicId = comicId,
                        libraryRepository = appContainer.libraryRepository
                    )
                )
                com.example.kitsune.ui.screens.detail.ComicDetailScreen(
                    viewModel = detailViewModel,
                    onBackClick = { navController.popBackStack() },
                    onChapterClick = { chapterId ->
                        navController.navigate(KitsuneDestinations.readerRoute(chapterId))
                    }
                )
            }
            composable(
                route = KitsuneDestinations.READER_ROUTE,
                arguments = listOf(
                    navArgument("chapterId") { type = NavType.LongType }
                )
            ) { backStackEntry ->
                val chapterId = backStackEntry.arguments?.getLong("chapterId") ?: 0L
                val readerViewModel: com.example.kitsune.ui.screens.reader.ReaderViewModel = viewModel(
                    factory = com.example.kitsune.ui.screens.reader.ReaderViewModel.Factory(
                        chapterId = chapterId,
                        libraryRepository = appContainer.libraryRepository,
                        settingsRepository = appContainer.settingsRepository,
                        cbzPageIndexer = appContainer.cbzPageIndexer,
                        cbzPageDecoderFactory = appContainer.cbzPageDecoderFactory
                    )
                )
                com.example.kitsune.ui.screens.reader.ReaderScreen(
                    viewModel = readerViewModel,
                    onBackClick = { navController.popBackStack() },
                    onSettingsClick = {
                        navController.navigate(KitsuneDestinations.SETTINGS_ROUTE)
                    }
                )
            }
        }
    }
}

