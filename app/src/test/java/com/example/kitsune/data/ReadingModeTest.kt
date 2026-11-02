package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.reader.ChapterPageIndex
import com.example.kitsune.ui.screens.reader.ReaderUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReadingModeTest {

    private lateinit var db: KitsuneDatabase
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultSettingsRepository(db.settingsDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun readingMode_persistence_savesAndRetrievesRtlLtrVertical() = runTest {
        repository.setReadingMode("rtl")
        assertEquals("rtl", repository.observeSettings().first().readingMode)

        repository.setReadingMode("vertical")
        assertEquals("vertical", repository.observeSettings().first().readingMode)

        repository.setReadingMode("ltr")
        assertEquals("ltr", repository.observeSettings().first().readingMode)
    }

    @Test
    fun readingMode_invalidInput_fallsBackToLtr() = runTest {
        repository.setReadingMode("invalid_mode")
        assertEquals("ltr", repository.observeSettings().first().readingMode)
    }

    @Test
    fun effectiveReadingMode_mapsLegacyAndNewModeFieldsCorrectly() {
        val stateRtl = ReaderUiState.Success(
            chapterId = 1L,
            pageIndex = ChapterPageIndex(1L, emptyList()),
            readerMode = "single_page",
            readingDirection = "rtl",
            readingMode = "rtl"
        )
        assertEquals("rtl", stateRtl.effectiveReadingMode)

        val stateVertical = ReaderUiState.Success(
            chapterId = 1L,
            pageIndex = ChapterPageIndex(1L, emptyList()),
            readerMode = "vertical_continuous",
            readingDirection = "ltr",
            readingMode = "vertical"
        )
        assertEquals("vertical", stateVertical.effectiveReadingMode)

        val stateInvalidFallback = ReaderUiState.Success(
            chapterId = 1L,
            pageIndex = ChapterPageIndex(1L, emptyList()),
            readerMode = "vertical_continuous",
            readingDirection = "rtl",
            readingMode = "invalid_value"
        )
        assertEquals("Invalid readingMode MUST fall back to ltr without reading legacy fields", "ltr", stateInvalidFallback.effectiveReadingMode)
    }
}
