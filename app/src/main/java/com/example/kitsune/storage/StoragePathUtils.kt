package com.example.kitsune.storage

/**
 * Pure helper for normalizing and safely joining relative paths.
 * Guarantees no path traversal attacks (e.g. "../"), no absolute path assumptions,
 * and standard forward-slash normalization.
 */
object StoragePathUtils {

    /**
     * Normalizes a relative path string:
     * - Replaces backslashes with forward slashes
     * - Trims leading and trailing slashes and whitespace
     * - Resolves and eliminates single dots "."
     * - Blocks and rejects path traversal elements ".."
     */
    fun normalizeRelativePath(rawPath: String?): String {
        if (rawPath.isNullOrBlank()) return ""

        val replaced = rawPath.trim().replace('\\', '/')
        val segments = replaced.split('/').filter { it.isNotEmpty() && it != "." }

        for (segment in segments) {
            if (segment == "..") {
                throw SecurityException("Percobaan path traversal terdeteksi pada segmen: '$rawPath'")
            }
        }

        return segments.joinToString("/")
    }

    /**
     * Combines parent and child into a normalized relative path.
     */
    fun combineRelativePath(parent: String, childName: String): String {
        val normParent = normalizeRelativePath(parent)
        val normChild = normalizeRelativePath(childName)

        return when {
            normParent.isEmpty() -> normChild
            normChild.isEmpty() -> normParent
            else -> "$normParent/$normChild"
        }
    }
}

