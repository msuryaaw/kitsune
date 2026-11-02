package com.example.kitsune.scanner

import com.example.kitsune.storage.FakeStorageGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicFolderScannerTest {

    @Test
    fun discoverComicFolders_rootNotConfigured_returnsRootNotConfigured() {
        val gateway = FakeStorageGateway(rootConfigured = false)
        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.RootNotConfigured)
    }

    @Test
    fun discoverComicFolders_permissionDenied_returnsPermissionDenied() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = false)
        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.PermissionDenied)
    }

    @Test
    fun discoverComicFolders_comicsDirectoryMissing_returnsComicsDirectoryNotFound() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("OtherDirectory")
        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.ComicsDirectoryNotFound)
    }

    @Test
    fun discoverComicFolders_comicsDirectoryEmpty_returnsEmptySuccess() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Success)
        val folders = (result as ComicFolderDiscoveryResult.Success).comicFolders
        assertTrue(folders.isEmpty())
    }

    @Test
    fun discoverComicFolders_identifiesOnlyDirectDirectoriesAndDerivesTitle() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/One Piece")
        gateway.addDirectory("Comics/Bleach")
        // File langsung di dalam Comics (bukan direktori) harus diabaikan
        gateway.addFile("Comics/notes.txt", "ignore me".toByteArray())
        gateway.addFile("Comics/.nomedia", ByteArray(0))

        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Success)
        val folders = (result as ComicFolderDiscoveryResult.Success).comicFolders
        assertEquals(2, folders.size)

        val onePiece = folders.first { it.folderName == "One Piece" }
        assertEquals("Comics/One Piece", onePiece.relativePath)
        assertEquals("One Piece", onePiece.defaultTitle)
        assertTrue(onePiece.entry.isDirectory)

        val bleach = folders.first { it.folderName == "Bleach" }
        assertEquals("Comics/Bleach", bleach.relativePath)
        assertEquals("Bleach", bleach.defaultTitle)
        assertTrue(bleach.entry.isDirectory)
    }

    @Test
    fun discoverComicFolders_doesNotRecurseIntoNestedDirectories() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/Naruto")
        // Nested subdirektori di dalam folder komik (misalnya subfolder yang tidak diizinkan atau aset)
        gateway.addDirectory("Comics/Naruto/Extra")
        gateway.addDirectory("Comics/Naruto/Extra/SubExtra")

        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Success)
        val folders = (result as ComicFolderDiscoveryResult.Success).comicFolders

        // Hanya Naruto yang boleh terdeteksi sebagai kandidat folder komik langsung
        assertEquals(1, folders.size)
        assertEquals("Naruto", folders[0].folderName)
        assertEquals("Comics/Naruto", folders[0].relativePath)
    }

    @Test
    fun discoverComicFolders_derivesCleanTitlePreservingOriginalName() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/Fullmetal Alchemist")

        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Success)
        val folders = (result as ComicFolderDiscoveryResult.Success).comicFolders
        assertEquals(1, folders.size)

        assertEquals("Fullmetal Alchemist", folders[0].defaultTitle)
        assertEquals("Fullmetal Alchemist", folders[0].folderName)
        assertEquals("Comics/Fullmetal Alchemist", folders[0].relativePath)
    }

    @Test
    fun discoverComicFolders_handlesStorageIoErrorExplicitly() {
        // Simulasi error storage I/O
        val errorGateway = object : com.example.kitsune.storage.StorageGateway {
            override fun getRootState() = com.example.kitsune.storage.StorageResult.Success("content://root")
            override fun listChildren(parentRelativePath: String): com.example.kitsune.storage.StorageResult<List<com.example.kitsune.storage.StorageEntry>> {
                return if (parentRelativePath.isEmpty()) {
                    com.example.kitsune.storage.StorageResult.Success(
                        listOf(
                            com.example.kitsune.storage.StorageEntry(
                                name = "Comics",
                                relativePath = "Comics",
                                isDirectory = true
                            )
                        )
                    )
                } else {
                    com.example.kitsune.storage.StorageResult.Error(java.io.IOException("Disk read error"))
                }
            }
            override fun findEntry(relativePath: String) = com.example.kitsune.storage.StorageResult.NotFound(relativePath)
            override fun openInputStream(relativePath: String) = com.example.kitsune.storage.StorageResult.NotFound(relativePath)
            override fun openFileDescriptor(relativePath: String, mode: String) = com.example.kitsune.storage.StorageResult.NotFound(relativePath)
            override fun createDirectory(parentRelativePath: String, directoryName: String) = com.example.kitsune.storage.StorageResult.Success(com.example.kitsune.storage.StorageEntry(directoryName, "$parentRelativePath/$directoryName", true))
            override fun openOutputStream(relativePath: String) = com.example.kitsune.storage.StorageResult.Success(java.io.ByteArrayOutputStream())
            override fun createFile(parentRelativePath: String, fileName: String, mimeType: String) = com.example.kitsune.storage.StorageResult.Success(com.example.kitsune.storage.StorageEntry(fileName, "$parentRelativePath/$fileName", false))
        }

        val detector = DefaultComicsDirectoryDetector(errorGateway)
        val scanner = DefaultComicFolderScanner(detector, errorGateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Error)
        val error = result as ComicFolderDiscoveryResult.Error
        assertTrue(error.exception is java.io.IOException)
        assertEquals("Disk read error", error.exception.message)
    }

    @Test
    fun discoverComicFolders_existingSidecarMetadata_isReadOnlyAndNotOverwritten() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/[Manga] [ID] [Oda] One Piece")
        val existingJson = """{"type": "CustomType", "language": "CustomLang", "author": "CustomAuthor"}"""
        gateway.addFile("Comics/[Manga] [ID] [Oda] One Piece/metadata.json", existingJson.toByteArray())

        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Success)
        val folders = (result as ComicFolderDiscoveryResult.Success).comicFolders
        assertEquals(1, folders.size)

        val folder = folders[0]
        assertEquals("One Piece", folder.defaultTitle)
        assertEquals("CustomType", folder.type)
        assertEquals("CustomLang", folder.language)
        assertEquals("CustomAuthor", folder.author)

        // ASSERTION: Existing sidecar file MUST NOT be created or overwritten!
        assertEquals("Existing sidecar MUST NOT trigger createFile", 0, gateway.createFileCalls)
        assertEquals("Existing sidecar MUST NOT trigger openOutputStream for writing", 0, gateway.openOutputStreamCalls)
    }

    @Test
    fun discoverComicFolders_existingSidecarPartialMetadata_usesSidecarPriorityWithFolderAuthorFallback() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/[Manga] [ID] [Oda] One Piece")
        // Existing sidecar has type and language, but missing author
        val partialJson = """{"type": "ComicType", "language": "ID"}"""
        gateway.addFile("Comics/[Manga] [ID] [Oda] One Piece/metadata.json", partialJson.toByteArray())

        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Success)
        val folder = (result as ComicFolderDiscoveryResult.Success).comicFolders.first()

        assertEquals("ComicType", folder.type)
        assertEquals("ID", folder.language)
        assertEquals("Oda", folder.author) // Fallback for missing author field from folder name!

        assertEquals("Existing sidecar MUST NOT be overwritten during fallback", 0, gateway.createFileCalls)
        assertEquals("Existing sidecar MUST NOT be rewritten during fallback", 0, gateway.openOutputStreamCalls)
    }

    @Test
    fun discoverComicFolders_missingSidecarCanonicalFolder_createsNewSidecarWithAuthor() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/[Manga] [ID] [Oda] One Piece")
        // No metadata.json exists initially

        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Success)
        val folder = (result as ComicFolderDiscoveryResult.Success).comicFolders.first()

        assertEquals("One Piece", folder.defaultTitle)
        assertEquals("Manga", folder.type)
        assertEquals("ID", folder.language)
        assertEquals("Oda", folder.author)

        // ASSERTION: New sidecar file MUST be created with valid JSON!
        assertEquals("Missing sidecar MUST trigger createFile", 1, gateway.createFileCalls)
        assertEquals("Missing sidecar MUST trigger openOutputStream", 1, gateway.openOutputStreamCalls)

        val createdEntry = gateway.findEntry("Comics/[Manga] [ID] [Oda] One Piece/metadata.json")
        assertTrue("Created metadata.json MUST exist on storage", createdEntry is com.example.kitsune.storage.StorageResult.Success)
    }

    @Test
    fun discoverComicFolders_plainFolderName_doesNotCreateUnnecessarySidecar() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/Bleach")
        // Plain folder name without brackets and no metadata.json

        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Success)
        val folder = (result as ComicFolderDiscoveryResult.Success).comicFolders.first()

        assertEquals("Bleach", folder.defaultTitle)
        assertNull(folder.type)
        assertNull(folder.language)
        assertNull(folder.author)

        // ASSERTION: Plain folder MUST NOT create an unnecessary metadata.json sidecar!
        assertEquals("Plain folder MUST NOT trigger createFile", 0, gateway.createFileCalls)
        assertEquals("Plain folder MUST NOT trigger openOutputStream", 0, gateway.openOutputStreamCalls)
    }

    @Test
    fun discoverComicFolders_legacyFolderFormat_doesNotProduceFakeAuthor() {
        val gateway = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        gateway.addDirectory("Comics")
        gateway.addDirectory("Comics/[Manga] [Japanese] One Piece")

        val detector = DefaultComicsDirectoryDetector(gateway)
        val scanner = DefaultComicFolderScanner(detector, gateway)

        val result = scanner.discoverComicFolders()
        assertTrue(result is ComicFolderDiscoveryResult.Success)
        val folder = (result as ComicFolderDiscoveryResult.Success).comicFolders.first()

        assertEquals("One Piece", folder.defaultTitle)
        assertEquals("Manga", folder.type)
        assertEquals("Japanese", folder.language)
        assertNull("Legacy 2-bracket folder MUST NOT produce a fake author", folder.author)
    }
}
