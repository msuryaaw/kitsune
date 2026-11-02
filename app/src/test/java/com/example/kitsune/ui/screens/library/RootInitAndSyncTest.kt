package com.example.kitsune.ui.screens.library

import com.example.kitsune.core.FakeRootFolderPreferences
import com.example.kitsune.data.entity.ComicEntity
import com.example.kitsune.data.entity.SettingsEntity
import com.example.kitsune.scanner.ComicsDirectoryDetector
import com.example.kitsune.scanner.ComicsDirectoryState
import com.example.kitsune.scanner.DefaultComicsDirectoryDetector
import com.example.kitsune.storage.FakeStorageGateway
import com.example.kitsune.storage.StorageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootInitAndSyncTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val rootUri = "content://primary%3AKitsune"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun rootInitialization_createsComicsDirectory_ifNotPresent() {
        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        // Storage root starts empty without Comics directory
        val findInitial = fakeStorage.findEntry("Comics")
        assertTrue("Initially Comics folder does NOT exist", findInitial is StorageResult.NotFound)

        // Execute createDirectory("", "Comics")
        val createResult = fakeStorage.createDirectory("", "Comics")
        assertTrue("createDirectory MUST succeed", createResult is StorageResult.Success)

        // Verify Comics folder now exists
        val findAfter = fakeStorage.findEntry("Comics")
        assertTrue("Comics folder MUST exist after creation", findAfter is StorageResult.Success)
        assertTrue("Comics entry MUST be a directory", (findAfter as StorageResult.Success).data.isDirectory)
    }

    @Test
    fun automaticInitialScan_triggersSyncLibrary_whenRootConfigured() = runTest {
        val fakeRepo = FakeLibraryRepository()
        val fakePrefs = FakeRootFolderPreferences()
        val fakeSettingsRepo = FakeSettingsRepository()
        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val detector = DefaultComicsDirectoryDetector(fakeStorage)

        // 1. Initially no root configured
        val viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, detector)
        assertEquals(0, fakeRepo.syncCalls)

        // 2. Set root URI
        fakePrefs.setRootUriString(rootUri)
        testDispatcher.scheduler.advanceUntilIdle()

        // 3. Verify syncLibrary WAS AUTOMATICALLY TRIGGERED!
        assertTrue("Setting a valid root URI MUST trigger automatic initial scan syncLibrary", fakeRepo.syncCalls >= 1)
        assertEquals(rootUri, fakeRepo.syncRootUri)
    }

    @Test
    fun libraryViewModel_createComicsDirectory_createsFolderAndTriggersSync() = runTest {
        val fakeRepo = FakeLibraryRepository()
        val fakePrefs = FakeRootFolderPreferences(rootUri)
        val fakeSettingsRepo = FakeSettingsRepository()
        val fakeStorage = FakeStorageGateway(rootConfigured = true, permissionGranted = true)
        val detector = DefaultComicsDirectoryDetector(fakeStorage)

        val viewModel = LibraryViewModel(fakeRepo, fakePrefs, fakeSettingsRepo, detector)
        val initialSyncCalls = fakeRepo.syncCalls

        viewModel.createComicsDirectory()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("createComicsDirectory MUST trigger library sync", fakeRepo.syncCalls > initialSyncCalls)
    }
}
