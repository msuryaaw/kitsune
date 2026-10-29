package com.example.kitsune.scanner

/**
 * Representation of an image page entry indexed from a CBZ archive.
 *
 * @param entryPath Path of the entry inside the archive with forward slash separators (e.g. "image_01.jpg" or "pages/p01.png").
 * @param pageNumber 1-based index according to natural sort order.
 * @param sizeBytes Uncompressed byte size of the entry, if available.
 */
data class CbzPageEntry(
    val entryPath: String,
    val pageNumber: Int,
    val sizeBytes: Long? = null
)

/**
 * Result state when indexing a CBZ archive.
 */
sealed class CbzCatalogResult {
    /**
     * Archive was successfully cataloged and contains at least one valid image page.
     * Pages are deterministically ordered using [NaturalOrderComparator].
     */
    data class Success(
        val cbzRelativePath: String,
        val pages: List<CbzPageEntry>
    ) : CbzCatalogResult()

    /**
     * Archive is valid and readable, but contains no valid image entries.
     */
    data class Empty(
        val cbzRelativePath: String
    ) : CbzCatalogResult()

    /**
     * The requested CBZ file was not found at the specified relative path.
     */
    data class NotFound(
        val cbzRelativePath: String
    ) : CbzCatalogResult()

    /**
     * Storage access permission is missing or revoked.
     */
    data class PermissionDenied(
        val message: String
    ) : CbzCatalogResult()

    /**
     * Root directory has not been configured.
     */
    object RootNotConfigured : CbzCatalogResult()

    /**
     * The archive is corrupt, truncated, not a valid ZIP file, or an I/O error occurred.
     */
    data class CorruptArchive(
        val cbzRelativePath: String,
        val reason: String,
        val cause: Throwable? = null
    ) : CbzCatalogResult()

    /**
     * General error during cataloging.
     */
    data class Error(
        val exception: Throwable
    ) : CbzCatalogResult()
}

/**
 * Contract for inspecting and indexing image page entries from CBZ archives.
 */
interface CbzCataloger {

    /**
     * Indexes the CBZ archive located at [cbzRelativePath] without decoding bitmap pixels
     * and without extracting archive files to disk.
     *
     * @param cbzRelativePath Relative path of the CBZ archive from the storage root.
     * @param fileMetadata Optional [StorageEntry] metadata for the archive file. When non-null,
     *   implementations that support incremental caching may use [StorageEntry.sizeBytes] and
     *   [StorageEntry.lastModifiedMillis] as a fingerprint to skip re-reading the archive.
     *   Implementations that do not support caching may safely ignore this parameter.
     */
    fun catalogArchive(
        cbzRelativePath: String,
        fileMetadata: com.example.kitsune.storage.StorageEntry? = null
    ): CbzCatalogResult
}

