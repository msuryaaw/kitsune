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
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Parser untuk membaca isi file CBZ (Zip) dengan dukungan Random Access $O(1)$.
 * Menggunakan ZipFile untuk performa yang lebih baik dibandingkan ZipInputStream.
 * 
 * REVISION 12.5.1: Thread-safe implementation with Transient Isolation.
 * Fixes intermittent blank pages caused by concurrent prefetch closing active session.
 */
class CbzParser(private val context: Context) {

    private val lock = Any()
    private val naturalOrderComparator = NaturalOrderComparator()
    private val allowedExtensions = setOf("jpg", "jpeg", "png", "webp")

    // Session-based caching for high-speed access (Protected by lock)
    private var currentZip: ZipFile? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentUri: Uri? = null
    private var currentTempFile: java.io.File? = null
    private var entryMap = mutableMapOf<String, ZipEntry>()

    /**
     * Memastikan file ZIP terbuka dan terindeks di memori untuk sesi aktif.
     * Wajib dipanggil di dalam synchronized(lock).
     */
    private fun ensureZipOpenInternal(uri: Uri) {
        if (currentUri == uri && currentZip != null) return

        closeInternal() // Close previous session if any

        try {
            val result = openZipFile(uri)
            currentZip = result.zipFile
            currentPfd = result.pfd
            currentTempFile = result.tempFile
            currentUri = uri
            
            val newEntryMap = mutableMapOf<String, ZipEntry>()
            val entries = result.zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext in allowedExtensions) {
                        newEntryMap[entry.name] = entry
                    }
                }
            }
            entryMap = newEntryMap
            
            Log.d("CbzParser", "Active ZIP session ready for $uri with ${newEntryMap.size} valid entries")
        } catch (e: Exception) {
            closeInternal()
            throw e
        }
    }

    /**
     * Membuka file ZIP baik melalui proc-fd atau temp cache.
     * Helper terisolasi untuk digunakan oleh sesi aktif maupun akses transient.
     */
    private fun openZipFile(uri: Uri): ZipFileResult {
        var zipFile: ZipFile? = null
        var pfd: ParcelFileDescriptor? = null
        var tempFile: java.io.File? = null

        try {
            // Method 1: proc-fd (Fastest)
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw Exception("Failed to open PFD")
                
                zipFile = ZipFile("/proc/self/fd/${pfd.fd}")
                if (zipFile.size() == 0) throw java.io.IOException("Empty ZIP via FD")
                
                return ZipFileResult(zipFile, pfd, null)
            } catch (e: Exception) {
                Log.w("CbzParser", "proc-fd failed for $uri, falling back to cache: ${e.message}")
                zipFile?.close()
                pfd?.close()
            }

            // Method 2: Temp Cache (Fallback)
            val cacheDir = java.io.File(context.cacheDir, "chapter_cache").apply { mkdirs() }
            val uniqueId = UUID.randomUUID().toString().take(8)
            tempFile = java.io.File(cacheDir, "temp_reader_${uniqueId}_${uri.lastPathSegment}.cbz")
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Failed to open stream for $uri")
            
            zipFile = ZipFile(tempFile)
            if (zipFile.size() == 0) throw Exception("ZIP is empty after caching")
            
            return ZipFileResult(zipFile, null, tempFile)
        } catch (e: Exception) {
            zipFile?.close()
            pfd?.close()
            tempFile?.delete()
            throw e
        }
    }

    /**
     * Mengambil daftar halaman dari file CBZ.
     * REVISION 12.5.2: Transient Isolation logic. 
     * Jika URI berbeda dengan sesi aktif (misal: prefetch), data dibaca tanpa mengganggu sesi utama.
     */
    suspend fun getPages(chapterUri: Uri): List<Page> = withContext(Dispatchers.IO) {
        // 1. Cek apakah ini sesi aktif (Fast Path)
        synchronized(lock) {
            if (currentUri == chapterUri && currentZip != null) {
                return@synchronized getPagesFromMap()
            }
        }

        // 2. Akses Transient (Untuk prefetch atau inisialisasi)
        // Kita buka resource secara lokal dan segera tutup agar tidak terjadi contention pada sesi utama.
        var transientResult: ZipFileResult? = null
        try {
            transientResult = openZipFile(chapterUri)
            val zip = transientResult.zipFile
            val paths = mutableListOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext in allowedExtensions) {
                        paths.add(entry.name)
                    }
                }
            }
            
            val sortedPaths = paths.sortedWith { s1, s2 -> naturalOrderComparator.compare(s1, s2) }
            sortedPaths.mapIndexed { index, path ->
                Page(
                    pageNumber = index + 1,
                    imageName = path.substringAfterLast('/'),
                    entryPath = path
                )
            }
        } finally {
            transientResult?.close()
        }
    }

    private fun getPagesFromMap(): List<Page> {
        return entryMap.keys.sortedWith { s1, s2 ->
            naturalOrderComparator.compare(s1, s2)
        }.mapIndexed { index, path ->
            Page(
                pageNumber = index + 1,
                imageName = path.substringAfterLast('/'),
                entryPath = path
            )
        }
    }

    /**
     * Membuka InputStream untuk entri spesifik secara langsung $O(1)$.
     * REVISION 12.5.3: Thread-safe stream extraction.
     */
    fun getEntryInputStream(chapterUri: Uri, entryPath: String): InputStream? {
        return synchronized(lock) {
            try {
                ensureZipOpenInternal(chapterUri)
                val zip = currentZip ?: return@synchronized null
                
                // Path Normalization: Ensure lookup works regardless of leading slashes
                val normalizedPath = entryPath.removePrefix("/")
                val entry = entryMap[normalizedPath] ?: entryMap[entryPath] ?: return@synchronized null
                
                zip.getInputStream(entry)
            } catch (e: Exception) {
                Log.e("CbzParser", "Error getting stream for $entryPath", e)
                null
            }
        }
    }

    /**
     * Menutup seluruh resource yang terbuka.
     */
    fun close() {
        synchronized(lock) {
            closeInternal()
        }
    }

    private fun closeInternal() {
        try {
            currentZip?.close()
            currentPfd?.close()
            currentTempFile?.let { if (it.exists()) it.delete() }
        } catch (e: Exception) {
            // Ignored
        } finally {
            currentZip = null
            currentPfd = null
            currentUri = null
            currentTempFile = null
            entryMap.clear()
        }
    }

    /**
     * Helper data class to manage ZIP session resources.
     */
    private data class ZipFileResult(
        val zipFile: ZipFile,
        val pfd: ParcelFileDescriptor?,
        val tempFile: java.io.File?
    ) {
        fun close() {
            try {
                zipFile.close()
                pfd?.close()
                tempFile?.delete()
            } catch (e: Exception) {}
        }
    }
}
