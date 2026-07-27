package com.kitsune.app.data.metadata

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kitsune.app.core.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Responsible for reading and parsing metadata.json from SAF.
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
     * @return MediaMetadata object. Returns default if file is missing or corrupted.
     */
    suspend fun readMetadata(rootUri: Uri, titleRelativePath: String): MediaMetadata = withContext(Dispatchers.IO) {
        try {
            val titleFolder = storageHelper.findFileByRelativePath(rootUri, titleRelativePath)
                ?: return@withContext MediaMetadata()

            val metadataFile = titleFolder.findFile("metadata.json")
                ?: return@withContext MediaMetadata()

            context.contentResolver.openInputStream(metadataFile.uri)?.use { inputStream ->
                val content = inputStream.bufferedReader().use { it.readText() }
                return@withContext json.decodeFromString<MediaMetadata>(content)
            } ?: MediaMetadata()
        } catch (e: Exception) {
            // Log error if needed, but return default to prevent crash as per rules
            e.printStackTrace()
            MediaMetadata()
        }
    }
}
