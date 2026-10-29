package com.example.kitsune.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class SharedPrefsRootFolderPreferences(
    context: Context
) : RootFolderPreferences {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun getRootUriString(): String? {
        val raw = prefs.getString(KEY_ROOT_URI, null)?.trim()
        return if (raw.isNullOrEmpty()) null else raw
    }

    override fun setRootUriString(uriString: String?) {
        val sanitized = uriString?.trim()
        prefs.edit().apply {
            if (!sanitized.isNullOrEmpty()) {
                putString(KEY_ROOT_URI, sanitized)
            } else {
                remove(KEY_ROOT_URI)
            }
            apply()
        }
    }

    override fun observeRootUriString(): Flow<String?> = callbackFlow {
        trySend(getRootUriString())

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ROOT_URI) {
                trySend(getRootUriString())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    companion object {
        const val PREFS_NAME = "kitsune_storage_prefs"
        const val KEY_ROOT_URI = "root_folder_uri"
    }
}
