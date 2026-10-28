package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageEntry

/**
 * Representation of a discovered comic folder directly inside `Comics`.
 *
 * @param folderName Exact name of the comic directory on filesystem.
 * @param relativePath Relative path from root (e.g. "Comics/One Piece").
 * @param defaultTitle Default title derived from folder name without modifying the original name.
 * @param entry The underlying [StorageEntry] record.
 */
data class DiscoveredComicFolder(
    val folderName: String,
    val relativePath: String,
    val defaultTitle: String,
    val entry: StorageEntry
)

/**
 * Result state when discovering comic folders inside `Comics`.
 */
sealed class ComicFolderDiscoveryResult {
    /**
     * Root directory is not configured.
     */
    object RootNotConfigured : ComicFolderDiscoveryResult()

    /**
     * Storage access permission is missing or revoked.
     */
    data class PermissionDenied(val message: String) : ComicFolderDiscoveryResult()

    /**
     * The `Comics` directory was not found under the root.
     */
    object ComicsDirectoryNotFound : ComicFolderDiscoveryResult()

    /**
     * Successfully inspected `Comics` directory and identified candidate comic folders.
     * Note: list may be empty if `Comics` contains no direct subdirectories.
     */
    data class Success(val comicFolders: List<DiscoveredComicFolder>) : ComicFolderDiscoveryResult()

    /**
     * Storage I/O failure or unexpected error.
     */
    data class Error(val exception: Throwable) : ComicFolderDiscoveryResult()
}

/**
 * Contract for discovering direct comic directories located inside `Comics`.
 */
interface ComicFolderScanner {

    /**
     * Discovers direct subdirectories inside `Comics` as comic candidates.
     * Does not recurse into subdirectories and does not inspect CBZ files (per Task 2.2 scope).
     */
    fun discoverComicFolders(): ComicFolderDiscoveryResult
}

