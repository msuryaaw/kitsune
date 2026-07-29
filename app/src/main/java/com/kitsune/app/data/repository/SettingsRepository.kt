package com.kitsune.app.data.repository

import com.kitsune.app.database.dao.SettingsDao
import com.kitsune.app.database.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach

/**
 * Repository for managing application settings.
 * REVISION 10.5.1: Implemented memory caching for settings to reduce Room overhead during navigation.
 */
class SettingsRepository(private val settingsDao: SettingsDao) {
    
    private var _cachedSettings: SettingsEntity? = null

    /**
     * Flow of settings with memory caching and stability.
     */
    val settings: Flow<SettingsEntity?> = settingsDao.getSettings()
        .onEach { _cachedSettings = it }
        .distinctUntilChanged()

    /**
     * Efficiently retrieves settings from cache if available, falling back to database.
     * Use this instead of settings.first() for one-shot operations in ViewModels.
     */
    suspend fun getSettingsCached(): SettingsEntity? {
        return _cachedSettings ?: settingsDao.getSettingsSync()
    }

    suspend fun saveSettings(settings: SettingsEntity) {
        settingsDao.insertSettings(settings)
    }

    suspend fun updateRootFolderUri(uri: String) {
        val currentSettings = getSettingsCached() ?: SettingsEntity()
        settingsDao.insertSettings(currentSettings.copy(rootFolderUri = uri))
    }

    suspend fun updateReadingMode(mode: String) {
        val currentSettings = getSettingsCached() ?: SettingsEntity()
        settingsDao.insertSettings(currentSettings.copy(readingMode = mode))
    }

    suspend fun updateGridSize(size: Int) {
        val currentSettings = getSettingsCached() ?: SettingsEntity()
        settingsDao.insertSettings(currentSettings.copy(gridSize = size))
    }

    suspend fun updateDarkMode(enabled: Boolean) {
        val currentSettings = getSettingsCached() ?: SettingsEntity()
        settingsDao.insertSettings(currentSettings.copy(darkMode = enabled))
    }

    suspend fun updateOledBlack(enabled: Boolean) {
        val currentSettings = getSettingsCached() ?: SettingsEntity()
        settingsDao.insertSettings(currentSettings.copy(oledBlack = enabled))
    }
}
