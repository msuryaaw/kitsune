package com.example.kitsune.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Representation of application-wide display and reader preferences.
 * In accordance with architecture guidelines and decision log, SAF root URI and persistent
 * permission status remain managed by [com.example.kitsune.core.RootFolderPreferences].
 * This entity stores visual, grid, and reading experience preferences.
 *
 * @param id Single row identifier (defaults to 1).
 * @param gridColumns Selected column count for library grid (2, 3, or 4).
 * @param theme Selected UI theme ("system", "light", or "dark").
 * @param readingDirection Reading direction ("ltr" or "rtl").
 * @param readerMode Display mode ("single_page" or "vertical_continuous").
 * @param pageFit Fit mode for reader ("fit_screen", "fit_width", or "fit_height").
 */
@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val gridColumns: Int = 3,
    val theme: String = "system",
    val readingDirection: String = "ltr",
    val readerMode: String = "single_page",
    val pageFit: String = "fit_screen"
)

