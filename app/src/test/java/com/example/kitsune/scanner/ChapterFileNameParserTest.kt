package com.example.kitsune.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterFileNameParserTest {

    @Test
    fun parse_validStandardChapterNames_returnsValid() {
        val result1 = ChapterFileNameParser.parse("Chapter 1.cbz")
        assertTrue(result1 is ChapterParseResult.Valid)
        val valid1 = result1 as ChapterParseResult.Valid
        assertEquals("Chapter 1.cbz", valid1.fileName)
        assertEquals(1, valid1.chapterNumber)

        val result2 = ChapterFileNameParser.parse("Chapter 10.cbz")
        assertTrue(result2 is ChapterParseResult.Valid)
        assertEquals(10, (result2 as ChapterParseResult.Valid).chapterNumber)

        val result3 = ChapterFileNameParser.parse("Chapter 999.cbz")
        assertTrue(result3 is ChapterParseResult.Valid)
        assertEquals(999, (result3 as ChapterParseResult.Valid).chapterNumber)
    }

    @Test
    fun parse_zeroAndLeadingZero_returnsValidDocumentedBehavior() {
        val resZero = ChapterFileNameParser.parse("Chapter 0.cbz")
        assertTrue(resZero is ChapterParseResult.Valid)
        val validZero = resZero as ChapterParseResult.Valid
        assertEquals("Chapter 0.cbz", validZero.fileName)
        assertEquals(0, validZero.chapterNumber)

        val resLeadingZero = ChapterFileNameParser.parse("Chapter 01.cbz")
        assertTrue(resLeadingZero is ChapterParseResult.Valid)
        val validLeadingZero = resLeadingZero as ChapterParseResult.Valid
        assertEquals("Chapter 01.cbz", validLeadingZero.fileName)
        assertEquals(1, validLeadingZero.chapterNumber)
    }

    @Test
    fun parse_numberExceedingIntBounds_returnsInvalidWithoutCrash() {
        val overflowStr = "Chapter 99999999999999999999.cbz"
        val result = ChapterFileNameParser.parse(overflowStr)
        assertTrue(result is ChapterParseResult.Invalid)
        val invalid = result as ChapterParseResult.Invalid
        assertEquals(overflowStr, invalid.fileName)
        assertTrue(invalid.reason.contains("exceeds integer range"))
    }

    @Test
    fun parse_leadingOrTrailingWhitespace_returnsInvalid() {
        val resLeading = ChapterFileNameParser.parse(" Chapter 1.cbz")
        assertTrue(resLeading is ChapterParseResult.Invalid)

        val resTrailing = ChapterFileNameParser.parse("Chapter 1.cbz ")
        assertTrue(resTrailing is ChapterParseResult.Invalid)

        val resBoth = ChapterFileNameParser.parse("   Chapter 1.cbz   ")
        assertTrue(resBoth is ChapterParseResult.Invalid)
    }

    @Test
    fun parse_caseInsensitiveNames_returnsValidPreservingOriginalFileName() {
        val resultLower = ChapterFileNameParser.parse("chapter 2.cbz")
        assertTrue(resultLower is ChapterParseResult.Valid)
        val validLower = resultLower as ChapterParseResult.Valid
        assertEquals("chapter 2.cbz", validLower.fileName)
        assertEquals(2, validLower.chapterNumber)

        val resultUpper = ChapterFileNameParser.parse("CHAPTER 5.CBZ")
        assertTrue(resultUpper is ChapterParseResult.Valid)
        val validUpper = resultUpper as ChapterParseResult.Valid
        assertEquals("CHAPTER 5.CBZ", validUpper.fileName)
        assertEquals(5, validUpper.chapterNumber)

        val resultMixed = ChapterFileNameParser.parse("ChApTeR 42.cBz")
        assertTrue(resultMixed is ChapterParseResult.Valid)
        val validMixed = resultMixed as ChapterParseResult.Valid
        assertEquals("ChApTeR 42.cBz", validMixed.fileName)
        assertEquals(42, validMixed.chapterNumber)
    }

    @Test
    fun parse_multipleSpacesBetweenWordAndNumber_returnsValid() {
        val result = ChapterFileNameParser.parse("Chapter   3.cbz")
        assertTrue(result is ChapterParseResult.Valid)
        assertEquals(3, (result as ChapterParseResult.Valid).chapterNumber)
    }

    @Test
    fun parse_invalidNames_returnsInvalidWithoutThrowing() {
        // Missing space
        val res1 = ChapterFileNameParser.parse("Chapter1.cbz")
        assertTrue(res1 is ChapterParseResult.Invalid)

        // Non-number
        val res2 = ChapterFileNameParser.parse("Chapter One.cbz")
        assertTrue(res2 is ChapterParseResult.Invalid)

        // Wrong extension
        val res3 = ChapterFileNameParser.parse("Chapter 1.zip")
        assertTrue(res3 is ChapterParseResult.Invalid)

        // Extra suffix/prefix
        val res4 = ChapterFileNameParser.parse("My Chapter 1.cbz")
        assertTrue(res4 is ChapterParseResult.Invalid)

        val res5 = ChapterFileNameParser.parse("Chapter 1 - Final.cbz")
        assertTrue(res5 is ChapterParseResult.Invalid)

        // Non-chapter files
        val res6 = ChapterFileNameParser.parse("cover.jpg")
        assertTrue(res6 is ChapterParseResult.Invalid)

        val res7 = ChapterFileNameParser.parse(".nomedia")
        assertTrue(res7 is ChapterParseResult.Invalid)

        // Blank or null
        val res8 = ChapterFileNameParser.parse("")
        assertTrue(res8 is ChapterParseResult.Invalid)

        val res9 = ChapterFileNameParser.parse(null)
        assertTrue(res9 is ChapterParseResult.Invalid)
    }

    @Test
    fun isValidChapterFileName_convenienceMethodMatchesParse() {
        assertTrue(ChapterFileNameParser.isValidChapterFileName("Chapter 1.cbz"))
        assertTrue(ChapterFileNameParser.isValidChapterFileName("chapter 10.cbz"))
        assertFalse(ChapterFileNameParser.isValidChapterFileName("Chapter1.cbz"))
        assertFalse(ChapterFileNameParser.isValidChapterFileName("notes.txt"))
        assertFalse(ChapterFileNameParser.isValidChapterFileName(null))
    }
}

