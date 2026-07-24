package com.kitsune.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.data.repository.*
import com.kitsune.app.database.AppDatabase
import com.kitsune.app.navigation.KitsuneNavGraph
import com.kitsune.app.ui.library.LibraryViewModel
import com.kitsune.app.ui.splash.SplashViewModel

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
    private lateinit var collectionRepository: CollectionRepository
    private lateinit var scannerRepository: ScannerRepository
    private lateinit var videoRepository: VideoRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var progressRepository: ReadingProgressRepository
    private lateinit var storageHelper: StorageHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        // --- Dependency Initialization ---
        val kitsuneApp = (application as KitsuneApplication)
        readerRepository = kitsuneApp.readerRepository
        
        val database = AppDatabase.getDatabase(this)
        settingsRepository = SettingsRepository(database.settingsDao())
        storageHelper = StorageHelper(this)
        
        val comicScanner = com.kitsune.app.scanner.ComicScanner(this)
        val videoScanner = com.kitsune.app.scanner.VideoScanner(this)
        
        scannerRepository = ScannerRepository(
            comicScanner = comicScanner,
            comicDao = database.comicDao(),
            videoScanner = videoScanner,
            videoDao = database.videoDao()
        )
        
        bookmarkRepository = BookmarkRepository(database.bookmarkDao())
        playlistRepository = PlaylistRepository(database.playlistDao())
        collectionRepository = CollectionRepository(bookmarkRepository, playlistRepository)
        videoRepository = VideoRepository(database.videoDao(), scannerRepository, storageHelper)
        progressRepository = ReadingProgressRepository(database.readingProgressDao(), database.comicDao())
        
        // --- Global ViewModels ---
        val splashViewModelInstance = SplashViewModel(settingsRepository, storageHelper)
        val libraryViewModelInstance = LibraryViewModel(
            scannerRepository, 
            settingsRepository, 
            bookmarkRepository
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
                    
                    // REVISION 7.9.3: Modularized Navigation Graph
                    KitsuneNavGraph(
                        navController = navController,
                        application = application,
                        storageHelper = storageHelper,
                        scannerRepository = scannerRepository,
                        videoRepository = videoRepository,
                        settingsRepository = settingsRepository,
                        readerRepository = readerRepository,
                        progressRepository = progressRepository,
                        bookmarkRepository = bookmarkRepository,
                        playlistRepository = playlistRepository,
                        collectionRepository = collectionRepository,
                        libraryViewModel = libraryViewModelInstance,
                        splashViewModel = splashViewModelInstance
                    )
                }
            }
        }
    }
}
