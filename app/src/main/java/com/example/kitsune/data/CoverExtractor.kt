package com.example.kitsune.data

import com.example.kitsune.data.dao.ChapterDao
import com.example.kitsune.data.dao.ComicDao
import com.example.kitsune.scanner.DefaultCbzCataloger
import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Result state for cover extraction operations.
 */
sealed interface CoverResult {
    /**
     * Cover image extracted successfully in memory as uncompressed byte payload.
     *
     * @param imageBytes The raw uncompressed byte payload of the cover image.
     * @param mimeType The inferred MIME type (e.g. "image/jpeg", "image/png", "image/webp").
     */
    data class Success(
        val imageBytes: ByteArray,
        val mimeType: String?
    ) : CoverResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return imageBytes.contentEquals(other.imageBytes) && mimeType == other.mimeType
        }

        override fun hashCode(): Int {
            var result = imageBytes.contentHashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Cover image could not be extracted (missing Chapter 1, invalid archive, corrupt image,
     * size limit exceeded, or unsupported format).
     *
     * @param reason Diagnostic explanation for why the cover is unavailable.
     */
    data class Unavailable(val reason: String) : CoverResult
}

/**
 * Component responsible for extracting the single cover image entry from `Chapter 1.cbz`.
 *
 * Constraints & Invariants:
 * 1. Strictly sources cover from the first valid image entry of `Chapter 1.cbz` recorded in [ComicEntity.coverEntryPath].
 * 2. Does NOT perform fallback to Chapter 2 or any other chapter (Rule K-07).
 * 3. Does NOT extract the full CBZ archive and does NOT write any files to the user's comic collection directory.
 * 4. Enforces a 10 MB maximum size limit on the extracted cover image.
 * 5. Handles `CancellationException` by rethrowing it to support Coroutine cancellation.
 * 6. Runs all stream I/O and ZIP parsing on [Dispatchers.IO].
 */
class CoverExtractor(
    private val storageGateway: StorageGateway,
    private val comicDao: ComicDao,
    private val chapterDao: ChapterDao
) {

    companion object {
        /** Maximum allowed cover image payload size (10 MB). */
        const val MAX_COVER_BYTE_SIZE: Long = 10L * 1024L * 1024L

        /**
         * Infers MIME type from entry file extension.
         */
        fun inferMimeType(entryPath: String): String? {
            return when (entryPath.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> null
            }
        }
    }

    /**
     * Extracts the single cover image entry from `Chapter 1.cbz` for [comicId].
     *
     * @param comicId ID of the comic entity in database.
     * @return [CoverResult.Success] if extracted, or [CoverResult.Unavailable] on failure.
     */
    suspend fun extractCover(comicId: Long): CoverResult = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch ComicEntity
            val comic = comicDao.getById(comicId)
                ?: return@withContext CoverResult.Unavailable("Comic with ID $comicId not found")

            val coverEntryPath = comic.coverEntryPath
            if (coverEntryPath.isNullOrBlank()) {
                return@withContext CoverResult.Unavailable("No cover entry path recorded for comic")
            }

            // Validate supported extension
            if (!DefaultCbzCataloger.isSupportedImageExtension(coverEntryPath)) {
                return@withContext CoverResult.Unavailable("Cover entry '$coverEntryPath' has unsupported extension")
            }

            // 2. Fetch Chapter 1 (STRICTLY chapterNumber == 1)
            val chapter1 = chapterDao.getChapterOneByComicId(comicId)
                ?: return@withContext CoverResult.Unavailable("Chapter 1 is missing for comic")

            if (!chapter1.isValid) {
                return@withContext CoverResult.Unavailable("Chapter 1 archive is marked invalid")
            }

            // 3. Open Chapter 1 InputStream via StorageGateway
            val streamResult = storageGateway.openInputStream(chapter1.relativePath)
            val inputStream = when (streamResult) {
                is StorageResult.Success -> streamResult.data
                is StorageResult.NotFound -> return@withContext CoverResult.Unavailable("Chapter 1 file not found at ${chapter1.relativePath}")
                is StorageResult.PermissionDenied -> return@withContext CoverResult.Unavailable("Permission denied for ${chapter1.relativePath}")
                is StorageResult.RootNotConfigured -> return@withContext CoverResult.Unavailable("Root folder not configured")
                is StorageResult.Error -> return@withContext CoverResult.Unavailable("Storage error: ${streamResult.exception.localizedMessage}")
            }

            // 4. Read ZIP entries sequentially until target coverEntryPath is found
            val normalizedTarget = coverEntryPath.replace('\\', '/')
            val mimeType = inferMimeType(normalizedTarget)

            readSingleEntryFromZip(inputStream, normalizedTarget, mimeType)
        } catch (ce: CancellationException) {
            throw ce // Propagate coroutine cancellation!
        } catch (ze: ZipException) {
            CoverResult.Unavailable("Corrupt ZIP archive: ${ze.localizedMessage}")
        } catch (ee: java.io.EOFException) {
            CoverResult.Unavailable("Truncated ZIP archive: ${ee.localizedMessage}")
        } catch (ioe: IOException) {
            CoverResult.Unavailable("I/O error reading cover: ${ioe.localizedMessage}")
        } catch (e: Exception) {
            CoverResult.Unavailable("Unexpected error: ${e.localizedMessage}")
        }
    }

    private fun readSingleEntryFromZip(
        inputStream: InputStream,
        targetEntryPath: String,
        mimeType: String?
    ): CoverResult {
        ZipInputStream(inputStream).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val normalizedName = entry.name.replace('\\', '/')

                if (!entry.isDirectory && normalizedName == targetEntryPath) {
                    // Check header size metadata if available
                    if (entry.size > MAX_COVER_BYTE_SIZE) {
                        return CoverResult.Unavailable("Cover image exceeds size limit of 10 MB (${entry.size} bytes)")
                    }

                    // Read payload bytes with 10 MB size limit check
                    val baos = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var totalRead = 0L

                    while (true) {
                        val count = zis.read(buffer)
                        if (count < 0) break
                        totalRead += count
                        if (totalRead > MAX_COVER_BYTE_SIZE) {
                            return CoverResult.Unavailable("Cover image stream exceeded size limit of 10 MB")
                        }
                        baos.write(buffer, 0, count)
                    }

                    val imageBytes = baos.toByteArray()
                    if (imageBytes.isEmpty()) {
                        return CoverResult.Unavailable("Cover image entry payload is empty")
                    }

                    return CoverResult.Success(
                        imageBytes = imageBytes,
                        mimeType = mimeType
                    )
                }
                try {
                    zis.closeEntry()
                } catch (_: Exception) {}
            }
        }

        return CoverResult.Unavailable("Cover entry '$targetEntryPath' not found in Chapter 1 archive")
    }
}
