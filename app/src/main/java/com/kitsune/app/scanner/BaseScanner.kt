package com.kitsune.app.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kitsune.app.core.NaturalOrderComparator
import com.kitsune.app.core.StorageHelper

/**
 * Base class for all scanners in Kitsune.
 * Handles generic filesystem traversal, natural sorting, and SAF helpers.
 * REVISION 10.2.1: Extract shared logic and ensure thread-safe operations.
 */
abstract class BaseScanner(
    protected val context: Context,
    protected val storageHelper: StorageHelper
) {
    protected val naturalOrderComparator = NaturalOrderComparator()
    protected val allowedImageExtensions = listOf("jpg", "jpeg", "png", "webp")

    /**
     * Resolves the root folder for a given tree URI.
     * Note: Does not use instance-level caching to avoid thread safety issues
     * with mutable shared state.
     */
    protected fun getRootFolder(rootUri: Uri): DocumentFile? {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            if (rootDoc != null && rootDoc.exists() && rootDoc.isDirectory) {
                rootDoc
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves a category folder (e.g., "Comics", "Videos") under the root URI.
     */
    protected fun getCategoryFolder(rootUri: Uri, category: String): DocumentFile? {
        val root = getRootFolder(rootUri) ?: return null
        val folder = root.findFile(category)
        return if (folder != null && folder.exists() && folder.isDirectory) {
            folder
        } else null
    }

    /**
     * Common logic to find a cover file (cover.* or poster.*) in a directory.
     */
    protected fun findCover(folder: DocumentFile): Uri? {
        return folder.listFiles().find { file ->
            val fileName = file.name?.lowercase() ?: ""
            allowedImageExtensions.any { ext -> 
                fileName == "cover.$ext" || fileName == "poster.$ext" 
            }
        }?.uri
    }
    
    /**
     * Verifies if a specific category folder is valid.
     */
    open fun isCategoryFolderValid(rootUri: Uri, category: String): Boolean {
        return try {
            val folder = getCategoryFolder(rootUri, category)
            folder != null && folder.exists() && folder.isDirectory
        } catch (e: Exception) {
            false
        }
    }
}
