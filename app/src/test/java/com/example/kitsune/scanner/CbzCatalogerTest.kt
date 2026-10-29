package com.example.kitsune.scanner

import com.example.kitsune.storage.FakeStorageGateway
import com.example.kitsune.storage.StorageEntry
import com.example.kitsune.storage.StorageGateway
import com.example.kitsune.storage.StorageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzCatalogerTest {

    private fun createZipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun catalogArchive_validArchive_indexesSupportedImagesInNaturalOrder() {
        val zipBytes = createZipBytes(
            "image_10.jpg" to "page 10".toByteArray(),
            "image_1.jpeg" to "page 1".toByteArray(),
            "image_2.PNG" to "page 2".toByteArray(),
            "image_20.webp" to "page 20".toByteArray(),
            "image_03.jpg" to "page 3".toByteArray()
        )

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/One Piece/Chapter 1.cbz"
        gateway.addFile(cbzPath, zipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue(result is CbzCatalogResult.Success)
        val success = result as CbzCatalogResult.Success
        assertEquals(cbzPath, success.cbzRelativePath)
        assertEquals(5, success.pages.size)

        // Verify natural sort order: 1, 2, 03, 10, 20
        assertEquals("image_1.jpeg", success.pages[0].entryPath)
        assertEquals(1, success.pages[0].pageNumber)

        assertEquals("image_2.PNG", success.pages[1].entryPath)
        assertEquals(2, success.pages[1].pageNumber)

        assertEquals("image_03.jpg", success.pages[2].entryPath)
        assertEquals(3, success.pages[2].pageNumber)

        assertEquals("image_10.jpg", success.pages[3].entryPath)
        assertEquals(4, success.pages[3].pageNumber)

        assertEquals("image_20.webp", success.pages[4].entryPath)
        assertEquals(5, success.pages[4].pageNumber)
    }

    @Test
    fun catalogArchive_ignoresDirectoriesNonImagesAndJunkMetadata() {
        val zipBytes = createZipBytes(
            "__MACOSX/._image_1.jpg" to "junk".toByteArray(),
            "subfolder/" to ByteArray(0),
            ".DS_Store" to "junk".toByteArray(),
            "thumbs.db" to "junk".toByteArray(),
            "metadata.xml" to "<xml></xml>".toByteArray(),
            "notes.txt" to "notes".toByteArray(),
            "script.sh" to "echo evil".toByteArray(),
            "pages/image_01.jpg" to "page 1".toByteArray(),
            "pages/image_02.png" to "page 2".toByteArray()
        )

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Bleach/Chapter 1.cbz"
        gateway.addFile(cbzPath, zipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue(result is CbzCatalogResult.Success)
        val success = result as CbzCatalogResult.Success
        assertEquals(2, success.pages.size)
        assertEquals("pages/image_01.jpg", success.pages[0].entryPath)
        assertEquals("pages/image_02.png", success.pages[1].entryPath)
    }

    @Test
    fun catalogArchive_validEmptyZipWithoutEntries_returnsEmpty() {
        // Valid empty ZIP created with ZipOutputStream closing without entries
        val emptyZipBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).close()
        }.toByteArray()

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Naruto/EmptyZip.cbz"
        gateway.addFile(cbzPath, emptyZipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue(result is CbzCatalogResult.Empty)
        assertEquals(cbzPath, (result as CbzCatalogResult.Empty).cbzRelativePath)
    }

    @Test
    fun catalogArchive_validZipWithoutImages_returnsEmpty() {
        // Valid ZIP containing only non-image entries
        val zipBytes = createZipBytes(
            "readme.txt" to "hello".toByteArray(),
            "info.json" to "{}".toByteArray()
        )

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Naruto/NoImages.cbz"
        gateway.addFile(cbzPath, zipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue(result is CbzCatalogResult.Empty)
        assertEquals(cbzPath, (result as CbzCatalogResult.Empty).cbzRelativePath)
    }

    @Test
    fun catalogArchive_truncatedEocdExactFourBytes_returnsCorruptArchive() {
        // Exactly 4 bytes with EOCD signature PK\x05\x06 (incomplete EOCD record)
        val fourBytes = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Naruto/TruncatedEocd4Bytes.cbz"
        gateway.addFile(cbzPath, fourBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue(result is CbzCatalogResult.CorruptArchive)
        val corrupt = result as CbzCatalogResult.CorruptArchive
        assertEquals(cbzPath, corrupt.cbzRelativePath)
        assertTrue(corrupt.reason.contains("EOCD terpotong"))
    }

    @Test
    fun catalogArchive_truncatedEocdLessThan22Bytes_returnsCorruptArchive() {
        // 10 bytes starting with PK\x05\x06 (less than 22 bytes minimum EOCD)
        val tenBytes = byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0)

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Naruto/TruncatedEocd10Bytes.cbz"
        gateway.addFile(cbzPath, tenBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue(result is CbzCatalogResult.CorruptArchive)
    }

    @Test
    fun catalogArchive_nonZipRandomBytes_strictlyReturnsCorruptArchive() {
        val nonZipBytes = "Random uncompressed text that is definitely not a zip file".toByteArray()

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Naruto/Chapter 2.cbz"
        gateway.addFile(cbzPath, nonZipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        // Print actual result to diagnose
        if (result !is CbzCatalogResult.CorruptArchive) {
            throw AssertionError("Expected CorruptArchive but was $result")
        }
        assertTrue(result is CbzCatalogResult.CorruptArchive)
        val corrupt = result as CbzCatalogResult.CorruptArchive
        assertEquals(cbzPath, corrupt.cbzRelativePath)
        assertTrue(corrupt.reason.contains("Bukan file ZIP yang valid"))
    }

    @Test
    fun catalogArchive_truncatedZipHeader_returnsCorruptArchive() {
        val truncatedHeader = byteArrayOf(0x50, 0x4B) // Only 2 bytes, insufficient for 4-byte header

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Naruto/Truncated.cbz"
        gateway.addFile(cbzPath, truncatedHeader)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        // Print actual result to diagnose
        if (result !is CbzCatalogResult.CorruptArchive) {
            throw AssertionError("Expected CorruptArchive but was $result")
        }
        assertTrue(result is CbzCatalogResult.CorruptArchive)
    }

    @Test
    fun catalogArchive_validZipHeaderWithCorruptedEntry_returnsCorruptArchive() {
        // Create a valid zip with an image entry, then truncate it halfway through the local entry data/header
        val validZip = createZipBytes(
            "image_1.jpg" to "page 1 data".toByteArray()
        )
        // Cut the valid zip in the middle (e.g. keep first 25 bytes out of 100+ bytes)
        val truncatedZipBytes = validZip.copyOfRange(0, 25)

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Naruto/BrokenEntry.cbz"
        gateway.addFile(cbzPath, truncatedZipBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        assertTrue(result is CbzCatalogResult.CorruptArchive)
    }

    @Test
    fun catalogArchive_completeLocalHeaderWithTruncatedPayload_strictlyReturnsCorruptArchive() {
        // Create a zip with complete local header (30 + filename = 41 bytes) but payload is truncated
        val validZip = createZipBytes(
            "page_01.jpg" to ("sample image payload byte stream repeated ".repeat(10)).toByteArray()
        )
        // Local header is ~41 bytes. Cut at 50 bytes so header is complete but payload is truncated.
        val truncatedPayloadBytes = validZip.copyOfRange(0, 50)

        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cbzPath = "Comics/Naruto/TruncatedPayload.cbz"
        gateway.addFile(cbzPath, truncatedPayloadBytes)

        val cataloger = DefaultCbzCataloger(gateway)
        val result = cataloger.catalogArchive(cbzPath)

        // When closeEntry() consumes/skips the remaining compressed payload of a truncated stream,
        // it encounters unexpected EOF and throws EOFException (or ZipException), which is strictly classified
        // as CorruptArchive because the archive stream itself is prematurely terminated / truncated.
        assertTrue(result is CbzCatalogResult.CorruptArchive)
        val corrupt = result as CbzCatalogResult.CorruptArchive
        assertEquals(cbzPath, corrupt.cbzRelativePath)
        assertTrue(corrupt.reason.contains("terpotong"))
    }

    @Test
    fun catalogArchive_ioExceptionDuringCloseEntry_returnsError() {
        // Stream where first entry header can be read, but reading entry contents/skipping during closeEntry() fails with IOException
        val failingCloseGateway = object : StorageGateway {
            override fun getRootState() = StorageResult.Success("content://root")
            override fun listChildren(parentRelativePath: String) = StorageResult.Success(emptyList<StorageEntry>())
            override fun findEntry(relativePath: String) = StorageResult.NotFound(relativePath)
            override fun openInputStream(relativePath: String): StorageResult<java.io.InputStream> {
                val validZip = createZipBytes(
                    "image_01.jpg" to "test image content".toByteArray()
                )
                // Serve first 40 bytes (includes header and entry name), then throw IOException when closeEntry tries to drain
                val stream = object : java.io.InputStream() {
                    private var index = 0
                    override fun read(): Int {
                        if (index < 40 && index < validZip.size) {
                            return validZip[index++].toInt() and 0xFF
                        }
                        throw IOException("I/O failure while draining entry in closeEntry")
                    }
                }
                return StorageResult.Success(stream)
            }
            override fun openFileDescriptor(relativePath: String, mode: String) = StorageResult.NotFound(relativePath)
        }

        val cataloger = DefaultCbzCataloger(failingCloseGateway)
        val result = cataloger.catalogArchive("Comics/Naruto/CloseEntryFail.cbz")

        assertTrue(result is CbzCatalogResult.Error)
        val err = result as CbzCatalogResult.Error
        assertTrue(err.exception is IOException)
        assertEquals("I/O failure while draining entry in closeEntry", err.exception.message)
    }

    @Test
    fun catalogArchive_ioFailureWhileReadingStream_returnsError() {
        // Stream whose header is valid but throws IOException during iteration
        val failingStreamGateway = object : StorageGateway {
            override fun getRootState() = StorageResult.Success("content://root")
            override fun listChildren(parentRelativePath: String) = StorageResult.Success(emptyList<StorageEntry>())
            override fun findEntry(relativePath: String) = StorageResult.NotFound(relativePath)
            override fun openInputStream(relativePath: String): StorageResult<java.io.InputStream> {
                val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
                val stream = object : java.io.InputStream() {
                    private var index = 0
                    override fun read(): Int {
                        if (index < header.size) {
                            return header[index++].toInt() and 0xFF
                        }
                        throw IOException("Connection reset / disk read error mid-stream")
                    }
                }
                return StorageResult.Success(stream)
            }
            override fun openFileDescriptor(relativePath: String, mode: String) = StorageResult.NotFound(relativePath)
        }

        val cataloger = DefaultCbzCataloger(failingStreamGateway)
        val result = cataloger.catalogArchive("Comics/Naruto/MidStreamFail.cbz")

        assertTrue(result is CbzCatalogResult.Error)
        val err = result as CbzCatalogResult.Error
        assertTrue(err.exception is IOException)
        assertEquals("Connection reset / disk read error mid-stream", err.exception.message)
    }

    @Test
    fun catalogArchive_fileNotFound_returnsNotFound() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val cataloger = DefaultCbzCataloger(gateway)

        val result = cataloger.catalogArchive("Comics/Missing/Chapter 1.cbz")
        assertTrue(result is CbzCatalogResult.NotFound)
    }

    @Test
    fun catalogArchive_rootNotConfigured_returnsRootNotConfigured() {
        val gateway = FakeStorageGateway(rootConfigured = false)
        val cataloger = DefaultCbzCataloger(gateway)

        val result = cataloger.catalogArchive("Comics/Test/Chapter 1.cbz")
        assertTrue(result is CbzCatalogResult.RootNotConfigured)
    }

    @Test
    fun catalogArchive_permissionDenied_returnsPermissionDenied() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = false)
        val cataloger = DefaultCbzCataloger(gateway)

        val result = cataloger.catalogArchive("Comics/Test/Chapter 1.cbz")
        assertTrue(result is CbzCatalogResult.PermissionDenied)
    }

    @Test
    fun catalogArchive_handlesStorageIoErrorExplicitly() {
        val errorGateway = object : StorageGateway {
            override fun getRootState() = StorageResult.Success("content://root")
            override fun listChildren(parentRelativePath: String) = StorageResult.Success(emptyList<StorageEntry>())
            override fun findEntry(relativePath: String) = StorageResult.NotFound(relativePath)
            override fun openInputStream(relativePath: String) =
                StorageResult.Error(IOException("I/O failure reading file stream"))
            override fun openFileDescriptor(relativePath: String, mode: String) = StorageResult.NotFound(relativePath)
        }

        val cataloger = DefaultCbzCataloger(errorGateway)
        val result = cataloger.catalogArchive("Comics/Test/Chapter 1.cbz")

        assertTrue(result is CbzCatalogResult.Error)
        val err = result as CbzCatalogResult.Error
        assertTrue(err.exception is IOException)
        assertEquals("I/O failure reading file stream", err.exception.message)
    }
}
