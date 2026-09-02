package com.kitsune.app.data.metadata

import kotlinx.serialization.Serializable

/**
 * Domain model for media metadata.
 * Consistent with the decisions in Phase 9.1 - 9.4 (Future Prep).
 * 
 * DESIGN RULE: 
 * All new fields added to this class MUST have a default value to ensure 
 * backward compatibility with older metadata.json files.
 * 
 * @property version Metadata format version for future migrations.
 * @property tags List of strings for categorization.
 */
@Serializable
data class MediaMetadata(
    val version: Int = CURRENT_VERSION,
    val tags: List<String> = emptyList(),
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    val type: String? = null
) {
    companion object {
        /**
         * The current version of the metadata schema.
         * Incremented only when breaking changes are introduced.
         */
        const val CURRENT_VERSION = 1
    }
}
