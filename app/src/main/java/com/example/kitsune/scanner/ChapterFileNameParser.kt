package com.example.kitsune.scanner

/**
 * Result of parsing a chapter file name (e.g. "Chapter 1.cbz").
 */
sealed class ChapterParseResult {
    /**
     * Successfully recognized a chapter file adhering to `Chapter <angka>.cbz`.
     *
     * @param fileName The original file name (e.g. "Chapter 1.cbz").
     * @param chapterNumber The parsed chapter number as an integer.
     */
    data class Valid(
        val fileName: String,
        val chapterNumber: Int
    ) : ChapterParseResult()

    /**
     * File name did not match the required `Chapter <angka>.cbz` convention.
     * The application will ignore or register this in scan diagnostics,
     * without modifying the original file name on disk.
     *
     * @param fileName The original file name that was rejected.
     * @param reason Description of why the file name is invalid.
     */
    data class Invalid(
        val fileName: String,
        val reason: String
    ) : ChapterParseResult()
}

/**
 * Pure parser for chapter file names based on Kitsune CBZ and Filesystem specifications.
 * Recognizes case-insensitive pattern: `^Chapter\s+(\d+)\.cbz$`
 */
object ChapterFileNameParser {

    private val CHAPTER_REGEX = Regex("""^Chapter\s+(\d+)\.cbz$""", RegexOption.IGNORE_CASE)

    /**
     * Parses the given [fileName].
     * Returns [ChapterParseResult.Valid] if it matches `Chapter <angka>.cbz` without renaming the file,
     * or [ChapterParseResult.Invalid] otherwise.
     */
    fun parse(fileName: String?): ChapterParseResult {
        if (fileName.isNullOrBlank()) {
            return ChapterParseResult.Invalid(fileName.orEmpty(), "File name is empty or blank")
        }

        val match = CHAPTER_REGEX.matchEntire(fileName)
            ?: return ChapterParseResult.Invalid(
                fileName,
                "File name does not match required convention 'Chapter <number>.cbz'"
            )

        val numberStr = match.groupValues[1]
        val number = numberStr.toIntOrNull()
            ?: return ChapterParseResult.Invalid(
                fileName,
                "Chapter number '$numberStr' exceeds integer range"
            )

        return ChapterParseResult.Valid(
            fileName = fileName,
            chapterNumber = number
        )
    }

    /**
     * Convenience method returning whether [fileName] is a valid chapter file name.
     */
    fun isValidChapterFileName(fileName: String?): Boolean {
        return parse(fileName) is ChapterParseResult.Valid
    }
}

