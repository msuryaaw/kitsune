package com.kitsune.app.data.repository

import android.net.Uri
import android.util.LruCache
import com.kitsune.app.domain.model.Page
import com.kitsune.app.reader.CbzParser
import java.io.InputStream

/**
 * Repository untuk mengelola logika pembacaan konten komik (CBZ).
 * Menggunakan LRU Cache untuk manajemen memori yang efisien agar tidak terjadi kebocoran memori.
 */
class ReaderRepository(
    private val cbzParser: CbzParser
) {
    // Bounded LRU Cache untuk menyimpan metadata halaman dari 8 chapter terakhir.
    private val pageCache = LruCache<String, List<Page>>(8)

    /**
     * Mendapatkan daftar halaman dari file CBZ berdasarkan URI.
     * Mendukung caching berbasis key (jalur file + waktu modifikasi terakhir).
     * 
     * Galat (Exceptions) akan diteruskan ke pemanggil untuk penanganan yang lebih baik.
     */
    suspend fun getPages(chapterUri: Uri, cacheKey: String? = null): List<Page> {
        // 1. Cek cache jika key tersedia
        if (cacheKey != null) {
            pageCache.get(cacheKey)?.let { return it }
        }

        // 2. Parse ZIP jika cache miss (Throws exception on failure)
        val pages = cbzParser.getPages(chapterUri)

        // 3. Simpan ke cache jika parsing berhasil
        if (cacheKey != null && pages.isNotEmpty()) {
            pageCache.put(cacheKey, pages)
        }

        return pages
    }

    /**
     * Membuka InputStream untuk entri spesifik di dalam file CBZ.
     */
    fun getPageStream(chapterUri: Uri, entryPath: String): InputStream? {
        return cbzParser.getEntryInputStream(chapterUri, entryPath)
    }

    /**
     * Menutup resource file CBZ yang sedang terbuka secara eksplisit.
     */
    fun closeCurrentChapter() {
        cbzParser.close()
    }
}
