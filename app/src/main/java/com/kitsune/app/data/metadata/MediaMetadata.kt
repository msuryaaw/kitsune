package com.kitsune.app.data.metadata

import kotlinx.serialization.Serializable

/**
 * Domain model for media metadata.
 * Consistent with the decision in Phase 9.1.
 * 
 * @property version Metadata format version for migration.
 * @property tags List of strings for categorization.
 */
@Serializable
data class MediaMetadata(
    val version: Int = 1,
    val tags: List<String> = emptyList()
)
