package com.example.kitsune.reader

import com.example.kitsune.scanner.DefaultCbzCataloger
import com.example.kitsune.scanner.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Contract for building an immutable page index from a local CBZ archive file.
 */
interface CbzPageIndexer {
    /**
     * Builds a [ChapterPageIndex] for [chapterId] from [archiveFile].
     * Borrowed access only; does not close or own the archive file handle.
     */
    suspend fun buildIndex(chapterId: Long, archiveFile: File): PageIndexResult
}

/**
 * Default implementation of [CbzPageIndexer] with path security validation,
 * natural order sorting, duplicate rejection, and safety caps.
 */
class DefaultCbzPageIndexer(
    private val maxTotalZipEntries: Int = MAX_TOTAL_ZIP_ENTRIES,
    private val maxPageCount: Int = MAX_PAGE_COUNT,
    private val maxUncompressedEntrySizeBytes: Long = MAX_UNCOMPRESSED_ENTRY_SIZE_BYTES
) : CbzPageIndexer {

    companion object {
        /** Maximum allowed total ZIP entries in an archive. */
        const val MAX_TOTAL_ZIP_ENTRIES = 10_000

        /** Maximum allowed image pages in a chapter. */
        const val MAX_PAGE_COUNT = 1_000

        /** Maximum allowed uncompressed size for a single image entry (100 MiB). */
        const val MAX_UNCOMPRESSED_ENTRY_SIZE_BYTES = 100L * 1024L * 1024L

        /**
         * Validates entry path against suspicious or malicious path traversal indicators.
         */
        fun isSuspiciousPath(path: String): Boolean {
            if (path.contains("\u0000")) return true
            if (path.startsWith("/") || path.startsWith("\\")) return true
            if (path.contains(":")) return true // Windows drive letter or URI scheme
            val segments = path.replace('\\', '/').split('/')
            return segments.any { it == ".." }
        }
    }

    override suspend fun buildIndex(chapterId: Long, archiveFile: File): PageIndexResult = withContext(Dispatchers.IO) {
        if (!archiveFile.exists() || !archiveFile.isFile) {
            return@withContext PageIndexResult.CorruptArchive("File arsip CBZ tidak ditemukan pada disk")
        }

        try {
            ZipFile(archiveFile).use { zipFile ->
                val entries = zipFile.entries()
                var totalZipEntries = 0
                val rawCandidates = mutableListOf<RawEntry>()
                val seenEntryNames = mutableSetOf<String>()
                val seenNormalizedPaths = mutableSetOf<String>()

                while (entries.hasMoreElements()) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    totalZipEntries++
                    if (totalZipEntries > maxTotalZipEntries) {
                        return@withContext PageIndexResult.LimitExceeded(
                            "Total entri dalam arsip ZIP melebihi batas $maxTotalZipEntries entri"
                        )
                    }

                    val entry: ZipEntry = try {
                        entries.nextElement()
                    } catch (e: Exception) {
                        return@withContext PageIndexResult.CorruptArchive(
                            "Gagal membaca entri ZIP: ${e.localizedMessage}"
                        )
                    }

                    val rawName = entry.name
                    if (isSuspiciousPath(rawName)) {
                        return@withContext PageIndexResult.CorruptArchive(
                            "Path entri ZIP tidak aman atau terindikasi path traversal: $rawName"
                        )
                    }

                    val normalizedPath = rawName.replace('\\', '/')
                    val isIgnored = entry.isDirectory ||
                            normalizedPath.endsWith('/') ||
                            DefaultCbzCataloger.isIgnoredMetadataOrJunk(normalizedPath)

                    if (!isIgnored && DefaultCbzCataloger.isSupportedImageExtension(normalizedPath)) {
                        val size = if (entry.size >= 0) entry.size else null
                        if (size != null && size > maxUncompressedEntrySizeBytes) {
                            return@withContext PageIndexResult.LimitExceeded(
                                "Ukuran entri gambar $normalizedPath ($size bytes) melebihi batas $maxUncompressedEntrySizeBytes bytes"
                            )
                        }

                        if (!seenEntryNames.add(rawName)) {
                            return@withContext PageIndexResult.CorruptArchive(
                                "Terdapat nama entri gambar duplikat di dalam arsip: $rawName"
                            )
                        }

                        if (!seenNormalizedPaths.add(normalizedPath)) {
                            return@withContext PageIndexResult.CorruptArchive(
                                "Terdapat normalized path gambar duplikat di dalam arsip: $normalizedPath"
                            )
                        }

                        rawCandidates.add(
                            RawEntry(
                                entryName = rawName,
                                normalizedPath = normalizedPath,
                                sizeBytes = size
                            )
                        )

                        if (rawCandidates.size > maxPageCount) {
                            return@withContext PageIndexResult.LimitExceeded(
                                "Jumlah halaman gambar melebihi batas maksimum $maxPageCount halaman per chapter"
                            )
                        }
                    }
                }

                if (rawCandidates.isEmpty()) {
                    return@withContext PageIndexResult.NoImagesFound
                }

                // Natural order sorting using normalizedPath
                val sortedCandidates = rawCandidates.sortedWith { e1, e2 ->
                    NaturalOrderComparator.compare(e1.normalizedPath, e2.normalizedPath)
                }

                val pages = sortedCandidates.mapIndexed { index, raw ->
                    PageMetadata(
                        pageIndex = index,
                        pageNumber = index + 1,
                        entryName = raw.entryName,
                        normalizedPath = raw.normalizedPath,
                        sizeBytes = raw.sizeBytes
                    )
                }

                PageIndexResult.Success(
                    ChapterPageIndex(
                        chapterId = chapterId,
                        pages = pages
                    )
                )
            }
        } catch (ze: ZipException) {
            PageIndexResult.CorruptArchive("Arsip ZIP rusak atau format tidak valid: ${ze.localizedMessage}")
        } catch (ioe: IOException) {
            PageIndexResult.StorageError(ioe)
        } catch (e: Exception) {
            PageIndexResult.StorageError(e)
        }
    }

    private data class RawEntry(
        val entryName: String,
        val normalizedPath: String,
        val sizeBytes: Long?
    )
}
