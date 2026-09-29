package com.kitsune.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral unit tests for SearchUtils.matches().
 * Verifies comma-separated multi-criteria AND search logic.
 */
class SearchUtilsTest {

    private val fields = listOf("One Piece", "Eiichiro Oda", "EN", "Manga", "action adventure pirate")

    @Test
    fun `test empty query matches all`() {
        assertTrue(SearchUtils.matches("", fields))
        assertTrue(SearchUtils.matches("   ", fields))
        assertTrue(SearchUtils.matches(",,", fields))
    }

    @Test
    fun `test single criterion matching`() {
        assertTrue(SearchUtils.matches("one", fields))
        assertTrue(SearchUtils.matches("oda", fields))
        assertTrue(SearchUtils.matches("action", fields))
        assertFalse(SearchUtils.matches("naruto", fields))
    }

    @Test
    fun `test multi-token comma separated AND semantics`() {
        // Both "one piece" AND "oda" must be present in searchable fields
        assertTrue(SearchUtils.matches("one piece, oda", fields))
        assertTrue(SearchUtils.matches("manga, action, en", fields))

        // If one criterion fails, AND logic returns false
        assertFalse(SearchUtils.matches("one piece, naruto", fields))
    }

    @Test
    fun `test criterion order independence`() {
        val match1 = SearchUtils.matches("oda, manga", fields)
        val match2 = SearchUtils.matches("manga, oda", fields)
        assertTrue(match1)
        assertTrue(match2)
        assertEquals(match1, match2)
    }

    @Test
    fun `test whitespace and empty criteria handling`() {
        assertTrue(SearchUtils.matches(" oda ,  manga ,, ", fields))
    }

    @Test
    fun `test case insensitive matching`() {
        assertTrue(SearchUtils.matches("ONE PIECE, ODA", fields))
        assertTrue(SearchUtils.matches("en, MANGA", fields))
    }

    @Test
    fun `test null fields handled safely`() {
        val nullableFields = listOf("Title", null, "Author", null)
        assertTrue(SearchUtils.matches("title", nullableFields))
        assertTrue(SearchUtils.matches("author", nullableFields))
        assertFalse(SearchUtils.matches("missing", nullableFields))
    }
}
