package com.kitsune.app.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.kitsune.app.core.NaturalOrderComparator
import com.kitsune.app.domain.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Parser untuk membaca isi file CBZ (Zip) dengan dukungan Random Access $O(1)$.
 * REVISION 12.1.1: Migrated from ZipInputStream to ZipFile for performance.
 */
class CbzParser(private val context: Context) {

    private val naturalOrderComparator = NaturalOrderComparator()
    private val allowedExtensions = setOf("jpg", "jpeg", "png", "webp")

    // Session-based caching for high-speed access
    private var currentZip: ZipFile? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentUri: Uri? = null
    private var entryMap = mutableMapOf<String, ZipEntry>()

    /**
     * Memastikan file ZIP terbuka dan terindeks di memori.
     */
    private fun ensureZipOpen(uri: Uri) {
        if (currentUri == uri && currentZip != null) return

        close() // Close previous session if any

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw Exception("Failed to open FileDescriptor for $uri")
            
            // OPTIMIZATION: Use /proc/self/fd/ to open ZipFile from ParcelFileDescriptor
            // This enables true O(1) Random Access on SAF-based files.
            val fd = pfd.fd
            val zipFile = ZipFile("/proc/self/fd/$fd")
            
            val newEntryMap = mutableMapOf<String, ZipEntry>()
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext in allowedExtensions) {
                        newEntryMap[entry.name] = entry
                    }
                }
            }

            currentZip = zipFile
            currentPfd = pfd
            currentUri = uri
            entryMap = newEntryMap
            
            Log.d("CbzParser", "Opened ZIP session for $uri with ${newEntryMap.size} entries")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /**
     * Mengambil daftar halaman dari file CBZ menggunakan random access.
     */
    suspend fun getPages(chapterUri: Uri): List<Page> = withContext(Dispatchers.IO) {
        try {
            ensureZipOpen(chapterUri)
            if (currentZip == null) return@withContext emptyList()

            val sortedEntries = entryMap.keys.sortedWith { s1, s2 ->
                naturalOrderComparator.compare(s1, s2)
            }

            sortedEntries.mapIndexed { index, path ->
                Page(
                    pageNumber = index + 1,
                    imageName = path.substringAfterLast('/'),
                    entryPath = path
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Membuka InputStream untuk entri spesifik secara langsung $O(1)$.
     */
    fun getEntryInputStream(chapterUri: Uri, entryPath: String): InputStream? {
        return try {
            ensureZipOpen(chapterUri)
            val zip = currentZip ?: return null
            val entry = entryMap[entryPath] ?: return null
            
            zip.getInputStream(entry)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Menutup seluruh resource yang terbuka untuk mencegah file leak.
     */
    fun close() {
        try {
            currentZip?.close()
            currentPfd?.close()
        } catch (e: Exception) {
            // Ignored
        } finally {
            currentZip = null
            currentPfd = null
            currentUri = null
            entryMap.clear()
        }
    }
}
