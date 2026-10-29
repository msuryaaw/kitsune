package com.example.kitsune.scanner

import java.math.BigInteger

/**
 * Natural sorting comparator for strings containing multi-digit numbers,
 * such as chapter names ("Chapter 2.cbz" vs "Chapter 10.cbz") and image paths
 * ("image_2.jpg" vs "image_10.jpg" or "sub/page_1.png" vs "sub/page_10.png").
 *
 * Behavior:
 * - Splits string into contiguous non-digit and digit segments.
 * - Non-digit segments are compared case-insensitively.
 * - If non-digit segments are equal case-insensitively, compares case-sensitively for determinism.
 * - Numeric segments are compared by their numerical value. Supports arbitrary length numbers via [BigInteger].
 * - If numeric values are identical (e.g. "1" vs "01"), shorter string (fewer leading zeros) comes first.
 */
object NaturalOrderComparator : Comparator<String> {

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 == s2) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        val primary = compareSegments(s1, s2, ignoreCase = true)
        if (primary != 0) {
            return primary
        }

        // Secondary tie-breaker for strict determinism when strings differ only in case
        return compareSegments(s1, s2, ignoreCase = false)
    }

    private fun compareSegments(s1: String, s2: String, ignoreCase: Boolean): Int {
        var i1 = 0
        var i2 = 0
        val len1 = s1.length
        val len2 = s2.length

        while (i1 < len1 && i2 < len2) {
            val c1 = s1[i1]
            val c2 = s2[i2]

            if (c1.isDigit() && c2.isDigit()) {
                val start1 = i1
                while (i1 < len1 && s1[i1].isDigit()) i1++
                val numStr1 = s1.substring(start1, i1)

                val start2 = i2
                while (i2 < len2 && s2[i2].isDigit()) i2++
                val numStr2 = s2.substring(start2, i2)

                val val1 = BigInteger(numStr1)
                val val2 = BigInteger(numStr2)
                val numComparison = val1.compareTo(val2)
                if (numComparison != 0) {
                    return numComparison
                }

                // If numeric values are identical (e.g. "01" vs "1"), shorter string (fewer leading zeros) comes first
                val lengthComparison = numStr1.length.compareTo(numStr2.length)
                if (lengthComparison != 0) {
                    return lengthComparison
                }
            } else {
                val start1 = i1
                while (i1 < len1 && !s1[i1].isDigit()) i1++
                val text1 = s1.substring(start1, i1)

                val start2 = i2
                while (i2 < len2 && !s2[i2].isDigit()) i2++
                val text2 = s2.substring(start2, i2)

                val textComparison = text1.compareTo(text2, ignoreCase = ignoreCase)
                if (textComparison != 0) {
                    return textComparison
                }
            }
        }

        return len1.compareTo(len2)
    }
}

/**
 * Comparator specialized for [DiscoveredChapter].
 * Compares by [DiscoveredChapter.chapterNumber] ascending, then ties broken by [NaturalOrderComparator] on fileName.
 */
object ChapterNaturalComparator : Comparator<DiscoveredChapter> {
    override fun compare(c1: DiscoveredChapter?, c2: DiscoveredChapter?): Int {
        if (c1 == c2) return 0
        if (c1 == null) return -1
        if (c2 == null) return 1

        val numberComparison = c1.chapterNumber.compareTo(c2.chapterNumber)
        if (numberComparison != 0) {
            return numberComparison
        }

        return NaturalOrderComparator.compare(c1.fileName, c2.fileName)
    }
}
