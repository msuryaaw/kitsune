package com.example.kitsune.data

import com.example.kitsune.data.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing application preferences and display settings in Room DB.
 */
interface SettingsRepository {

    /**
     * Observes application settings. Emits default [SettingsEntity] if no row exists in DB yet.
     */
    fun observeSettings(): Flow<SettingsEntity>

    /**
     * Updates the grid column count setting in DB, coercing inputs strictly to 2..4 range
     * and preserving all other settings fields.
     */
    suspend fun setGridColumns(columns: Int)

    /**
     * Updates the reader mode setting in DB ("single_page" or "vertical_continuous").
     */
    suspend fun setReaderMode(readerMode: String)
}
