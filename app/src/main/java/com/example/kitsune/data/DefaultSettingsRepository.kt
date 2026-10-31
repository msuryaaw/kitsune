package com.example.kitsune.data

import com.example.kitsune.data.dao.SettingsDao
import com.example.kitsune.data.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Default implementation of [SettingsRepository] backed by Room [SettingsDao].
 */
class DefaultSettingsRepository(
    private val settingsDao: SettingsDao
) : SettingsRepository {

    override fun observeSettings(): Flow<SettingsEntity> {
        return settingsDao.observeSettings().map { it ?: SettingsEntity() }
    }

    override suspend fun setGridColumns(columns: Int) {
        val sanitized = columns.coerceIn(2, 4)
        val existing = settingsDao.getSettings()
        val updated = (existing ?: SettingsEntity()).copy(gridColumns = sanitized)
        settingsDao.upsertSettings(updated)
    }

    override suspend fun setReaderMode(readerMode: String) {
        val sanitized = if (readerMode == "vertical_continuous") "vertical_continuous" else "single_page"
        val existing = settingsDao.getSettings()
        val updated = (existing ?: SettingsEntity()).copy(readerMode = sanitized)
        settingsDao.upsertSettings(updated)
    }

    override suspend fun setReadingDirection(readingDirection: String) {
        val sanitized = if (readingDirection == "rtl") "rtl" else "ltr"
        val existing = settingsDao.getSettings()
        val updated = (existing ?: SettingsEntity()).copy(readingDirection = sanitized)
        settingsDao.upsertSettings(updated)
    }
}
