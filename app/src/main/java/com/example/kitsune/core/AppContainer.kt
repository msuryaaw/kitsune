package com.example.kitsune.core

import android.content.Context
import androidx.room.Room

import com.example.kitsune.data.DefaultLibraryRepository
import com.example.kitsune.data.DefaultSettingsRepository
import com.example.kitsune.data.KitsuneDatabase
import com.example.kitsune.data.LibraryRepository
import com.example.kitsune.data.SettingsRepository
import com.example.kitsune.scanner.CbzCataloger
import com.example.kitsune.scanner.ChapterScanner
import com.example.kitsune.scanner.ComicFolderScanner
import com.example.kitsune.scanner.ComicsDirectoryDetector
import com.example.kitsune.scanner.DefaultCbzCataloger
import com.example.kitsune.scanner.DefaultChapterScanner
import com.example.kitsune.scanner.DefaultComicFolderScanner
import com.example.kitsune.scanner.DefaultComicsDirectoryDetector
import com.example.kitsune.scanner.DefaultLibraryScanner
import com.example.kitsune.scanner.IncrementalCbzCataloger
import com.example.kitsune.scanner.LibraryScanner
import com.example.kitsune.storage.SafStorageGateway
import com.example.kitsune.storage.StorageGateway

/**
 * Dependency container contract for Kitsune application.
 * Provides application-level dependencies via constructor injection / manual DI.
 */
interface AppContainer {
    val rootFolderPreferences: RootFolderPreferences
    val storageGateway: StorageGateway
    val comicsDirectoryDetector: ComicsDirectoryDetector
    val comicFolderScanner: ComicFolderScanner
    val chapterScanner: ChapterScanner
    val cbzCataloger: CbzCataloger
    val incrementalCbzCataloger: IncrementalCbzCataloger
    val libraryScanner: LibraryScanner
    val database: KitsuneDatabase
    val libraryRepository: LibraryRepository
    val settingsRepository: SettingsRepository
}

/**
 * Default implementation of [AppContainer] providing real application dependencies.
 */
class DefaultAppContainer(
    private val appContext: Context
) : AppContainer {
    override val rootFolderPreferences: RootFolderPreferences by lazy {
        SharedPrefsRootFolderPreferences(appContext)
    }

    override val storageGateway: StorageGateway by lazy {
        SafStorageGateway(appContext, rootFolderPreferences)
    }

    override val comicsDirectoryDetector: ComicsDirectoryDetector by lazy {
        DefaultComicsDirectoryDetector(storageGateway)
    }

    override val comicFolderScanner: ComicFolderScanner by lazy {
        DefaultComicFolderScanner(comicsDirectoryDetector, storageGateway)
    }

    override val chapterScanner: ChapterScanner by lazy {
        DefaultChapterScanner(storageGateway)
    }

    override val cbzCataloger: CbzCataloger by lazy {
        DefaultCbzCataloger(storageGateway)
    }

    override val incrementalCbzCataloger: IncrementalCbzCataloger by lazy {
        IncrementalCbzCataloger(cbzCataloger)
    }

    override val libraryScanner: LibraryScanner by lazy {
        DefaultLibraryScanner(comicFolderScanner, chapterScanner, incrementalCbzCataloger)
    }

    override val database: KitsuneDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            KitsuneDatabase::class.java,
            "kitsune_db"
        ).build()
    }

    override val libraryRepository: LibraryRepository by lazy {
        DefaultLibraryRepository(
            libraryScanner = libraryScanner,
            incrementalCataloger = incrementalCbzCataloger,
            comicDao = database.comicDao(),
            chapterDao = database.chapterDao(),
            readingProgressDao = database.readingProgressDao()
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        DefaultSettingsRepository(
            settingsDao = database.settingsDao()
        )
    }
}
