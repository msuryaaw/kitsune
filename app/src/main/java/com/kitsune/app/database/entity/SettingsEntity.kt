package com.kitsune.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val rootFolderUri: String? = null,
    val gridSize: Int = 3,
    val readingMode: String = "Vertical",
    val darkMode: Boolean = true,
    val oledBlack: Boolean = false,
    val keepScreenOn: Boolean = true,
    val showPageNumber: Boolean = true,
    val preloadPages: Boolean = true,
    val lastScanTime: Long = 0
)
