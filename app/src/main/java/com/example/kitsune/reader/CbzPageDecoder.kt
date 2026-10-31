package com.example.kitsune.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * Contract for decoding page bitmaps from a spooled CBZ archive on demand.
 */
interface CbzPageDecoder : Closeable {
    val pageIndex: ChapterPageIndex

    /**
     * Decodes the bitmap for [pageIndex] downsampled to [targetWidth] x [targetHeight].
     */
    suspend fun decodePage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int
    ): PageDecodeResult
}

/**
 * Factory for creating [CbzPageDecoder] instances per chapter session.
 */
interface CbzPageDecoderFactory {
    fun createDecoder(pageIndex: ChapterPageIndex, archiveHandle: CbzArchiveHandle): CbzPageDecoder
}

/**
 * Default implementation of [CbzPageDecoderFactory].
 */
class DefaultCbzPageDecoderFactory : CbzPageDecoderFactory {
    override fun createDecoder(
        pageIndex: ChapterPageIndex,
        archiveHandle: CbzArchiveHandle
    ): CbzPageDecoder {
        return DefaultCbzPageDecoder(pageIndex, archiveHandle)
    }
}

/**
 * Default implementation of [CbzPageDecoder] with thread-safe lifecycle state,
 * active operation counter, memory-safe streaming byte check, and downsampled bitmap decode.
 */
class DefaultCbzPageDecoder(
    override val pageIndex: ChapterPageIndex,
    private val archiveHandle: CbzArchiveHandle,
    private val pageCache: ReaderPageCache = ReaderPageCache(),
    private val maxImageDecompressedBytes: Long = MAX_IMAGE_DECOMPRESSED_BYTES
) : CbzPageDecoder {

    private enum class State { OPEN, CLOSING, CLOSED }

    @Volatile
    private var state = State.OPEN

    private val stateLock = Any()
    private val activeOpsCounter = AtomicInteger(0)
    private val handleClosed = AtomicBoolean(false)

    companion object {
        /** Maximum allowed target dimension for decoded bitmap side length (2048 px). */
        const val MAX_DECODE_DIMENSION = 2048

        /** Maximum allowed decompressed byte limit for a single image entry (100 MiB). */
        const val MAX_IMAGE_DECOMPRESSED_BYTES = 100L * 1024L * 1024L

        fun calculateInSampleSize(
            options: BitmapFactory.Options,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }
    }

    override suspend fun decodePage(
        pageIndex: Int,
        targetWidth: Int,
        targetHeight: Int
    ): PageDecodeResult = withContext(Dispatchers.IO) {
        val isAlreadyClosed = synchronized(stateLock) {
            state != State.OPEN
        }
        if (isAlreadyClosed) {
            return@withContext PageDecodeResult.ArchiveClosed
        }

        val metadata = this@DefaultCbzPageDecoder.pageIndex.pages.firstOrNull { it.pageIndex == pageIndex }
            ?: return@withContext PageDecodeResult.PageNotFound(pageIndex)

        val safeWidth = targetWidth.coerceIn(1, MAX_DECODE_DIMENSION)
        val safeHeight = targetHeight.coerceIn(1, MAX_DECODE_DIMENSION)

        // Check memory-bounded cache first
        pageCache.get(pageIndex, safeWidth, safeHeight)?.let { cachedBitmap ->
            return@withContext PageDecodeResult.Success(
                pageIndex = pageIndex,
                bitmap = cachedBitmap,
                width = cachedBitmap.width,
                height = cachedBitmap.height
            )
        }

        // Increment active operations counter
        activeOpsCounter.incrementAndGet()

        try {
            val isClosedAfterIncrement = synchronized(stateLock) {
                state != State.OPEN
            }
            if (isClosedAfterIncrement) {
                return@withContext PageDecodeResult.ArchiveClosed
            }

            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            val archiveFile = archiveHandle.file
            if (!archiveFile.exists()) {
                return@withContext PageDecodeResult.Error(
                    IllegalStateException("File spooled archive tidak ditemukan di disk")
                )
            }

            val entryName = metadata.entryName

            val decodedResult = ZipFile(archiveFile).use { zip ->
                val entry = zip.getEntry(entryName)
                    ?: return@use PageDecodeResult.PageNotFound(pageIndex)

                // 1. Decode bounds first
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                zip.getInputStream(entry).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return@use PageDecodeResult.ImageCorrupt(
                        pageIndex = pageIndex,
                        message = "Header gambar $entryName tidak valid atau dimensi <= 0"
                    )
                }

                options.inSampleSize = calculateInSampleSize(options, safeWidth, safeHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.ARGB_8888

                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                // 2. Decode pixel bitmap with streaming byte limit check
                val rawStream = zip.getInputStream(entry)
                val boundedStream = BoundedInputStream(rawStream, maxImageDecompressedBytes)

                val bitmap = boundedStream.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                if (boundedStream.exceededLimit) {
                    return@use PageDecodeResult.Error(
                        IllegalStateException("Ukuran byte gambar $entryName melebihi batas $maxImageDecompressedBytes bytes")
                    )
                }

                if (bitmap == null) {
                    return@use PageDecodeResult.ImageCorrupt(
                        pageIndex = pageIndex,
                        message = "Gagal mendecode pixel bitmap untuk $entryName"
                    )
                }

                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                PageDecodeResult.Success(
                    pageIndex = pageIndex,
                    bitmap = bitmap,
                    width = bitmap.width,
                    height = bitmap.height
                )
            }

            if (decodedResult is PageDecodeResult.Success) {
                synchronized(stateLock) {
                    if (state == State.OPEN) {
                        pageCache.put(pageIndex, safeWidth, safeHeight, decodedResult.bitmap)
                    }
                }
            }

            decodedResult
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            PageDecodeResult.Error(e)
        } finally {
            val remaining = activeOpsCounter.decrementAndGet()
            if (state != State.OPEN && remaining == 0) {
                closeArchiveHandle()
            }
        }
    }

    override fun close() {
        var shouldCloseHandle = false
        synchronized(stateLock) {
            if (state == State.OPEN) {
                state = State.CLOSING
                pageCache.clear()
                if (activeOpsCounter.get() == 0) {
                    shouldCloseHandle = true
                }
            }
        }
        if (shouldCloseHandle) {
            closeArchiveHandle()
        }
    }

    private fun closeArchiveHandle() {
        state = State.CLOSED
        if (handleClosed.compareAndSet(false, true)) {
            try {
                archiveHandle.close()
            } catch (_: Exception) {}
        }
    }

    val isClosed: Boolean get() = state == State.CLOSED

    private class BoundedInputStream(
        private val delegate: InputStream,
        private val maxBytes: Long
    ) : InputStream() {
        private var bytesRead = 0L
        var exceededLimit = false
            private set

        override fun read(): Int {
            if (bytesRead >= maxBytes) {
                exceededLimit = true
                return -1
            }
            val b = delegate.read()
            if (b != -1) {
                bytesRead++
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= maxBytes) {
                exceededLimit = true
                return -1
            }
            val maxToRead = minOf(len.toLong(), maxBytes - bytesRead).toInt()
            val count = delegate.read(b, off, maxToRead)
            if (count > 0) {
                bytesRead += count
            }
            return count
        }
    }
}
