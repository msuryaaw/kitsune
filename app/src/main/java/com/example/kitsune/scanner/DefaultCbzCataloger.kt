package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Default implementation of [CbzCataloger].
 *
 * Reads archive entries via streaming [ZipInputStream] from [StorageGateway.openInputStream],
 * filtering for supported image extensions (.jpg, .jpeg, .png, .webp) ignoring case.
 * Discards directories, metadata junk (e.g. `__MACOSX/`, `.DS_Store`), and non-image files.
 * Normalizes internal entry paths to `/` and sorts them deterministically using [NaturalOrderComparator].
 * Does not decode bitmap pixels and does not extract entries to disk.
 *
 * Note on ZIP validation:
 * [ZipInputStream] inspects local file headers sequentially. It detects non-ZIP headers,
 * corrupt local headers, and truncated stream entries during iteration.
 * However, corruption occurring purely within the Central Directory at the end of the file
 * is not guaranteed to be detected by forward-only streaming without reading to EOF or seeking.
 */
class DefaultCbzCataloger(
    private val storageGateway: StorageGateway
) : CbzCataloger {

    companion object {
        /**
         * Standard ZIP local file header signature: 0x04034b50 ('P', 'K', 3, 4).
         * Also accepts empty archive with end-of-central-directory signature: 0x06054b50 ('P', 'K', 5, 6),
         * or spanned archive signature: 0x08074b50 ('P', 'K', 7, 8).
         */
        private val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val ZIP_EOCD_HEADER = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        private val ZIP_SPANNED_HEADER = byteArrayOf(0x50, 0x4B, 0x07, 0x08)

        /**
         * Supported image extensions per CBZ format specification (Section 2).
         */
        val SUPPORTED_IMAGE_EXTENSIONS = setOf(
            "jpg",
            "jpeg",
            "png",
            "webp"
        )

        /**
         * Checks whether an entry path has a supported image extension.
         */
        fun isSupportedImageExtension(path: String): Boolean {
            val extension = path.substringAfterLast('.', "").lowercase()
            return extension in SUPPORTED_IMAGE_EXTENSIONS
        }

        /**
         * Checks whether an entry path represents known metadata or junk files.
         */
        fun isIgnoredMetadataOrJunk(normalizedPath: String): Boolean {
            val lower = normalizedPath.lowercase()
            return lower.startsWith("__macosx/") ||
                    lower.contains("/__macosx/") ||
                    lower.endsWith("/.ds_store") ||
                    lower == ".ds_store" ||
                    lower.endsWith("/thumbs.db") ||
                    lower == "thumbs.db"
        }
    }

    override fun catalogArchive(
        cbzRelativePath: String,
        fileMetadata: com.example.kitsune.storage.StorageEntry?
    ): CbzCatalogResult {
        // 1. Validasi status root storage
        when (val rootState = storageGateway.getRootState()) {
            is StorageResult.RootNotConfigured -> return CbzCatalogResult.RootNotConfigured
            is StorageResult.PermissionDenied -> return CbzCatalogResult.PermissionDenied(rootState.message)
            is StorageResult.Error -> return CbzCatalogResult.Error(rootState.exception)
            is StorageResult.NotFound -> return CbzCatalogResult.NotFound(cbzRelativePath)
            is StorageResult.Success -> {
                // Root siap, lanjutkan membuka CBZ
            }
        }

        // 2. Buka InputStream untuk file CBZ
        val streamResult = storageGateway.openInputStream(cbzRelativePath)
        val rawStream: InputStream = when (streamResult) {
            is StorageResult.Success -> streamResult.data
            is StorageResult.NotFound -> return CbzCatalogResult.NotFound(cbzRelativePath)
            is StorageResult.PermissionDenied -> return CbzCatalogResult.PermissionDenied(streamResult.message)
            is StorageResult.RootNotConfigured -> return CbzCatalogResult.RootNotConfigured
            is StorageResult.Error -> return CbzCatalogResult.Error(streamResult.exception)
        }

        val bufferedStream = if (rawStream.markSupported()) rawStream else BufferedInputStream(rawStream)

        return try {
            // 3. Verifikasi signature awal file ZIP sebelum memproses stream
            bufferedStream.mark(4)
            val headerBytes = ByteArray(4)
            val bytesRead = readFully(bufferedStream, headerBytes)
            bufferedStream.reset()

            if (bytesRead < 4 || !isValidZipHeader(headerBytes)) {
                return CbzCatalogResult.CorruptArchive(
                    cbzRelativePath = cbzRelativePath,
                    reason = "Bukan file ZIP yang valid (signature header tidak cocok atau file terlalu pendek)"
                )
            }

            // Jika diawali EOCD header (PK\x05\x06), validasi record EOCD secara lengkap untuk empty ZIP
            if (headerBytes.contentEquals(ZIP_EOCD_HEADER)) {
                return if (isValidEmptyZipEocd(bufferedStream)) {
                    CbzCatalogResult.Empty(cbzRelativePath)
                } else {
                    CbzCatalogResult.CorruptArchive(
                        cbzRelativePath = cbzRelativePath,
                        reason = "Header EOCD terpotong atau rusak pada arsip ZIP kosong"
                    )
                }
            }

            readAndCatalogZipStream(bufferedStream, cbzRelativePath)
        } catch (ze: ZipException) {
            CbzCatalogResult.CorruptArchive(
                cbzRelativePath = cbzRelativePath,
                reason = "Arsip ZIP rusak atau format tidak valid: ${ze.localizedMessage}",
                cause = ze
            )
        } catch (ee: java.io.EOFException) {
            CbzCatalogResult.CorruptArchive(
                cbzRelativePath = cbzRelativePath,
                reason = "Arsip ZIP terpotong sebelum data selesai dibaca: ${ee.localizedMessage}",
                cause = ee
            )
        } catch (ioe: IOException) {
            CbzCatalogResult.Error(ioe)
        } catch (e: Exception) {
            CbzCatalogResult.Error(e)
        } finally {
            try {
                bufferedStream.close()
            } catch (_: Exception) {}
        }
    }

    private fun readFully(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = stream.read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun isValidZipHeader(header: ByteArray): Boolean {
        if (header.size < 4) return false
        return header.contentEquals(ZIP_LOCAL_HEADER) ||
                header.contentEquals(ZIP_EOCD_HEADER) ||
                header.contentEquals(ZIP_SPANNED_HEADER)
    }

    /**
     * Memvalidasi apakah stream adalah empty ZIP record EOCD yang utuh dan valid (minimal 22 byte,
     * total entri 0, dan tidak terpotong).
     */
    private fun isValidEmptyZipEocd(stream: InputStream): Boolean {
        // Record EOCD minimum berukuran 22 byte:
        // 0..3: signature PK\x05\x06
        // 4..5: disk number (2 byte)
        // 6..7: disk with CD start (2 byte)
        // 8..9: number of CD entries on this disk (2 byte)
        // 10..11: total number of CD entries (2 byte)
        // 12..15: size of central directory (4 byte)
        // 16..19: offset of start of CD (4 byte)
        // 20..21: comment length (2 byte, n)
        // 22..(22+n-1): comment bytes
        stream.mark(65536 + 22)
        val buf = ByteArray(22)
        val bytesRead = readFully(stream, buf)
        if (bytesRead < 22) {
            try { stream.reset() } catch (_: Exception) {}
            return false
        }

        if (!buf.copyOfRange(0, 4).contentEquals(ZIP_EOCD_HEADER)) {
            try { stream.reset() } catch (_: Exception) {}
            return false
        }

        val diskNum = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
        val cdDisk = (buf[6].toInt() and 0xFF) or ((buf[7].toInt() and 0xFF) shl 8)
        val entriesOnDisk = (buf[8].toInt() and 0xFF) or ((buf[9].toInt() and 0xFF) shl 8)
        val totalEntries = (buf[10].toInt() and 0xFF) or ((buf[11].toInt() and 0xFF) shl 8)
        val cdSize = (buf[12].toInt() and 0xFF) or
                ((buf[13].toInt() and 0xFF) shl 8) or
                ((buf[14].toInt() and 0xFF) shl 16) or
                ((buf[15].toInt() and 0xFF) shl 24)
        val cdOffset = (buf[16].toInt() and 0xFF) or
                ((buf[17].toInt() and 0xFF) shl 8) or
                ((buf[18].toInt() and 0xFF) shl 16) or
                ((buf[19].toInt() and 0xFF) shl 24)
        val commentLength = (buf[20].toInt() and 0xFF) or ((buf[21].toInt() and 0xFF) shl 8)

        // Pada empty ZIP standar: tidak ada partisi disk dan jumlah entri adalah 0
        if (diskNum != 0 || cdDisk != 0 || entriesOnDisk != 0 || totalEntries != 0 || cdSize != 0 || cdOffset != 0) {
            try { stream.reset() } catch (_: Exception) {}
            return false
        }

        // Pastikan komentar (jika ada) terbaca utuh sampai tepat EOF
        var skippedComment = 0L
        while (skippedComment < commentLength) {
            val skip = stream.skip(commentLength - skippedComment)
            if (skip <= 0) {
                if (stream.read() == -1) break
                skippedComment++
            } else {
                skippedComment += skip
            }
        }

        val reachedEof = stream.read() == -1
        try { stream.reset() } catch (_: Exception) {}

        return skippedComment == commentLength.toLong() && reachedEof
    }

    private fun readAndCatalogZipStream(
        inputStream: InputStream,
        cbzRelativePath: String
    ): CbzCatalogResult {
        val rawCandidates = mutableListOf<RawEntry>()

        var rawEntryCount = 0

        ZipInputStream(inputStream).use { zis ->
            while (true) {
                val entry: ZipEntry = try {
                    zis.nextEntry ?: break
                } catch (ze: ZipException) {
                    return CbzCatalogResult.CorruptArchive(
                        cbzRelativePath = cbzRelativePath,
                        reason = "Arsip ZIP rusak saat membaca entri: ${ze.message}",
                        cause = ze
                    )
                } catch (ee: java.io.EOFException) {
                    return CbzCatalogResult.CorruptArchive(
                        cbzRelativePath = cbzRelativePath,
                        reason = "Arsip ZIP terpotong saat membaca entri: ${ee.message}",
                        cause = ee
                    )
                } catch (ioe: IOException) {
                    return CbzCatalogResult.Error(ioe)
                }

                rawEntryCount++
                val rawName = entry.name
                val normalizedPath = rawName.replace('\\', '/')

                val isIgnored = entry.isDirectory ||
                        normalizedPath.endsWith('/') ||
                        isIgnoredMetadataOrJunk(normalizedPath)

                if (!isIgnored && isSupportedImageExtension(normalizedPath)) {
                    val size = if (entry.size >= 0) entry.size else null
                    rawCandidates.add(RawEntry(normalizedPath, size))
                }

                // Tutup entri saat ini dan tangani error tanpa menelan exception
                try {
                    zis.closeEntry()
                } catch (ze: ZipException) {
                    return CbzCatalogResult.CorruptArchive(
                        cbzRelativePath = cbzRelativePath,
                        reason = "Arsip ZIP rusak saat menutup entri ($normalizedPath): ${ze.message}",
                        cause = ze
                    )
                } catch (ee: java.io.EOFException) {
                    return CbzCatalogResult.CorruptArchive(
                        cbzRelativePath = cbzRelativePath,
                        reason = "Arsip ZIP terpotong saat menutup entri ($normalizedPath): ${ee.message}",
                        cause = ee
                    )
                } catch (ioe: IOException) {
                    return CbzCatalogResult.Error(ioe)
                }
            }
        }

        if (rawEntryCount == 0) {
            return CbzCatalogResult.CorruptArchive(
                cbzRelativePath = cbzRelativePath,
                reason = "Arsip ZIP tidak memiliki entri valid atau rusak/terpotong"
            )
        }

        if (rawCandidates.isEmpty()) {
            return CbzCatalogResult.Empty(cbzRelativePath)
        }

        // Urutkan kandidat gambar menggunakan NaturalOrderComparator
        val sortedEntries = rawCandidates.sortedWith { e1, e2 ->
            NaturalOrderComparator.compare(e1.path, e2.path)
        }

        // Susun entri halaman dengan penomoran 1-based yang deterministik
        val pages = sortedEntries.mapIndexed { index, raw ->
            CbzPageEntry(
                entryPath = raw.path,
                pageNumber = index + 1,
                sizeBytes = raw.sizeBytes
            )
        }

        return CbzCatalogResult.Success(
            cbzRelativePath = cbzRelativePath,
            pages = pages
        )
    }

    private data class RawEntry(
        val path: String,
        val sizeBytes: Long?
    )
}

