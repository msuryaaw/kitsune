package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageEntry

/**
 * A [CbzCataloger] decorator that skips re-reading a CBZ archive when the caller supplies
 * [StorageEntry] metadata and the archive's fingerprint matches a previously-valid indexed entry.
 *
 * ## Incremental skip conditions (ALL must hold)
 * 1. [fileMetadata] is non-null (the scanner must supply it via [DefaultLibraryScanner]).
 * 2. Both [StorageEntry.sizeBytes] and [StorageEntry.lastModifiedMillis] are non-null.
 * 3. A matching [CachedEntry] for the same [cbzRelativePath] exists in the current index.
 * 4. The cached entry's [CachedEntry.fileSize] and [CachedEntry.lastModifiedMillis] equal the
 *    corresponding [StorageEntry] values.
 * 5. The cached entry is marked [CachedEntry.wasValid] (previously-invalid chapters are always
 *    re-cataloged so a repaired archive on disk is picked up).
 *
 * When all conditions are met, [catalogArchive] returns the stored [CbzCatalogResult] from the
 * cache without opening any stream or reading any bytes from storage.
 * In all other cases, the call is delegated to the wrapped [delegate] cataloger.
 *
 * ## Cover preservation
 * For Chapter 1, the synthesized [CbzCatalogResult.Success] must include the cover entry path
 * so that [ScannedComic.coverEntryPath] returns the correct value and the comic's cover is not
 * overwritten with `null` on incremental re-scans. The [CachedEntry.cachedResult] is built by the
 * repository (see [DefaultLibraryRepository]) using the stored [com.example.kitsune.data.entity.ComicEntity.coverEntryPath]
 * for Chapter 1 and a synthetic stub list for other chapters.
 *
 * ## Thread safety
 * The index is replaced atomically via [updateIndex]. Concurrent calls to [catalogArchive] during
 * a scan are safe as long as [updateIndex] is called before [DefaultLibraryScanner.scanLibrary].
 * ([DefaultLibraryScanner.scanLibrary] is not concurrent by design.)
 *
 * @param delegate The real [CbzCataloger] that performs actual CBZ archive I/O.
 */
class IncrementalCbzCataloger(
    private val delegate: CbzCataloger
) : CbzCataloger {

    /**
     * A cached catalog entry representing a previously-indexed chapter archive.
     *
     * @param fileSize Byte size of the archive as recorded during the previous scan.
     * @param lastModifiedMillis Last-modified timestamp as recorded during the previous scan.
     * @param wasValid Whether the previous catalog result was a [CbzCatalogResult.Success].
     *   Only valid chapters are eligible for the incremental skip; previously-invalid chapters
     *   (corrupt, empty, error) are always re-cataloged so repairs on disk are detected.
     * @param cachedResult The [CbzCatalogResult] to return when the fingerprint matches.
     *   For valid chapters this is a [CbzCatalogResult.Success] with synthesized page stubs
     *   (preserving [pageCount] and cover entry path for Chapter 1).
     */
    data class CachedEntry(
        val fileSize: Long,
        val lastModifiedMillis: Long,
        val wasValid: Boolean,
        val cachedResult: CbzCatalogResult
    )

    /** Current index of known-good chapters. Replaced wholesale before each scan. */
    @Volatile
    private var index: Map<String, CachedEntry> = emptyMap()

    /**
     * Replaces the current chapter index.
     *
     * Must be called by [DefaultLibraryRepository] with the latest DB snapshot **before** calling
     * [DefaultLibraryScanner.scanLibrary] so that the index reflects the state from the prior scan.
     */
    fun updateIndex(newIndex: Map<String, CachedEntry>) {
        index = newIndex
    }

    /**
     * Returns the current index size (for testing/diagnostics only).
     */
    val indexSize: Int get() = index.size

    /**
     * Catalogs the CBZ archive, skipping actual I/O when the archive is unchanged.
     *
     * @param cbzRelativePath Relative path of the CBZ archive.
     * @param fileMetadata Optional [StorageEntry] for the archive. When non-null and the
     *   fingerprint matches the cached entry, returns the cached result immediately.
     *   When null, always delegates to the real cataloger.
     */
    override fun catalogArchive(
        cbzRelativePath: String,
        fileMetadata: StorageEntry?
    ): CbzCatalogResult {
        if (fileMetadata != null) {
            val scannedSize = fileMetadata.sizeBytes
            val scannedModified = fileMetadata.lastModifiedMillis

            // Both metadata values must be non-null to form a reliable fingerprint.
            // Null metadata is treated as "unknown" → always re-catalog.
            if (scannedSize != null && scannedModified != null) {
                val cached = index[cbzRelativePath]
                if (cached != null
                    && cached.wasValid
                    && cached.fileSize == scannedSize
                    && cached.lastModifiedMillis == scannedModified
                ) {
                    return cached.cachedResult
                }
            }
        }
        return delegate.catalogArchive(cbzRelativePath, fileMetadata)
    }
}

