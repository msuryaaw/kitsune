package com.kitsune.app.data.metadata

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kitsune.app.core.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bertanggung jawab untuk menulis MediaMetadata ke file metadata.json.
 * Menggunakan strategi penulisan atomik untuk mencegah korupsi data.
 */
class MetadataWriter(
    private val context: Context,
    private val storageHelper: StorageHelper
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Menulis metadata ke dalam folder judul media.
     * Menggunakan strategi file sementara untuk meniru penulisan atomik dan mencegah korupsi file.
     * 
     * @param rootUri URI root dari library.
     * @param titleRelativePath Jalur relatif ke folder judul.
     * @param metadata Objek metadata yang akan disimpan.
     * @return Result yang mengindikasikan keberhasilan atau kegagalan.
     */
    suspend fun writeMetadata(
        rootUri: Uri,
        titleRelativePath: String,
        metadata: MediaMetadata
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val titleFolder = storageHelper.findFileByRelativePath(rootUri, titleRelativePath)
                ?: return@withContext Result.failure(Exception("Title folder not found: $titleRelativePath"))

            val content = json.encodeToString(metadata)
            
            // 1. Create temporary file
            val tempFileName = "metadata.json.tmp"
            // Delete existing temp if it exists from a failed previous attempt
            titleFolder.findFile(tempFileName)?.delete()
            
            val tempFile = titleFolder.createFile("application/json", tempFileName)
                ?: return@withContext Result.failure(Exception("Failed to create temporary metadata file"))

            // 2. Write content to temporary file
            context.contentResolver.openOutputStream(tempFile.uri)?.use { outputStream ->
                outputStream.bufferedWriter().use { it.write(content) }
            } ?: return@withContext Result.failure(Exception("Failed to open output stream for temp file"))

            // 3. Replace old file with new one
            val finalFile = titleFolder.findFile("metadata.json")
            
            // Note: SAF renameTo doesn't always overwrite. We delete the old one first.
            finalFile?.delete()
            
            if (tempFile.renameTo("metadata.json")) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to rename temporary file to metadata.json"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
