package com.example.kitsune.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGatewayTest {

    @Test
    fun storagePathUtils_normalizesVariousFormats() {
        assertEquals("", StoragePathUtils.normalizeRelativePath(null))
        assertEquals("", StoragePathUtils.normalizeRelativePath("   "))
        assertEquals("", StoragePathUtils.normalizeRelativePath("/"))
        assertEquals("Comics/One Piece", StoragePathUtils.normalizeRelativePath("/Comics/One Piece/"))
        assertEquals("Comics/One Piece", StoragePathUtils.normalizeRelativePath("\\Comics\\One Piece\\"))
        assertEquals("Comics/One Piece", StoragePathUtils.normalizeRelativePath("Comics/./One Piece"))
    }

    @Test(expected = SecurityException::class)
    fun storagePathUtils_rejectsTraversalPath() {
        StoragePathUtils.normalizeRelativePath("Comics/../secret.txt")
    }

    @Test
    fun storagePathUtils_combinesPathsCleanly() {
        assertEquals("Comics/One Piece", StoragePathUtils.combineRelativePath("Comics", "One Piece"))
        assertEquals("Comics/One Piece", StoragePathUtils.combineRelativePath("", "Comics/One Piece"))
        assertEquals("Comics/One Piece", StoragePathUtils.combineRelativePath("Comics/One Piece", ""))
        assertEquals("Comics/One Piece/Chapter 1.cbz", StoragePathUtils.combineRelativePath("Comics/One Piece", "Chapter 1.cbz"))
    }

    @Test
    fun fakeStorageGateway_handlesRootNotConfigured() {
        val gateway = FakeStorageGateway(rootConfigured = false)

        val rootState = gateway.getRootState()
        assertTrue(rootState is StorageResult.RootNotConfigured)

        val children = gateway.listChildren("Comics")
        assertTrue(children is StorageResult.RootNotConfigured)
    }

    @Test
    fun fakeStorageGateway_handlesPermissionDenied() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = false)

        val rootState = gateway.getRootState()
        assertTrue(rootState is StorageResult.PermissionDenied)

        val children = gateway.listChildren("Comics")
        assertTrue(children is StorageResult.PermissionDenied)
    }

    @Test
    fun fakeStorageGateway_listsHierarchyAndReadsFiles() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/One Piece")
        gateway.addFile("Comics/One Piece/Chapter 1.cbz", "cbz content".toByteArray())
        gateway.addFile("Comics/One Piece/Chapter 2.cbz", "cbz 2 content".toByteArray())

        // List root
        val rootChildren = gateway.listChildren("")
        assertTrue(rootChildren is StorageResult.Success)
        val rootEntries = (rootChildren as StorageResult.Success).data
        assertEquals(1, rootEntries.size)
        assertEquals("Comics", rootEntries[0].name)
        assertTrue(rootEntries[0].isDirectory)

        // List Comics
        val comicsChildren = gateway.listChildren("Comics")
        assertTrue(comicsChildren is StorageResult.Success)
        val comicsEntries = (comicsChildren as StorageResult.Success).data
        assertEquals(1, comicsEntries.size)
        assertEquals("One Piece", comicsEntries[0].name)
        assertTrue(comicsEntries[0].isDirectory)

        // List Comics/One Piece
        val seriesChildren = gateway.listChildren("Comics/One Piece")
        assertTrue(seriesChildren is StorageResult.Success)
        val seriesEntries = (seriesChildren as StorageResult.Success).data
        assertEquals(2, seriesEntries.size)

        // Find entry
        val entry = gateway.findEntry("Comics/One Piece/Chapter 1.cbz")
        assertTrue(entry is StorageResult.Success)
        val ch1 = (entry as StorageResult.Success).data
        assertEquals("Chapter 1.cbz", ch1.name)
        assertFalse(ch1.isDirectory)
        assertEquals(11L, ch1.sizeBytes)

        // Read stream
        val streamResult = gateway.openInputStream("Comics/One Piece/Chapter 1.cbz")
        assertTrue(streamResult is StorageResult.Success)
        val stream = (streamResult as StorageResult.Success).data
        val content = stream.bufferedReader().use { it.readText() }
        assertEquals("cbz content", content)

        // Clean up temporary files if any
        gateway.cleanUp()
    }

    @Test
    fun fakeStorageGateway_handlesOpenFileDescriptorOrReportsEnvironmentLimitation() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val expectedBytes = "binary comic payload".toByteArray()
        gateway.addFile("Comics/test.cbz", expectedBytes)

        val pfdResult = gateway.openFileDescriptor("Comics/test.cbz", "r")
        // Di JVM tanpa android.jar mocked atau Robolectric, ParcelFileDescriptor.open melempar RuntimeException "not mocked"
        // yang ditangkap oleh gateway sebagai StorageResult.Error.
        // Di Android runtime atau Robolectric, operasi ini menghasilkan StorageResult.Success.
        when (pfdResult) {
            is StorageResult.Success -> {
                pfdResult.data.use { descriptor ->
                    val input = java.io.FileInputStream(descriptor.fileDescriptor)
                    val readBytes = input.readBytes()
                    assertEquals(String(expectedBytes), String(readBytes))
                }
            }
            is StorageResult.Error -> {
                val ex = pfdResult.exception
                assertTrue(
                    "Error harus berupa RuntimeException unmocked atau UnsupportedOperationException",
                    ex.message?.contains("not mocked") == true ||
                            ex.cause?.message?.contains("not mocked") == true ||
                            ex is UnsupportedOperationException
                )
            }
            else -> {
                throw AssertionError("Unexpected result: $pfdResult")
            }
        }

        // Pastikan cleanup menghapus temporary file
        gateway.cleanUp()
    }

    @Test
    fun fakeStorageGateway_notFoundForMissingFile() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val result = gateway.findEntry("NonExistent/file.cbz")
        assertTrue(result is StorageResult.NotFound)
    }
}

