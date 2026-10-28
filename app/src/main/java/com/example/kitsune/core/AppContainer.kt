package com.example.kitsune.core

import android.content.Context

import com.example.kitsune.storage.SafStorageGateway
import com.example.kitsune.storage.StorageGateway

/**
 * Dependency container contract for Kitsune application.
 * Provides application-level dependencies via constructor injection / manual DI.
 */
interface AppContainer {
    val rootFolderPreferences: RootFolderPreferences
    val storageGateway: StorageGateway
}

/**
 * Default implementation of [AppContainer] providing real application dependencies.
 */
class DefaultAppContainer(
    private val appContext: Context
) : AppContainer {
    override val rootFolderPreferences: RootFolderPreferences by lazy {
        SharedPrefsRootFolderPreferences(appContext)
    }

    override val storageGateway: StorageGateway by lazy {
        SafStorageGateway(appContext, rootFolderPreferences)
    }
}
