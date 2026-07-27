package com.kitsune.app.data.metadata

import android.content.Context
import android.net.Uri
import com.kitsune.app.core.StorageHelper

/**
 * Entry point for managing media metadata.
 * Coordinates Reader and Writer to provide a simple API for the rest of the app.
 * 
 * Consistent with Phase 9.3 architecture decisions.
 */
class MetadataManager(
    context: Context,
    storageHelper: StorageHelper
) {
    private val reader = MetadataReader(context, storageHelper)
    private val writer = MetadataWriter(context, storageHelper)

    /**
     * Reads metadata for a specific media title.
     * 
     * REVISION 9.3.2: Implemented Error Recovery. 
     * If file is missing or corrupted, returns default MediaMetadata in-memory
     * without overwriting the corrupted file.
     * 
     * @param rootUri The root URI of the Kitsune library.
     * @param titleRelativePath The relative path of the title folder.
     * @return The metadata object (default one if file doesn't exist or is invalid).
     */
    suspend fun readMetadata(rootUri: Uri, titleRelativePath: String): MediaMetadata {
        return reader.readMetadata(rootUri, titleRelativePath).getOrDefault(MediaMetadata())
    }

    /**
     * Writes metadata for a specific media title.
     * 
     * @param rootUri The root URI of the Kitsune library.
     * @param titleRelativePath The relative path of the title folder.
     * @param metadata The metadata object to persist.
     * @return Result of the operation.
     */
    suspend fun writeMetadata(
        rootUri: Uri,
        titleRelativePath: String,
        metadata: MediaMetadata
    ): Result<Unit> {
        return writer.writeMetadata(rootUri, titleRelativePath, metadata)
    }

    /**
     * Creates a default metadata object.
     */
    fun createDefaultMetadata(): MediaMetadata {
        return MediaMetadata()
    }
}
