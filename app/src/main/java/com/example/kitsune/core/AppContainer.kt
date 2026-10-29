package com.example.kitsune.core

import android.content.Context

import com.example.kitsune.scanner.CbzCataloger
import com.example.kitsune.scanner.ChapterScanner
import com.example.kitsune.scanner.ComicFolderScanner
import com.example.kitsune.scanner.ComicsDirectoryDetector
import com.example.kitsune.scanner.DefaultCbzCataloger
import com.example.kitsune.scanner.DefaultChapterScanner
import com.example.kitsune.scanner.DefaultComicFolderScanner
import com.example.kitsune.scanner.DefaultComicsDirectoryDetector
import com.example.kitsune.scanner.DefaultLibraryScanner
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
    val libraryScanner: LibraryScanner
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

    override val libraryScanner: LibraryScanner by lazy {
        DefaultLibraryScanner(comicFolderScanner, chapterScanner, cbzCataloger)
    }
}
