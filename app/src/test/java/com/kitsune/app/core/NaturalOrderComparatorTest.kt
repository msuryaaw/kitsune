package com.kitsune.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral unit tests for NaturalOrderComparator.
 * Verifies numeric sorting embedded within strings (e.g. "Chapter 2" < "Chapter 10").
 */
class NaturalOrderComparatorTest {

    private val comparator = NaturalOrderComparator()

    @Test
    fun `test chapter numeric ordering`() {
        val input = listOf("Chapter 10", "Chapter 1", "Chapter 2", "Chapter 9", "Chapter 20")
        val sorted = input.sortedWith(comparator)

        val expected = listOf("Chapter 1", "Chapter 2", "Chapter 9", "Chapter 10", "Chapter 20")
        assertEquals(expected, sorted)
    }

    @Test
    fun `test image file natural ordering`() {
        val input = listOf("image_10.jpg", "image_1.jpg", "image_2.png", "image_100.webp", "image_02.jpg")
        val sorted = input.sortedWith(comparator)

        // Note: NaturalOrderComparator strips leading zeros when comparing numbers
        assertTrue(comparator.compare("image_2.png", "image_10.jpg") < 0)
        assertTrue(comparator.compare("image_1.jpg", "image_2.png") < 0)
        assertTrue(comparator.compare("image_10.jpg", "image_100.webp") < 0)
    }

    @Test
    fun `test case insensitive comparison`() {
        assertTrue(comparator.compare("chapter 1", "Chapter 2") < 0)
        assertTrue(comparator.compare("Chapter 2", "chapter 10") < 0)
    }

    @Test
    fun `test lexical versus natural order difference`() {
        val lexicalSorted = listOf("Chapter 1", "Chapter 10", "Chapter 2").sorted()
        val naturalSorted = listOf("Chapter 1", "Chapter 10", "Chapter 2").sortedWith(comparator)

        assertEquals(listOf("Chapter 1", "Chapter 10", "Chapter 2"), lexicalSorted)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), naturalSorted)
    }
}
