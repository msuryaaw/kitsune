package com.example.kitsune.scanner

/**
 * Parsed representation of folder metadata derived from folder naming convention or sidecar file.
 *
 * @param title Clean display title (e.g. "One Piece").
 * @param type Optional comic classification (e.g. "Manga", "Manhwa", "Comic").
 * @param language Optional language specification (e.g. "Japanese", "English", "Indonesian").
 */
data class ParsedFolderMetadata(
    val title: String,
    val type: String? = null,
    val language: String? = null
)

/**
 * Parser utility for extracting comic title, type, and language from folder names matching
 * the `[Type] [Language] Title` convention (e.g. `[Manga] [Japanese] One Piece`).
 */
object FolderMetadataParser {

    private val FOLDER_PATTERN = Regex("^\\[([^\\]]+)\\]\\s*\\[([^\\]]+)\\]\\s*(.+)$")

    /**
     * Parses the given [folderName] into a [ParsedFolderMetadata].
     *
     * If [folderName] matches `[Type] [Language] Title` with non-blank type, language, and title,
     * returns a [ParsedFolderMetadata] containing extracted type, language, and clean title.
     *
     * Otherwise, falls back safely to returning [folderName] trimmed as the title, with null type and language.
     */
    fun parseFolderName(folderName: String?): ParsedFolderMetadata {
        val trimmed = folderName?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return ParsedFolderMetadata(title = "")
        }

        val match = FOLDER_PATTERN.matchEntire(trimmed)
        if (match != null) {
            val rawType = match.groupValues[1].trim()
            val rawLanguage = match.groupValues[2].trim()
            val rawTitle = match.groupValues[3].trim()

            if (rawType.isNotBlank() && rawLanguage.isNotBlank() && rawTitle.isNotBlank()) {
                return ParsedFolderMetadata(
                    title = rawTitle,
                    type = rawType,
                    language = rawLanguage
                )
            }
        }

        return ParsedFolderMetadata(title = trimmed)
    }
}
