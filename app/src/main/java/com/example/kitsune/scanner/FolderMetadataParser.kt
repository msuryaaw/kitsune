package com.example.kitsune.scanner

/**
 * Parsed representation of folder metadata derived from folder naming convention or sidecar file.
 *
 * @param title Clean display title (e.g. "One Piece").
 * @param type Optional comic classification (e.g. "Manga", "Manhwa", "Comic").
 * @param language Optional language specification (e.g. "Japanese", "English", "Indonesian").
 * @param author Optional comic author/creator (e.g. "Oda", "Kishimoto").
 */
data class ParsedFolderMetadata(
    val title: String,
    val type: String? = null,
    val language: String? = null,
    val author: String? = null
)

/**
 * Parser utility for extracting comic title, type, language, and author from folder names matching
 * the canonical `[Type] [Language] [Author] Title` convention (e.g. `[Manga] [ID] [Oda] One Piece`)
 * or legacy `[Type] [Language] Title` convention (e.g. `[Manga] [Japanese] One Piece`).
 */
object FolderMetadataParser {

    private val CANONICAL_PATTERN = Regex("^\\[([^\\]]+)\\]\\s*\\[([^\\]]+)\\]\\s*\\[([^\\]]+)\\]\\s*(.+)$")
    private val LEGACY_PATTERN = Regex("^\\[([^\\]]+)\\]\\s*\\[([^\\]]+)\\]\\s*(.+)$")

    /**
     * Parses the given [folderName] into a [ParsedFolderMetadata].
     *
     * 1. Checks canonical pattern `[Type] [Language] [Author] Title`.
     * 2. Checks legacy pattern `[Type] [Language] Title` (setting `author = null`).
     * 3. Otherwise, falls back safely to returning [folderName] trimmed as title, with null metadata.
     */
    fun parseFolderName(folderName: String?): ParsedFolderMetadata {
        val trimmed = folderName?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return ParsedFolderMetadata(title = "")
        }

        // 1. Canonical 3-bracket pattern: [Type] [Language] [Author] Title
        val matchCanonical = CANONICAL_PATTERN.matchEntire(trimmed)
        if (matchCanonical != null) {
            val rawType = matchCanonical.groupValues[1].trim()
            val rawLanguage = matchCanonical.groupValues[2].trim()
            val rawAuthor = matchCanonical.groupValues[3].trim()
            val rawTitle = matchCanonical.groupValues[4].trim()

            if (rawType.isNotBlank() && rawLanguage.isNotBlank() && rawAuthor.isNotBlank() && rawTitle.isNotBlank()) {
                return ParsedFolderMetadata(
                    title = rawTitle,
                    type = rawType,
                    language = rawLanguage,
                    author = rawAuthor
                )
            }
        }

        // 2. Legacy 2-bracket pattern: [Type] [Language] Title
        val matchLegacy = LEGACY_PATTERN.matchEntire(trimmed)
        if (matchLegacy != null) {
            val rawType = matchLegacy.groupValues[1].trim()
            val rawLanguage = matchLegacy.groupValues[2].trim()
            val rawTitle = matchLegacy.groupValues[3].trim()

            if (rawType.isNotBlank() && rawLanguage.isNotBlank() && rawTitle.isNotBlank() && !rawTitle.startsWith("[")) {
                return ParsedFolderMetadata(
                    title = rawTitle,
                    type = rawType,
                    language = rawLanguage,
                    author = null
                )
            }
        }

        // 3. Fallback: Full folder name
        return ParsedFolderMetadata(title = trimmed)
    }
}
