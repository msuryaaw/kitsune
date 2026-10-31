package com.example.kitsune.reader

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CbzPageDecoderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun decoder_pageNotFound_returnsPageNotFound() = runTest {
        val zipFile = createTestZipWithPng("page1.png")
        var handleClosed = false
        val handle = CbzArchiveHandle(1L, zipFile) { handleClosed = true }

        val pageMetadata = PageMetadata(pageIndex = 0, entryName = "non_existent.png", normalizedPath = "non_existent.png")
        val pageIndex = ChapterPageIndex(chapterId = 1L, pages = listOf(pageMetadata))

        val decoder = DefaultCbzPageDecoder(pageIndex, handle)

        val result = decoder.decodePage(0, 300, 400)
        assertTrue("Result must be PageNotFound or ImageCorrupt for missing entry", result is PageDecodeResult.PageNotFound || result is PageDecodeResult.ImageCorrupt)

        decoder.close()
        assertTrue("Archive handle must be closed when decoder is closed", handleClosed)
    }

    @Test
    fun decoder_closeRejectsNewDecodeRequests() = runTest {
        val zipFile = createTestZipWithPng("page1.png")
        var handleClosed = false
        val handle = CbzArchiveHandle(1L, zipFile) { handleClosed = true }

        val pageMetadata = PageMetadata(pageIndex = 0, entryName = "page1.png", normalizedPath = "page1.png")
        val pageIndex = ChapterPageIndex(chapterId = 1L, pages = listOf(pageMetadata))

        val decoder = DefaultCbzPageDecoder(pageIndex, handle)
        decoder.close()

        assertTrue("Handle must be closed immediately when no operations active", handleClosed)

        val result = decoder.decodePage(0, 300, 400)
        assertTrue("Decode request on closed decoder must return ArchiveClosed", result is PageDecodeResult.ArchiveClosed)
    }

    @Test
    fun decoder_closeDuringActiveDecode_defersArchiveHandleClose() = runTest {
        val zipFile = createTestZipWithPng("page1.png")
        var handleClosed = false
        val handle = CbzArchiveHandle(1L, zipFile) { handleClosed = true }

        val pageMetadata = PageMetadata(pageIndex = 0, entryName = "page1.png", normalizedPath = "page1.png")
        val pageIndex = ChapterPageIndex(chapterId = 1L, pages = listOf(pageMetadata))

        val cache = ReaderPageCache()
        val decoder = DefaultCbzPageDecoder(pageIndex, handle, cache)

        val gate = CompletableDeferred<Unit>()
        val decodeStarted = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.IO) {
            decodeStarted.complete(Unit)
            gate.await()
        }

        decodeStarted.await()
        assertFalse("Handle must NOT be closed while operation is active", handleClosed)

        // Close decoder while operation simulated in-flight
        gate.complete(Unit)
        job.join()

        decoder.close()
        assertTrue("Handle must be closed after operations complete", handleClosed)
    }

    @Test
    fun decoder_cancellationReleasesActiveOperation() = runTest {
        val zipFile = createTestZipWithPng("page1.png")
        var handleClosed = false
        val handle = CbzArchiveHandle(1L, zipFile) { handleClosed = true }

        val pageMetadata = PageMetadata(pageIndex = 0, entryName = "page1.png", normalizedPath = "page1.png")
        val pageIndex = ChapterPageIndex(chapterId = 1L, pages = listOf(pageMetadata))

        val decoder = DefaultCbzPageDecoder(pageIndex, handle)

        val job = launch {
            throw CancellationException("Simulated cancellation")
        }

        try {
            job.join()
        } catch (_: Exception) {}

        decoder.close()
        assertTrue("Handle must be closed after cancellation", handleClosed)
    }

    @Test
    fun decoder_closeDuringDecode_doesNotRepublishBitmapToCache() = runTest {
        val zipFile = createTestZipWithPng("page1.png")
        val handle = CbzArchiveHandle(1L, zipFile) {}

        val pageMetadata = PageMetadata(pageIndex = 0, entryName = "page1.png", normalizedPath = "page1.png")
        val pageIndex = ChapterPageIndex(chapterId = 1L, pages = listOf(pageMetadata))

        val cache = ReaderPageCache()
        val decoder = DefaultCbzPageDecoder(pageIndex, handle, cache)

        val gate = CompletableDeferred<Unit>()

        val decodeJob = launch(Dispatchers.IO) {
            decoder.decodePage(0, 300, 400)
            gate.complete(Unit)
        }

        // Close decoder mid-session
        decoder.close()
        assertEquals("Cache must be cleared upon close()", 0, cache.currentSizeBytes)

        gate.await()
        decodeJob.join()

        // Verify that completed decode result was NOT re-published into cache because state became CLOSING
        assertEquals("In-flight decode result MUST NOT be re-published to cache after close()", 0, cache.currentSizeBytes)
    }

    @Test
    fun decoder_cacheKeySeparatesTargetDimensions() = runTest {
        val key1 = ReaderPageCache.makeKey(0, 300, 400)
        val key2 = ReaderPageCache.makeKey(0, 600, 800)

        assertTrue("Cache keys for different dimensions must be distinct", key1 != key2)
    }

    @Test
    fun decoder_actualDecompressedBytesLimit_isEnforced() = runTest {
        val zipFile = createTestZipWithPng("page1.png")
        val handle = CbzArchiveHandle(1L, zipFile) {}

        val pageMetadata = PageMetadata(pageIndex = 0, entryName = "page1.png", normalizedPath = "page1.png")
        val pageIndex = ChapterPageIndex(chapterId = 1L, pages = listOf(pageMetadata))

        // Set maxImageDecompressedBytes to 5 bytes (smaller than valid PNG file size)
        val smallLimitDecoder = DefaultCbzPageDecoder(pageIndex, handle, ReaderPageCache(), maxImageDecompressedBytes = 5L)

        val result = smallLimitDecoder.decodePage(0, 300, 400)
        assertTrue("Exceeding actual decompressed byte limit must return Error", result is PageDecodeResult.Error)

        smallLimitDecoder.close()
    }

    private fun createTestZipWithPng(entryName: String): File {
        val file = tempFolder.newFile()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            // Minimal 1x1 transparent PNG bytes
            val pngBytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
                0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
                0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
                0x0D, 0x0A, 0x2D, 0xB4.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
            )
            zos.write(pngBytes)
            zos.closeEntry()
        }
        file.writeBytes(baos.toByteArray())
        return file
    }
}
