package com.kitsune.app.data.metadata

import android.content.Context
import android.net.Uri
import com.kitsune.app.core.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Responsible for reading and parsing metadata.json from SAF.
 * REVISION 9.3.1: Updated to return Result to distinguish between missing and corrupted files.
 */
class MetadataReader(
    private val context: Context,
    private val storageHelper: StorageHelper
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /**
     * Reads metadata from the title folder identified by its relative path.
     * 
     * @param rootUri The root URI of the library.
     * @param titleRelativePath Path to the title folder (e.g., "Comics/One Piece").
     * @return Result containing MediaMetadata. 
     *         Success(default) if missing, Failure if corrupted.
     */
    suspend fun readMetadata(rootUri: Uri, titleRelativePath: String): Result<MediaMetadata> = withContext(Dispatchers.IO) {
        try {
            val titleFolder = storageHelper.findFileByRelativePath(rootUri, titleRelativePath)
                ?: return@withContext Result.success(MediaMetadata())

            val metadataFile = titleFolder.findFile("metadata.json")
                ?: return@withContext Result.success(MediaMetadata()) // Not found is not an error

            context.contentResolver.openInputStream(metadataFile.uri)?.use { inputStream ->
                val content = inputStream.bufferedReader().use { it.readText() }
                val metadata = json.decodeFromString<MediaMetadata>(content)
                Result.success(metadata)
            } ?: Result.success(MediaMetadata())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
