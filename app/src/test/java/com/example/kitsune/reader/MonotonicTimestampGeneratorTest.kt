package com.example.kitsune.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicTimestampGeneratorTest {

    @Test
    fun nextTimestamp_returnsStrictlyIncreasingTimestamps() {
        val generator = MonotonicTimestampGenerator()
        val t1 = generator.nextTimestamp()
        val t2 = generator.nextTimestamp()
        val t3 = generator.nextTimestamp()

        assertTrue("t2 must be strictly greater than t1", t2 > t1)
        assertTrue("t3 must be strictly greater than t2", t3 > t2)
    }

    @Test
    fun nextTimestamp_sameClockValue_returnsStrictlyIncreasingTimestamps() {
        val staticClock = 1000L
        val generator = MonotonicTimestampGenerator(initialTimestamp = 1000L, clockSupplier = { staticClock })

        val t1 = generator.nextTimestamp()
        val t2 = generator.nextTimestamp()
        val t3 = generator.nextTimestamp()

        assertEquals(1001L, t1)
        assertEquals(1002L, t2)
        assertEquals(1003L, t3)
    }

    @Test
    fun nextTimestamp_backwardClockMovement_returnsStrictlyIncreasingTimestamps() {
        var clockValue = 5000L
        val generator = MonotonicTimestampGenerator(initialTimestamp = 5000L, clockSupplier = { clockValue })

        val t1 = generator.nextTimestamp() // 5001L
        assertEquals(5001L, t1)

        // System clock moves BACKWARDS to 2000L
        clockValue = 2000L
        val t2 = generator.nextTimestamp() // 5002L
        assertEquals(5002L, t2)
        assertTrue("t2 must be strictly greater than t1 even when clock moves backwards", t2 > t1)
    }

    @Test
    fun nextTimestamp_concurrentCalls_returnsAllUniqueAndStrictlyIncreasingTimestamps() = runTest {
        val generator = MonotonicTimestampGenerator(initialTimestamp = 1000L)
        val count = 100

        val results = (1..count).map {
            async(Dispatchers.Default) {
                generator.nextTimestamp()
            }
        }.awaitAll()

        val uniqueSet = results.toSet()
        assertEquals("All $count concurrent timestamps must be unique", count, uniqueSet.size)

        val sortedResults = results.sorted()
        for (i in 0 until sortedResults.size - 1) {
            assertTrue("Sorted timestamps must be strictly increasing", sortedResults[i + 1] > sortedResults[i])
        }
    }

    @Test
    fun seed_restoredTimestamp_advancesTimestampFloor() {
        val generator = MonotonicTimestampGenerator(initialTimestamp = 1000L)
        generator.seed(5000L)

        val next = generator.nextTimestamp()
        assertTrue("Next timestamp after seed(5000) must be > 5000", next > 5000L)
    }

    @Test
    fun seed_smallerTimestamp_isIgnored() {
        val generator = MonotonicTimestampGenerator(initialTimestamp = 5000L)
        generator.seed(1000L)

        assertEquals(5000L, generator.currentLastTimestamp)
    }
}
