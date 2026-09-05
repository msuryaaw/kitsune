package com.kitsune.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile

/**
 * Helper untuk menangani operasi Storage Access Framework (SAF).
 * Menggunakan sistem caching URI untuk meminimalkan traversal rekursif yang lambat pada SAF.
 */
class StorageHelper(private val context: Context) {

    // Cache untuk pemetaan jalur relatif ke URI yang sudah diresolusi.
    // Hal ini mengurangi panggilan Binder secara drastis dengan menghindari traversal findFile() berulang.
    private val uriCache = LruCache<String, Uri>(512)

    fun persistUriPermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    fun validateAndCreateStructure(rootUriString: String): Boolean {
        return try {
            val rootUri = Uri.parse(rootUriString)
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return false

            if (!rootDoc.exists() || !rootDoc.isDirectory) return false

            // Check and create subfolders
            val subFolders = listOf("Comics", "Videos", "Backup")
            subFolders.forEach { folderName ->
                val folder = rootDoc.findFile(folderName)
                if (folder == null || !folder.isDirectory) {
                    rootDoc.createDirectory(folderName)
                }
            }

            // Create .nomedia in root
            if (rootDoc.findFile(".nomedia") == null) {
                rootDoc.createFile("*/*", ".nomedia")
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    fun isUriPermissionValid(uriString: String?): Boolean {
        if (uriString.isNullOrEmpty()) return false
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Menelusuri DocumentFile berdasarkan jalur relatif.
     * Menggunakan cache URI untuk mempercepat pencarian berulang.
     */
    fun findFileByRelativePath(rootUri: Uri, relativePath: String): DocumentFile? {
        if (relativePath.isEmpty()) return DocumentFile.fromTreeUri(context, rootUri)

        val cacheKey = "$rootUri|$relativePath"
        uriCache.get(cacheKey)?.let { cachedUri ->
            // Restore DocumentFile from cached URI
            // Convention: Folders (categories/titles) don't have dots in the last segment.
            // Files (CBZ, MP4, images) always have an extension.
            val isDirectory = !relativePath.substringAfterLast('/').contains(".")
            val cachedDoc = if (isDirectory) {
                DocumentFile.fromTreeUri(context, cachedUri)
            } else {
                DocumentFile.fromSingleUri(context, cachedUri)
            }
            
            if (cachedDoc != null && cachedDoc.exists()) {
                return cachedDoc
            } else {
                uriCache.remove(cacheKey) // Remove invalid cache entry
            }
        }

        // Slow path: recursive traversal
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        var current: DocumentFile? = rootDoc
        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            current = current?.findFile(part)
            if (current == null) break
        }

        // Cache the result if found
        current?.uri?.let { resolvedUri ->
            uriCache.put(cacheKey, resolvedUri)
        }

        return current
    }

    /**
     * Clears the URI cache.
     */
    fun clearCache() {
        uriCache.evictAll()
    }

    /**
     * Deletes a file or folder identified by its relative path.
     * REVISION Delete Feature: Destructive physical deletion with strict path validation.
     */
    fun deleteFileByRelativePath(rootUri: Uri, relativePath: String): Result<Unit> {
        // Strict Validation: Only allow deletion inside "Comics/" or "Videos/"
        if (!relativePath.startsWith("Comics/") && !relativePath.startsWith("Videos/")) {
            return Result.failure(SecurityException("Unauthorized deletion target: $relativePath"))
        }
        
        if (relativePath.contains("..")) {
            return Result.failure(SecurityException("Path traversal attempt detected"))
        }

        return try {
            val file = findFileByRelativePath(rootUri, relativePath)
            if (file != null && file.exists()) {
                if (file.delete()) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to delete physical file: $relativePath"))
                }
            } else {
                Result.failure(Exception("File not found for deletion: $relativePath"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
