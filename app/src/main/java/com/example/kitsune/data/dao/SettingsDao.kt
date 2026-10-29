package com.example.kitsune.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.kitsune.data.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for managing application-wide settings in Room DB.
 */
@Dao
interface SettingsDao {

    /**
     * Observes the single settings row (id = 1).
     */
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observeSettings(): Flow<SettingsEntity?>

    /**
     * Retrieves the single settings row (id = 1) synchronously/one-shot.
     */
    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): SettingsEntity?

    /**
     * Inserts or replaces the settings row.
     */
    @Upsert
    suspend fun upsertSettings(settings: SettingsEntity)
}
