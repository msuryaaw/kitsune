package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult

/**
 * Default implementation of [ComicFolderScanner].
 *
 * Uses [ComicsDirectoryDetector] to find the valid `Comics` directory, then lists direct children
 * using [StorageGateway.listChildren], filtering only direct directories.
 * Derives default title from folder name without modifying the original filesystem name.
 */
class DefaultComicFolderScanner(
    private val directoryDetector: ComicsDirectoryDetector,
    private val storageGateway: StorageGateway
) : ComicFolderScanner {

    override fun discoverComicFolders(): ComicFolderDiscoveryResult {
        // 1. Deteksi direktori Comics
        val comicsEntry = when (val state = directoryDetector.detectComicsDirectory()) {
            is ComicsDirectoryState.RootNotConfigured -> return ComicFolderDiscoveryResult.RootNotConfigured
            is ComicsDirectoryState.PermissionDenied -> return ComicFolderDiscoveryResult.PermissionDenied(state.message)
            is ComicsDirectoryState.DirectoryNotFound -> return ComicFolderDiscoveryResult.ComicsDirectoryNotFound
            is ComicsDirectoryState.NotADirectory -> return ComicFolderDiscoveryResult.ComicsDirectoryNotFound
            is ComicsDirectoryState.Error -> return ComicFolderDiscoveryResult.Error(state.exception)
            is ComicsDirectoryState.Empty -> return ComicFolderDiscoveryResult.Success(emptyList())
            is ComicsDirectoryState.Ready -> state.entry
        }

        // 2. Ambil entitas anak langsung dari Comics
        return when (val childrenResult = storageGateway.listChildren(comicsEntry.relativePath)) {
            is StorageResult.Success -> {
                // Hanya ambil entitas yang berupa direktori langsung
                val directFolders = childrenResult.data
                    .filter { it.isDirectory }
                    .map { entry ->
                        DiscoveredComicFolder(
                            folderName = entry.name,
                            relativePath = entry.relativePath,
                            defaultTitle = deriveDefaultTitle(entry.name),
                            entry = entry
                        )
                    }
                ComicFolderDiscoveryResult.Success(directFolders)
            }
            is StorageResult.RootNotConfigured -> ComicFolderDiscoveryResult.RootNotConfigured
            is StorageResult.PermissionDenied -> ComicFolderDiscoveryResult.PermissionDenied(childrenResult.message)
            is StorageResult.NotFound -> ComicFolderDiscoveryResult.ComicsDirectoryNotFound
            is StorageResult.Error -> ComicFolderDiscoveryResult.Error(childrenResult.exception)
        }
    }

    /**
     * Derives default title from folder name.
     * Trims leading/trailing whitespace without modifying the original folder name representation.
     */
    private fun deriveDefaultTitle(folderName: String): String {
        val trimmed = folderName.trim()
        return if (trimmed.isNotEmpty()) trimmed else folderName
    }
}

