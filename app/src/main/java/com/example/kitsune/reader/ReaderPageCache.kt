package com.example.kitsune.reader

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Memory-bounded LRU bitmap cache for decoded reader pages.
 *
 * Size is calculated dynamically using [Bitmap.allocationByteCount] and bounded by
 * `minOf(Runtime.maxMemory() / 8, 32 MiB)`.
 *
 * Invariants:
 * - Eviction only drops cache references; does NOT call [Bitmap.recycle] on evicted
 *   bitmaps because they may still be referenced by active UI composables.
 * - Cache keys incorporate pageIndex, targetWidth, and targetHeight so different
 *   target resolutions do not return stale downsampled bitmaps.
 */
class ReaderPageCache(
    maxCacheBytes: Int = DEFAULT_MAX_CACHE_BYTES
) {
    companion object {
        /** Default maximum cache budget: minOf(Runtime maxMemory / 8, 32 MiB). */
        val DEFAULT_MAX_CACHE_BYTES: Int = minOf(
            (Runtime.getRuntime().maxMemory() / 8L).toInt(),
            32 * 1024 * 1024
        )

        fun makeKey(pageIndex: Int, targetWidth: Int, targetHeight: Int): String {
            return "p_${pageIndex}_w_${targetWidth}_h_${targetHeight}"
        }
    }

    private val lruCache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.allocationByteCount
        }
    }

    @Synchronized
    fun get(pageIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val key = makeKey(pageIndex, targetWidth, targetHeight)
        return lruCache.get(key)
    }

    @Synchronized
    fun put(pageIndex: Int, targetWidth: Int, targetHeight: Int, bitmap: Bitmap) {
        val key = makeKey(pageIndex, targetWidth, targetHeight)
        if (bitmap.allocationByteCount <= lruCache.maxSize()) {
            lruCache.put(key, bitmap)
        }
    }

    @Synchronized
    fun clear() {
        lruCache.evictAll()
    }

    val currentSizeBytes: Int @Synchronized get() = lruCache.size()
    val maxSizeBytes: Int get() = lruCache.maxSize()
}
