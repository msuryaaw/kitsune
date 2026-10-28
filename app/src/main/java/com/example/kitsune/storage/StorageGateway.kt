package com.example.kitsune.storage

import android.os.ParcelFileDescriptor
import java.io.InputStream

/**
 * Metadata representation of a file or directory within the storage gateway.
 */
data class StorageEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val lastModifiedMillis: Long? = null,
    val mimeType: String? = null
)

/**
 * Result wrapper for storage operations distinguishing success, missing permission, and I/O failures.
 */
sealed class StorageResult<out T> {
    data class Success<out T>(val data: T) : StorageResult<T>()
    object RootNotConfigured : StorageResult<Nothing>()
    data class PermissionDenied(val message: String) : StorageResult<Nothing>()
    data class NotFound(val relativePath: String) : StorageResult<Nothing>()
    data class Error(val exception: Throwable) : StorageResult<Nothing>()
}

/**
 * Central storage access abstraction.
 * Encapsulates SAF/ContentResolver access so domain, scanner, and reader layers
 * never deal with DocumentFile, raw URIs, or assume local filesystem absolute paths.
 */
interface StorageGateway {

    /**
     * Checks whether root is configured and accessible.
     */
    fun getRootState(): StorageResult<String>

    /**
     * Lists child entries located under [parentRelativePath].
     * Passing empty string or null lists children of the root directory.
     * Normalized relative paths are used (forward slashes, no leading/trailing slashes).
     */
    fun listChildren(parentRelativePath: String = ""): StorageResult<List<StorageEntry>>

    /**
     * Finds metadata for a specific entry at [relativePath].
     */
    fun findEntry(relativePath: String): StorageResult<StorageEntry>

    /**
     * Opens an [InputStream] for reading a file at [relativePath].
     * Caller is responsible for closing the returned stream.
     */
    fun openInputStream(relativePath: String): StorageResult<InputStream>

    /**
     * Opens a [ParcelFileDescriptor] for seekable reading of a file at [relativePath].
     * Mode must be "r". Caller is responsible for closing the descriptor.
     */
    fun openFileDescriptor(relativePath: String, mode: String = "r"): StorageResult<ParcelFileDescriptor>
}

