package com.example.kitsune.data

import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Result state for cover cache queries.
 */
sealed interface CoverCacheResult {
    /**
     * File was read directly from disk cache without extracting from ZIP archive (Cache Hit).
     */
    data class Hit(val file: File) : CoverCacheResult

    /**
     * Cover was extracted from Chapter 1.cbz and newly committed to disk cache (Cache Miss).
     */
    data class Success(val file: File) : CoverCacheResult

    /**
     * Cover is unavailable (Chapter 1 missing, corrupt archive, or extraction failed).
     */
    data class Unavailable(val reason: String) : CoverCacheResult
}

/**
 * Manager responsible for private disk caching of cover images (`cacheDir/covers/`).
 *
 * Invariants & Policies:
 * 1. Storage Isolation: All cache files are stored strictly in private [cacheDir]. Zero files written to user's comic collection.
 * 2. SHA-256 Fingerprint: Cache key uses full 64-character SHA-256 hash of comic identity, Chapter 1 path, coverEntryPath, fileSize, and lastModifiedAt.
 * 3. Automatic Invalidation: If source metadata (size/timestamp) or coverEntryPath changes, the hash changes, invalidating old cache files.
 * 4. Atomic Write: Writes first to a `.tmp` file in the same directory, then renames to final cache file. Partial files are never treated as valid.
 * 5. Double-Checked Locking: Concurrent requests for the same comic ID share a ref-counted `Mutex`. Waiting calls re-check for file existence after acquiring lock.
 * 6. Ephemeral Retention Policy: Ephemeral files (missing metadata) use active reference counting (`retainCoverFile`/`releaseCoverFile`). Active ephemeral files are never deleted during render. Unreferenced files are cleaned up on release or sync.
 * 7. Thread Safety: Runs all file I/O on [Dispatchers.IO]. Rethrows [CancellationException].
 */
class CoverCacheManager(
    private val cacheDir: File,
    private val coverExtractor: CoverExtractor,
    private val comicDao: ComicDao,
    private val chapterDao: ChapterDao
) {

    private class LockRef {
        val mutex = Mutex()
        var refCount = 0
    }

    /** Reference-counted per-comic locks preventing duplicate simultaneous extraction. */
    private val lockMap = ConcurrentHashMap<Long, LockRef>()

    /** Active consumer reference counter for ephemeral cover files. */
    private val activeEphemeralRefs = ConcurrentHashMap<String, AtomicInteger>()

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        } else {
            cleanupOrphanedTempAndEphemeralOnStartup()
        }
    }

    companion object {
        /**
         * Computes full 64-character hex SHA-256 hash of [input].
         */
        fun computeSha256Hex(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Determines extension from [entryPath] / [mimeType].
         */
        fun resolveExtension(entryPath: String, mimeType: String?): String {
            return when (mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/jpeg" -> "jpg"
                else -> when (entryPath.substringAfterLast('.', "").lowercase()) {
                    "png" -> "png"
                    "webp" -> "webp"
                    else -> "jpg"
                }
            }
        }
    }

    /**
     * Executes block within a reference-counted mutex lock for [comicId].
     */
    private suspend fun <T> withComicLock(comicId: Long, block: suspend () -> T): T {
        val ref = synchronized(lockMap) {
            lockMap.computeIfAbsent(comicId) { LockRef() }.also { it.refCount++ }
        }
        return try {
            ref.mutex.withLock {
                block()
            }
        } finally {
            synchronized(lockMap) {
                ref.refCount--
                if (ref.refCount == 0) {
                    lockMap.remove(comicId)
                }
            }
        }
    }

    /**
     * Registers an active consumer reference for an ephemeral cover file so it is not deleted while in use.
     */
    fun retainCoverFile(file: File) {
        if (file.name.startsWith("ephemeral_cover_")) {
            activeEphemeralRefs.computeIfAbsent(file.name) { AtomicInteger(0) }.incrementAndGet()
        }
    }

    /**
     * Releases an active consumer reference for an ephemeral cover file.
     * When the last reference is released, the unreferenced ephemeral file is safely deleted immediately.
     */
    fun releaseCoverFile(file: File) {
        if (file.name.startsWith("ephemeral_cover_")) {
            val ref = activeEphemeralRefs[file.name] ?: return
            val remaining = ref.decrementAndGet()
            if (remaining <= 0) {
                activeEphemeralRefs.remove(file.name)
                try {
                    if (file.exists() && file.isFile) {
                        file.delete()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Checks if an ephemeral file is currently active/retained by a consumer.
     */
    fun isEphemeralFileActive(file: File): Boolean {
        return (activeEphemeralRefs[file.name]?.get() ?: 0) > 0
    }

    /**
     * Retrieves or extracts the cover image file for [comicId].
     */
    suspend fun getCoverFile(comicId: Long): CoverCacheResult = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch Comic & Chapter 1 metadata from DB
            val comic = comicDao.getById(comicId)
                ?: return@withContext CoverCacheResult.Unavailable("Comic with ID $comicId not found")

            val coverEntryPath = comic.coverEntryPath
            if (coverEntryPath.isNullOrBlank()) {
                clearStaleCacheForComic(comicId, null)
                return@withContext CoverCacheResult.Unavailable("No cover entry path recorded for comic")
            }

            val chapter1 = chapterDao.getChapterOneByComicId(comicId)
                ?: run {
                    clearStaleCacheForComic(comicId, null)
                    return@withContext CoverCacheResult.Unavailable("Chapter 1 missing for comic")
                }

            if (!chapter1.isValid) {
                clearStaleCacheForComic(comicId, null)
                return@withContext CoverCacheResult.Unavailable("Chapter 1 archive marked invalid")
            }

            // If metadata is null (storage provider didn't report size/timestamp), fallback to direct extraction without fingerprint cache
            val fileSize = chapter1.fileSize
            val lastModified = chapter1.lastModifiedAt
            if (fileSize == null || lastModified == null) {
                return@withContext extractDirectlyWithoutPersistentCache(comicId)
            }

            // 2. Compute SHA-256 Fingerprint Hash & Cache File Path
            val rawKey = "${comic.id}_${comic.relativePath}_${chapter1.relativePath}_${coverEntryPath}_${fileSize}_${lastModified}"
            val fingerprintHash = computeSha256Hex(rawKey)
            val ext = resolveExtension(coverEntryPath, null)
            val finalFileName = "cover_${comicId}_${fingerprintHash}.$ext"
            val finalCacheFile = File(cacheDir, finalFileName)

            // 3. Fast-Path Cache Hit (no lock needed if file already exists)
            if (finalCacheFile.isFile && finalCacheFile.length() > 0) {
                return@withContext CoverCacheResult.Hit(finalCacheFile)
            }

            // 4. Cache Miss — Acquire Ref-Counted Per-Comic Lock (Double-Checked Locking)
            withComicLock(comicId) {
                // Re-check if previous thread created the file while we waited
                if (finalCacheFile.isFile && finalCacheFile.length() > 0) {
                    return@withComicLock CoverCacheResult.Hit(finalCacheFile)
                }

                // Extract cover bytes via CoverExtractor
                when (val extractResult = coverExtractor.extractCover(comicId)) {
                    is CoverResult.Unavailable -> {
                        CoverCacheResult.Unavailable(extractResult.reason)
                    }
                    is CoverResult.Success -> {
                        // Atomic write via temporary file in same cache directory
                        val tempFile = File(cacheDir, "temp_cover_${comicId}_${System.nanoTime()}.tmp")
                        try {
                            FileOutputStream(tempFile).use { fos ->
                                writeBytesWithCancellationCheck(fos, extractResult.imageBytes)
                            }

                            if (tempFile.length() != extractResult.imageBytes.size.toLong()) {
                                if (tempFile.exists()) tempFile.delete()
                                return@withComicLock CoverCacheResult.Unavailable("Written temp file size mismatch")
                            }

                            // Commit atomically
                            val renamed = tempFile.renameTo(finalCacheFile)
                            if (!renamed) {
                                // Fallback for filesystem move
                                try {
                                    tempFile.copyTo(finalCacheFile, overwrite = true)
                                    tempFile.delete()
                                } catch (e: Exception) {
                                    if (tempFile.exists()) tempFile.delete()
                                    return@withComicLock CoverCacheResult.Unavailable("Failed to commit cache file: ${e.localizedMessage}")
                                }
                            }

                            // Clean up any old stale cache files for this comic
                            clearStaleCacheForComic(comicId, finalFileName)

                            CoverCacheResult.Success(finalCacheFile)
                        } catch (ce: CancellationException) {
                            if (tempFile.exists()) tempFile.delete()
                            throw ce
                        } catch (e: Exception) {
                            if (tempFile.exists()) tempFile.delete()
                            CoverCacheResult.Unavailable("Failed writing cache file: ${e.localizedMessage}")
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            CoverCacheResult.Unavailable("Cache error: ${e.localizedMessage}")
        }
    }

    /**
     * Writes byte array in chunks with coroutine cancellation checks.
     */
    private suspend fun writeBytesWithCancellationCheck(fos: FileOutputStream, bytes: ByteArray) {
        val chunkSize = 8192
        var offset = 0
        while (offset < bytes.size) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val toWrite = minOf(chunkSize, bytes.size - offset)
            fos.write(bytes, offset, toWrite)
            offset += toWrite
        }
        fos.flush()
    }

    /**
     * Direct extraction without persistent fingerprint caching for sources with missing metadata.
     */
    private suspend fun extractDirectlyWithoutPersistentCache(comicId: Long): CoverCacheResult {
        return when (val extractResult = coverExtractor.extractCover(comicId)) {
            is CoverResult.Unavailable -> CoverCacheResult.Unavailable(extractResult.reason)
            is CoverResult.Success -> {
                val timestamp = System.currentTimeMillis()
                val nano = System.nanoTime()
                val newEphemeralName = "ephemeral_cover_${comicId}_${timestamp}_${nano}.jpg"
                val ephemeralFile = File(cacheDir, newEphemeralName)
                val tempFile = File(cacheDir, "temp_ephemeral_${comicId}_${nano}.tmp")
                try {
                    FileOutputStream(tempFile).use { fos ->
                        writeBytesWithCancellationCheck(fos, extractResult.imageBytes)
                    }

                    if (tempFile.length() != extractResult.imageBytes.size.toLong()) {
                        if (tempFile.exists()) tempFile.delete()
                        return CoverCacheResult.Unavailable("Ephemeral write size mismatch")
                    }

                    val renamed = tempFile.renameTo(ephemeralFile)
                    if (!renamed) {
                        tempFile.copyTo(ephemeralFile, overwrite = true)
                        tempFile.delete()
                    }

                    // Register active consumer reference before returning to consumer
                    retainCoverFile(ephemeralFile)

                    // Clean up stale unreferenced ephemeral files for this comic
                    clearStaleEphemeralFilesForComic(comicId)

                    CoverCacheResult.Success(ephemeralFile)
                } catch (ce: CancellationException) {
                    if (tempFile.exists()) tempFile.delete()
                    throw ce
                } catch (e: Exception) {
                    if (tempFile.exists()) tempFile.delete()
                    CoverCacheResult.Unavailable("Ephemeral write failed: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * Clears old cache files for [comicId] except [keepFileName].
     */
    fun clearStaleCacheForComic(comicId: Long, keepFileName: String?) {
        val prefix = "cover_${comicId}_"
        val files = cacheDir.listFiles() ?: return
        for (file in files) {
            if (file.name.startsWith(prefix) && file.name != keepFileName) {
                try {
                    file.delete()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Safely cleans up unreferenced ephemeral files for [comicId] without deleting files used by active consumers.
     */
    fun clearStaleEphemeralFilesForComic(comicId: Long) {
        val prefix = "ephemeral_cover_${comicId}_"
        val tempPrefix = "temp_ephemeral_${comicId}_"
        val files = cacheDir.listFiles() ?: return
        for (file in files) {
            if (file.name.startsWith(tempPrefix)) {
                try { file.delete() } catch (_: Exception) {}
            } else if (file.name.startsWith(prefix)) {
                // Delete ephemeral file ONLY IF it is not actively retained by a consumer
                if (!isEphemeralFileActive(file)) {
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Cleans up all orphaned `.tmp` and stale unreferenced ephemeral files left over from prior app sessions on startup.
     */
    private fun cleanupOrphanedTempAndEphemeralOnStartup() {
        val files = cacheDir.listFiles() ?: return
        for (file in files) {
            val isTemp = file.name.startsWith("temp_")
            val isEphemeral = file.name.startsWith("ephemeral_cover_")
            if (isTemp || (isEphemeral && !isEphemeralFileActive(file))) {
                try {
                    file.delete()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Clears all cached files in private covers directory.
     */
    fun clearAllCache() {
        activeEphemeralRefs.clear()
        val files = cacheDir.listFiles() ?: return
        for (file in files) {
            try {
                file.delete()
            } catch (_: Exception) {}
        }
    }
}
