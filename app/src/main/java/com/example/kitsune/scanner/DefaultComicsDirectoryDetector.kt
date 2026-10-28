package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult

/**
 * Default implementation of [ComicsDirectoryDetector] using [StorageGateway].
 *
 * Checks root accessibility and discovers the `Comics` directory (case-insensitive detection,
 * preferring exact "Comics"), inspects whether it is a directory, and checks whether it is empty.
 * Never creates the directory automatically.
 */
class DefaultComicsDirectoryDetector(
    private val storageGateway: StorageGateway
) : ComicsDirectoryDetector {

    companion object {
        const val COMICS_DIR_NAME = "Comics"
    }

    override fun detectComicsDirectory(): ComicsDirectoryState {
        // 1. Periksa status root
        when (val rootState = storageGateway.getRootState()) {
            is StorageResult.RootNotConfigured -> return ComicsDirectoryState.RootNotConfigured
            is StorageResult.PermissionDenied -> return ComicsDirectoryState.PermissionDenied(rootState.message)
            is StorageResult.Error -> return ComicsDirectoryState.Error(rootState.exception)
            is StorageResult.NotFound -> return ComicsDirectoryState.RootNotConfigured
            is StorageResult.Success -> {
                // Root valid dan siap, lanjutkan mencari direktori Comics
            }
        }

        // 2. List child pada root
        val rootChildren = when (val result = storageGateway.listChildren("")) {
            is StorageResult.Success -> result.data
            is StorageResult.RootNotConfigured -> return ComicsDirectoryState.RootNotConfigured
            is StorageResult.PermissionDenied -> return ComicsDirectoryState.PermissionDenied(result.message)
            is StorageResult.Error -> return ComicsDirectoryState.Error(result.exception)
            is StorageResult.NotFound -> return ComicsDirectoryState.DirectoryNotFound
        }

        // 3. Temukan entry Comics (prioritaskan exact match "Comics", jika tidak ada cari yang case-insensitive)
        val comicsEntry = rootChildren.firstOrNull { it.name == COMICS_DIR_NAME }
            ?: rootChildren.firstOrNull { it.name.equals(COMICS_DIR_NAME, ignoreCase = true) }
            ?: return ComicsDirectoryState.DirectoryNotFound

        // 4. Pastikan entry adalah direktori
        if (!comicsEntry.isDirectory) {
            return ComicsDirectoryState.NotADirectory
        }

        // 5. Periksa isi direktori Comics
        val childrenResult = storageGateway.listChildren(comicsEntry.relativePath)
        return when (childrenResult) {
            is StorageResult.Success -> {
                val children = childrenResult.data
                if (children.isEmpty()) {
                    ComicsDirectoryState.Empty(comicsEntry)
                } else {
                    ComicsDirectoryState.Ready(comicsEntry, children.size)
                }
            }
            is StorageResult.RootNotConfigured -> ComicsDirectoryState.RootNotConfigured
            is StorageResult.PermissionDenied -> ComicsDirectoryState.PermissionDenied(childrenResult.message)
            is StorageResult.Error -> ComicsDirectoryState.Error(childrenResult.exception)
            is StorageResult.NotFound -> ComicsDirectoryState.DirectoryNotFound
        }
    }
}

