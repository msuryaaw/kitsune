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
 * Menggunakan ZipFile untuk performa yang lebih baik dibandingkan ZipInputStream.
 */
class CbzParser(private val context: Context) {

    private val naturalOrderComparator = NaturalOrderComparator()
    private val allowedExtensions = setOf("jpg", "jpeg", "png", "webp")

    // Session-based caching for high-speed access
    private var currentZip: ZipFile? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentUri: Uri? = null
    private var currentTempFile: java.io.File? = null
    private var entryMap = mutableMapOf<String, ZipEntry>()

    /**
     * Memastikan file ZIP terbuka dan terindeks di memori.
     */
    private fun ensureZipOpen(uri: Uri) {
        if (currentUri == uri && currentZip != null) return

        close() // Close previous session if any

        var zipFile: ZipFile? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            // Kita coba metode FD terlebih dahulu karena ini yang paling cepat (O(1)).
            // Metode ini bekerja dengan memetakan deskriptor file SAF langsung ke sistem file Linux.
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw Exception("Gagal membuka FileDescriptor untuk $uri")
                
                val fd = pfd.fd
                zipFile = ZipFile("/proc/self/fd/$fd")
                
                if (zipFile.size() == 0) {
                    zipFile.close()
                    throw java.io.IOException("File ZIP kosong melalui FD")
                }
                
                currentPfd = pfd
                Log.d("CbzParser", "Berhasil membuka ZIP via proc-fd: $uri")
            } catch (e: Exception) {
                // Jika metode FD gagal (biasanya karena restriksi SELinux di Android baru),
                // kita gunakan fallback dengan menyalin file ke cache internal sementara.
                Log.w("CbzParser", "Metode proc-fd gagal, mencoba fallback cache untuk $uri: ${e.message}")
                
                // Bersihkan artifact dari percobaan pertama
                zipFile?.close()
                pfd?.close()
                
                val cacheDir = java.io.File(context.cacheDir, "chapter_cache").apply { mkdirs() }
                val tempFile = java.io.File(cacheDir, "temp_chapter_${System.currentTimeMillis()}.cbz")
                
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("Gagal membuka input stream untuk $uri")
                
                zipFile = ZipFile(tempFile)
                currentTempFile = tempFile
                Log.d("CbzParser", "Berhasil membuka ZIP via temp cache: $uri")
            }

            if (zipFile.size() == 0) {
                throw Exception("Zip file is empty or invalid after all attempts")
            }

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
            currentUri = uri
            entryMap = newEntryMap
            
            Log.d("CbzParser", "ZIP session ready for $uri with ${newEntryMap.size} valid entries")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /**
     * Mengambil daftar halaman dari file CBZ menggunakan random access.
     */
    suspend fun getPages(chapterUri: Uri): List<Page> = withContext(Dispatchers.IO) {
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
    }

    /**
     * Membuka InputStream untuk entri spesifik secara langsung $O(1)$.
     */
    fun getEntryInputStream(chapterUri: Uri, entryPath: String): InputStream? {
        return try {
            ensureZipOpen(chapterUri)
            val zip = currentZip ?: return null
            
            // Path Normalization: Ensure lookup works regardless of leading slashes
            val normalizedPath = entryPath.removePrefix("/")
            val entry = entryMap[normalizedPath] ?: entryMap[entryPath] ?: return null
            
            zip.getInputStream(entry)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Menutup seluruh resource yang terbuka untuk mencegah kebocoran memori atau file descriptor.
     * Juga membersihkan file cache sementara jika digunakan.
     */
    fun close() {
        try {
            currentZip?.close()
            currentPfd?.close()
            currentTempFile?.let { 
                if (it.exists()) it.delete() 
            }
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
}
