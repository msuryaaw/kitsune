package com.kitsune.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.kitsune.app.database.dao.SettingsDao
import com.kitsune.app.database.entity.SettingsEntity
import com.kitsune.app.ui.library.ComicSortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach

/**
 * Repository for managing application settings.
 * REVISION 10.5.1: Implemented memory caching for settings to reduce Room overhead during navigation.
 * REVISION Task B: Added SharedPreferences persistence for Comic Library and Bookmark sorting preferences.
 */
class SettingsRepository(
    private val settingsDao: SettingsDao,
    context: Context? = null
) {
    private val prefs: SharedPreferences? = context?.applicationContext?.getSharedPreferences("kitsune_preferences", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_COMIC_LIBRARY_SORT = "pref_comic_library_sort"
        private const val KEY_BOOKMARK_DETAIL_SORT = "pref_bookmark_detail_sort"
    }

    fun getComicLibrarySortOrder(): ComicSortOrder {
        val savedName = prefs?.getString(KEY_COMIC_LIBRARY_SORT, ComicSortOrder.TITLE_ASC.name)
        return try {
            if (savedName != null) ComicSortOrder.valueOf(savedName) else ComicSortOrder.TITLE_ASC
        } catch (e: Exception) {
            ComicSortOrder.TITLE_ASC
        }
    }

    fun saveComicLibrarySortOrder(order: ComicSortOrder) {
        prefs?.edit()?.putString(KEY_COMIC_LIBRARY_SORT, order.name)?.apply()
    }

    fun getBookmarkDetailSortOrder(): ComicSortOrder {
        val savedName = prefs?.getString(KEY_BOOKMARK_DETAIL_SORT, ComicSortOrder.TITLE_ASC.name)
        return try {
            if (savedName != null) ComicSortOrder.valueOf(savedName) else ComicSortOrder.TITLE_ASC
        } catch (e: Exception) {
            ComicSortOrder.TITLE_ASC
        }
    }

    fun saveBookmarkDetailSortOrder(order: ComicSortOrder) {
        prefs?.edit()?.putString(KEY_BOOKMARK_DETAIL_SORT, order.name)?.apply()
    }
    
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

    suspend fun updateLastScanTime(time: Long) {
        val currentSettings = getSettingsCached() ?: SettingsEntity()
        settingsDao.insertSettings(currentSettings.copy(lastScanTime = time))
    }
}
