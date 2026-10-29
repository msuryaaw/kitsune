package com.example.kitsune.core

import kotlinx.coroutines.flow.Flow

/**
 * Storage root folder state representation.
 */
sealed class RootFolderState {
    object NotConfigured : RootFolderState()
    data class PermissionLost(val uriString: String, val reason: String) : RootFolderState()
    data class Inaccessible(val uriString: String, val error: String) : RootFolderState()
    data class Ready(val uriString: String) : RootFolderState()
}

/**
 * Contract for persisting and retrieving root folder URI.
 * Stores string representation of URI for platform/framework independence.
 */
interface RootFolderPreferences {
    fun getRootUriString(): String?
    fun setRootUriString(uriString: String?)
    fun observeRootUriString(): Flow<String?>
}
