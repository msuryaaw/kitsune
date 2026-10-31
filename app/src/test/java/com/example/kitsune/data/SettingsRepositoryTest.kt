package com.example.kitsune.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.kitsune.data.dao.SettingsDao
import com.example.kitsune.data.entity.SettingsEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var db: KitsuneDatabase
    private lateinit var settingsDao: SettingsDao
    private lateinit var repository: SettingsRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KitsuneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsDao = db.settingsDao()
        repository = DefaultSettingsRepository(settingsDao)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun observeSettings_initiallyEmitsDefaultSettings_whenDatabaseIsEmpty() = runTest {
        val settings = repository.observeSettings().first()
        assertNotNull(settings)
        assertEquals(3, settings.gridColumns)
        assertEquals("system", settings.theme)
        assertEquals("ltr", settings.readingDirection)
        assertEquals("single_page", settings.readerMode)
        assertEquals("fit_screen", settings.pageFit)
    }

    @Test
    fun setGridColumns_validColumns_persistsAndEmitsNewValue() = runTest {
        repository.setGridColumns(2)
        val settings2 = repository.observeSettings().first()
        assertEquals(2, settings2.gridColumns)

        repository.setGridColumns(4)
        val settings4 = repository.observeSettings().first()
        assertEquals(4, settings4.gridColumns)
    }

    @Test
    fun setGridColumns_coercesInputsOutsideValidRange() = runTest {
        // Lower than 2 -> coerced to 2
        repository.setGridColumns(1)
        assertEquals(2, repository.observeSettings().first().gridColumns)

        // Higher than 4 -> coerced to 4
        repository.setGridColumns(10)
        assertEquals(4, repository.observeSettings().first().gridColumns)
    }

    @Test
    fun setGridColumns_preservesOtherSettingsFields() = runTest {
        // Seed custom settings row
        val custom = SettingsEntity(
            id = 1,
            gridColumns = 3,
            theme = "dark",
            readingDirection = "rtl",
            readerMode = "vertical_continuous",
            pageFit = "fit_width"
        )
        settingsDao.upsertSettings(custom)

        // Update grid columns
        repository.setGridColumns(4)

        val updated = repository.observeSettings().first()
        assertEquals(4, updated.gridColumns)
        // All other fields preserved 100%
        assertEquals("dark", updated.theme)
        assertEquals("rtl", updated.readingDirection)
        assertEquals("vertical_continuous", updated.readerMode)
        assertEquals("fit_width", updated.pageFit)
    }

    @Test
    fun setGridColumns_persistsAcrossDatabaseCloseAndReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("test_persistence.db")
        dbFile.delete() // Ensure clean start

        // 1. Open first DB instance and save gridColumns = 4
        var fileDb = Room.databaseBuilder(context, KitsuneDatabase::class.java, dbFile.name)
            .allowMainThreadQueries()
            .build()
        var repo: SettingsRepository = DefaultSettingsRepository(fileDb.settingsDao())

        repo.setGridColumns(4)
        assertEquals(4, repo.observeSettings().first().gridColumns)

        // Close the database
        fileDb.close()

        // 2. Open second DB instance from the SAME file
        fileDb = Room.databaseBuilder(context, KitsuneDatabase::class.java, dbFile.name)
            .allowMainThreadQueries()
            .build()
        repo = DefaultSettingsRepository(fileDb.settingsDao())

        // 3. Verify that gridColumns = 4 is read back from the persisted file
        val restoredSettings = repo.observeSettings().first()
        assertEquals("gridColumns must be restored after DB close and reopen", 4, restoredSettings.gridColumns)

        fileDb.close()
        dbFile.delete()
    }

    @Test
    fun setReadingDirection_validInputs_persistsAndPreservesOtherFields() = runTest {
        // Seed initial settings
        val initial = SettingsEntity(
            id = 1,
            gridColumns = 4,
            theme = "dark",
            readingDirection = "ltr",
            readerMode = "vertical_continuous",
            pageFit = "fit_width"
        )
        settingsDao.upsertSettings(initial)

        // Set direction to "rtl"
        repository.setReadingDirection("rtl")
        val settingsRtl = repository.observeSettings().first()
        assertEquals("rtl", settingsRtl.readingDirection)
        // All other fields preserved 100%
        assertEquals(4, settingsRtl.gridColumns)
        assertEquals("dark", settingsRtl.theme)
        assertEquals("vertical_continuous", settingsRtl.readerMode)
        assertEquals("fit_width", settingsRtl.pageFit)

        // Set direction to "ltr"
        repository.setReadingDirection("ltr")
        val settingsLtr = repository.observeSettings().first()
        assertEquals("ltr", settingsLtr.readingDirection)
        assertEquals(4, settingsLtr.gridColumns)
    }

    @Test
    fun setReadingDirection_invalidInput_sanitizesToLtr() = runTest {
        repository.setReadingDirection("invalid_direction_string")
        val settings = repository.observeSettings().first()
        assertEquals("ltr", settings.readingDirection)
    }
}
