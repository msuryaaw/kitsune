package com.example.kitsune.scanner

import com.example.kitsune.storage.FakeStorageGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicsDirectoryDetectorTest {

    @Test
    fun detectComicsDirectory_rootNotConfigured_returnsRootNotConfigured() {
        val gateway = FakeStorageGateway(rootConfigured = false)
        val detector = DefaultComicsDirectoryDetector(gateway)

        val state = detector.detectComicsDirectory()
        assertTrue(state is ComicsDirectoryState.RootNotConfigured)
    }

    @Test
    fun detectComicsDirectory_permissionDenied_returnsPermissionDenied() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = false)
        val detector = DefaultComicsDirectoryDetector(gateway)

        val state = detector.detectComicsDirectory()
        assertTrue(state is ComicsDirectoryState.PermissionDenied)
    }

    @Test
    fun detectComicsDirectory_comicsFolderMissing_returnsDirectoryNotFound() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        // Root ada tetapi tidak ada folder Comics
        gateway.addDirectory("OtherFolder")
        val detector = DefaultComicsDirectoryDetector(gateway)

        val state = detector.detectComicsDirectory()
        assertTrue(state is ComicsDirectoryState.DirectoryNotFound)
    }

    @Test
    fun detectComicsDirectory_comicsIsAFile_returnsNotADirectory() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        // Entry bernama Comics tapi berbentuk file, bukan direktori
        gateway.addFile("Comics", "file content".toByteArray())
        val detector = DefaultComicsDirectoryDetector(gateway)

        val state = detector.detectComicsDirectory()
        assertTrue(state is ComicsDirectoryState.NotADirectory)
    }

    @Test
    fun detectComicsDirectory_comicsFolderIsEmpty_returnsEmpty() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        val detector = DefaultComicsDirectoryDetector(gateway)

        val state = detector.detectComicsDirectory()
        assertTrue(state is ComicsDirectoryState.Empty)
        val emptyState = state as ComicsDirectoryState.Empty
        assertEquals("Comics", emptyState.entry.name)
        assertTrue(emptyState.entry.isDirectory)
    }

    @Test
    fun detectComicsDirectory_comicsFolderHasChildren_returnsReadyWithCount() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/One Piece")
        gateway.addDirectory("Comics/Bleach")
        val detector = DefaultComicsDirectoryDetector(gateway)

        val state = detector.detectComicsDirectory()
        assertTrue(state is ComicsDirectoryState.Ready)
        val readyState = state as ComicsDirectoryState.Ready
        assertEquals("Comics", readyState.entry.name)
        assertEquals(2, readyState.childCount)
    }

    @Test
    fun detectComicsDirectory_caseInsensitiveDetection_findsLowerOrMixedCase() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("comics")
        gateway.addDirectory("comics/Naruto")
        val detector = DefaultComicsDirectoryDetector(gateway)

        val state = detector.detectComicsDirectory()
        assertTrue(state is ComicsDirectoryState.Ready)
        val readyState = state as ComicsDirectoryState.Ready
        assertEquals("comics", readyState.entry.name)
        assertEquals(1, readyState.childCount)
    }
}

