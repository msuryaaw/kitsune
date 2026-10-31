package com.example.kitsune.reader

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe monotonic timestamp generator ensuring strictly increasing timestamps.
 * Guarantees that subsequent timestamps are strictly greater than previous timestamps,
 * preventing stale-write issues even if the system clock halts or moves backwards.
 */
class MonotonicTimestampGenerator(
    initialTimestamp: Long = System.currentTimeMillis(),
    private val clockSupplier: () -> Long = { System.currentTimeMillis() }
) {
    private val lastTimestamp = AtomicLong(initialTimestamp)

    /**
     * Generates a strictly increasing timestamp (`next > previous`).
     */
    fun nextTimestamp(): Long {
        val now = clockSupplier()
        while (true) {
            val currentLast = lastTimestamp.get()
            val target = maxOf(now, currentLast + 1)
            if (lastTimestamp.compareAndSet(currentLast, target)) {
                return target
            }
        }
    }

    /**
     * Seeds the generator with a known restored timestamp from DB if greater than current.
     */
    fun seed(restoredTimestamp: Long) {
        while (true) {
            val currentLast = lastTimestamp.get()
            if (restoredTimestamp <= currentLast) break
            if (lastTimestamp.compareAndSet(currentLast, restoredTimestamp)) break
        }
    }

    val currentLastTimestamp: Long get() = lastTimestamp.get()
}
