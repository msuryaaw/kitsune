package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageEntry

/**
 * Result state representing the detection of the root `Comics` directory.
 * Used by UI, scanner, and domain layers to represent filesystem status.
 */
sealed class ComicsDirectoryState {
    /**
     * Root directory has not been configured in preferences.
     */
    object RootNotConfigured : ComicsDirectoryState()

    /**
     * Storage access permission has been revoked or is not available.
     */
    data class PermissionDenied(val message: String) : ComicsDirectoryState()

    /**
     * Root exists and is accessible, but `Comics` directory is not found.
     * Note: In accordance with Filesystem Spec, the application must not automatically create it.
     */
    object DirectoryNotFound : ComicsDirectoryState()

    /**
     * An entry named `Comics` exists in root, but it is a file instead of a directory.
     */
    object NotADirectory : ComicsDirectoryState()

    /**
     * The `Comics` directory exists but contains no child files or directories.
     */
    data class Empty(val entry: StorageEntry) : ComicsDirectoryState()

    /**
     * The `Comics` directory exists and contains child entries.
     */
    data class Ready(val entry: StorageEntry, val childCount: Int) : ComicsDirectoryState()

    /**
     * An unexpected error or I/O failure occurred while querying the filesystem.
     */
    data class Error(val exception: Throwable) : ComicsDirectoryState()
}

/**
 * Contract for detecting and inspecting the root `Comics` directory.
 */
interface ComicsDirectoryDetector {

    /**
     * Inspects the storage gateway root and detects the state of the `Comics` directory.
     */
    fun detectComicsDirectory(): ComicsDirectoryState
}

