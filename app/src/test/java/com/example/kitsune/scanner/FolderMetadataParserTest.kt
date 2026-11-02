package com.example.kitsune.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderMetadataParserTest {

    @Test
    fun parseFolderName_canonicalThreeBracketPattern_extractsTypeLanguageAuthorAndCleanTitle() {
        val result = FolderMetadataParser.parseFolderName("[Manga] [ID] [Oda] One Piece")
        assertEquals("One Piece", result.title)
        assertEquals("Manga", result.type)
        assertEquals("ID", result.language)
        assertEquals("Oda", result.author)
    }

    @Test
    fun parseFolderName_legacyTwoBracketPattern_extractsTypeLanguageCleanTitleAndNullAuthor() {
        val result = FolderMetadataParser.parseFolderName("[Manga] [Japanese] One Piece")
        assertEquals("One Piece", result.title)
        assertEquals("Manga", result.type)
        assertEquals("Japanese", result.language)
        assertNull("Author MUST be null for legacy 2-bracket format so author is not falsely extracted from title", result.author)
    }

    @Test
    fun parseFolderName_canonicalPatternWithExtraSpaces_trimsComponentsCorrectly() {
        val result = FolderMetadataParser.parseFolderName("  [Manhwa]   [Korean]   [Chugong]   Solo Leveling   ")
        assertEquals("Solo Leveling", result.title)
        assertEquals("Manhwa", result.type)
        assertEquals("Korean", result.language)
        assertEquals("Chugong", result.author)
    }

    @Test
    fun parseFolderName_legacyFolderName_fallsBackToFullFolderNameAsTitle() {
        val result = FolderMetadataParser.parseFolderName("Bleach")
        assertEquals("Bleach", result.title)
        assertNull(result.type)
        assertNull(result.language)
        assertNull(result.author)
    }

    @Test
    fun parseFolderName_incompleteBrackets_fallsBackToFullFolderName() {
        val result1 = FolderMetadataParser.parseFolderName("[Manga] One Piece")
        assertEquals("[Manga] One Piece", result1.title)
        assertNull(result1.type)
        assertNull(result1.language)
        assertNull(result1.author)

        val result2 = FolderMetadataParser.parseFolderName("[Manga] [Japanese]")
        assertEquals("[Manga] [Japanese]", result2.title)
        assertNull(result2.type)
        assertNull(result2.language)
        assertNull(result2.author)
    }

    @Test
    fun parseFolderName_emptyTypeOrLanguageOrAuthor_fallsBackToFullFolderName() {
        val result1 = FolderMetadataParser.parseFolderName("[] [Japanese] [Oda] One Piece")
        assertEquals("[] [Japanese] [Oda] One Piece", result1.title)
        assertNull(result1.type)

        val result2 = FolderMetadataParser.parseFolderName("[Manga] [] [Oda] One Piece")
        assertEquals("[Manga] [] [Oda] One Piece", result2.title)
        assertNull(result2.type)

        val result3 = FolderMetadataParser.parseFolderName("[Manga] [ID] [] One Piece")
        assertEquals("[Manga] [ID] [] One Piece", result3.title)
        assertNull(result3.type)
    }

    @Test
    fun parseFolderName_nullOrBlankInput_returnsEmptyTitleAndNullMetadata() {
        val resNull = FolderMetadataParser.parseFolderName(null)
        assertEquals("", resNull.title)
        assertNull(resNull.type)
        assertNull(resNull.language)
        assertNull(resNull.author)

        val resBlank = FolderMetadataParser.parseFolderName("   ")
        assertEquals("", resBlank.title)
        assertNull(resBlank.type)
        assertNull(resBlank.language)
        assertNull(resBlank.author)
    }
}
