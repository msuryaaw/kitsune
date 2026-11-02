package com.example.kitsune.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderMetadataParserTest {

    @Test
    fun parseFolderName_validPattern_extractsTypeLanguageAndCleanTitle() {
        val result = FolderMetadataParser.parseFolderName("[Manga] [Japanese] One Piece")
        assertEquals("One Piece", result.title)
        assertEquals("Manga", result.type)
        assertEquals("Japanese", result.language)
    }

    @Test
    fun parseFolderName_validPatternWithExtraSpaces_trimsComponentsCorrectly() {
        val result = FolderMetadataParser.parseFolderName("  [Manhwa]   [Korean]   Solo Leveling   ")
        assertEquals("Solo Leveling", result.title)
        assertEquals("Manhwa", result.type)
        assertEquals("Korean", result.language)
    }

    @Test
    fun parseFolderName_legacyFolderName_fallsBackToFullFolderNameAsTitle() {
        val result = FolderMetadataParser.parseFolderName("Bleach")
        assertEquals("Bleach", result.title)
        assertNull(result.type)
        assertNull(result.language)
    }

    @Test
    fun parseFolderName_incompleteBrackets_fallsBackToFullFolderName() {
        val result1 = FolderMetadataParser.parseFolderName("[Manga] One Piece")
        assertEquals("[Manga] One Piece", result1.title)
        assertNull(result1.type)
        assertNull(result1.language)

        val result2 = FolderMetadataParser.parseFolderName("[Manga] [Japanese]")
        assertEquals("[Manga] [Japanese]", result2.title)
        assertNull(result2.type)
        assertNull(result2.language)
    }

    @Test
    fun parseFolderName_emptyTypeOrLanguage_fallsBackToFullFolderName() {
        val result1 = FolderMetadataParser.parseFolderName("[] [Japanese] One Piece")
        assertEquals("[] [Japanese] One Piece", result1.title)
        assertNull(result1.type)

        val result2 = FolderMetadataParser.parseFolderName("[Manga] [] One Piece")
        assertEquals("[Manga] [] One Piece", result2.title)
        assertNull(result2.type)
    }

    @Test
    fun parseFolderName_nullOrBlankInput_returnsEmptyTitleAndNullMetadata() {
        val resNull = FolderMetadataParser.parseFolderName(null)
        assertEquals("", resNull.title)
        assertNull(resNull.type)
        assertNull(resNull.language)

        val resBlank = FolderMetadataParser.parseFolderName("   ")
        assertEquals("", resBlank.title)
        assertNull(resBlank.type)
        assertNull(resBlank.language)
    }
}
