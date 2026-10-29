package com.example.kitsune.scanner

import com.example.kitsune.storage.StorageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderComparatorTest {

    @Test
    fun naturalOrderComparator_chapterNumberSorting_ordersChapter2BeforeChapter10() {
        val input = listOf(
            "Chapter 10.cbz",
            "Chapter 2.cbz",
            "Chapter 1.cbz",
            "Chapter 20.cbz",
            "Chapter 3.cbz"
        )
        val sorted = input.sortedWith(NaturalOrderComparator)
        assertEquals(
            listOf(
                "Chapter 1.cbz",
                "Chapter 2.cbz",
                "Chapter 3.cbz",
                "Chapter 10.cbz",
                "Chapter 20.cbz"
            ),
            sorted
        )
    }

    @Test
    fun naturalOrderComparator_imageEntrySorting_ordersImage2BeforeImage10() {
        val input = listOf(
            "image_10.jpg",
            "image_1.jpg",
            "image_2.jpg",
            "image_100.png",
            "image_20.webp"
        )
        val sorted = input.sortedWith(NaturalOrderComparator)
        assertEquals(
            listOf(
                "image_1.jpg",
                "image_2.jpg",
                "image_10.jpg",
                "image_20.webp",
                "image_100.png"
            ),
            sorted
        )
    }

    @Test
    fun naturalOrderComparator_nestedPaths_sortsSegmentsDeterministically() {
        val input = listOf(
            "pages/page_10.png",
            "pages/page_1.png",
            "pages/page_2.png",
            "cover/front_1.jpg"
        )
        val sorted = input.sortedWith(NaturalOrderComparator)
        assertEquals(
            listOf(
                "cover/front_1.jpg",
                "pages/page_1.png",
                "pages/page_2.png",
                "pages/page_10.png"
            ),
            sorted
        )
    }

    @Test
    fun naturalOrderComparator_handlesCaseInsensitivityWithDeterministicTieBreak() {
        val input = listOf(
            "CHAPTER 2.cbz",
            "chapter 10.cbz",
            "Chapter 1.cbz",
            "chapter 2.cbz"
        )
        val sorted = input.sortedWith(NaturalOrderComparator)

        // Chapter 1 must be first, then Chapter 2, then Chapter 10
        assertEquals("Chapter 1.cbz", sorted[0])
        assertEquals("chapter 10.cbz", sorted[3])

        // Between CHAPTER 2.cbz and chapter 2.cbz, uppercase/lowercase are equal case-insensitively,
        // and tie broken case-sensitively ("C" < "c")
        assertEquals("CHAPTER 2.cbz", sorted[1])
        assertEquals("chapter 2.cbz", sorted[2])
    }

    @Test
    fun naturalOrderComparator_handlesLeadingZerosCleanly() {
        val input = listOf(
            "Chapter 01.cbz",
            "Chapter 001.cbz",
            "Chapter 1.cbz",
            "Chapter 2.cbz"
        )
        val sorted = input.sortedWith(NaturalOrderComparator)
        // All represent 1 before 2; tie-break prefers shorter representation (fewer leading zeros)
        assertEquals(
            listOf(
                "Chapter 1.cbz",
                "Chapter 01.cbz",
                "Chapter 001.cbz",
                "Chapter 2.cbz"
            ),
            sorted
        )
    }

    @Test
    fun naturalOrderComparator_handlesArbitraryLargeNumbers() {
        val input = listOf(
            "Chapter 10000000000000000000000000000000000000.cbz",
            "Chapter 2.cbz",
            "Chapter 999999999999999999999999999999999999.cbz"
        )
        val sorted = input.sortedWith(NaturalOrderComparator)
        assertEquals(
            listOf(
                "Chapter 2.cbz",
                "Chapter 999999999999999999999999999999999999.cbz",
                "Chapter 10000000000000000000000000000000000000.cbz"
            ),
            sorted
        )
    }

    @Test
    fun naturalOrderComparator_nullAndEqualityContract() {
        assertEquals(0, NaturalOrderComparator.compare("abc", "abc"))
        assertTrue(NaturalOrderComparator.compare(null, "abc") < 0)
        assertTrue(NaturalOrderComparator.compare("abc", null) > 0)
        assertEquals(0, NaturalOrderComparator.compare(null, null))
    }

    @Test
    fun chapterNaturalComparator_sortsDiscoveredChaptersCorrectly() {
        val dummyEntry = StorageEntry(
            name = "dummy",
            relativePath = "dummy",
            isDirectory = false
        )

        val ch10 = DiscoveredChapter("chapter 10.cbz", "Comics/Test/chapter 10.cbz", 10, dummyEntry)
        val ch2 = DiscoveredChapter("Chapter 2.cbz", "Comics/Test/Chapter 2.cbz", 2, dummyEntry)
        val ch1 = DiscoveredChapter("Chapter 1.cbz", "Comics/Test/Chapter 1.cbz", 1, dummyEntry)

        val list = listOf(ch10, ch2, ch1)
        val sorted = list.sortedWith(ChapterNaturalComparator)

        assertEquals(1, sorted[0].chapterNumber)
        assertEquals(2, sorted[1].chapterNumber)
        assertEquals(10, sorted[2].chapterNumber)
    }
}

