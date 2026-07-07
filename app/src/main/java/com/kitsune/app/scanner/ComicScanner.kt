package com.kitsune.app.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kitsune.app.core.NaturalOrderComparator
import com.kitsune.app.domain.model.Chapter
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.reader.CbzParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Engine untuk melakukan scanning folder komik secara offline.
 * Menggunakan Hybrid SAF (DocumentFile) untuk navigasi direktori.
 */
class ComicScanner(private val context: Context) {

    private val naturalOrderComparator = NaturalOrderComparator()
    private val allowedImageExtensions = listOf("jpg", "jpeg", "png", "webp")
    private val cbzParser = CbzParser(context)

    // Folder Cache
    private var cachedRootUri: Uri? = null
    private var cachedRootDoc: DocumentFile? = null
    private var cachedComicsDoc: DocumentFile? = null
    private var cachedVideosDoc: DocumentFile? = null

    /**
     * Membersihkan seluruh cache folder yang tersimpan.
     */
    fun clearCache() {
        cachedRootUri = null
        cachedRootDoc = null
        cachedComicsDoc = null
        cachedVideosDoc = null
    }

    private fun getRootFolder(rootUri: Uri): DocumentFile? {
        // Invalidation: Jika URI berbeda atau folder tidak ada lagi
        if (cachedRootUri != rootUri || cachedRootDoc == null || !cachedRootDoc!!.exists()) {
            clearCache()
            cachedRootUri = rootUri
            cachedRootDoc = DocumentFile.fromTreeUri(context, rootUri)
        }
        return cachedRootDoc
    }

    private fun getCategoryFolder(rootUri: Uri, category: String): DocumentFile? {
        val root = getRootFolder(rootUri) ?: return null
        
        return when (category) {
            "Comics" -> {
                if (cachedComicsDoc == null || !cachedComicsDoc!!.exists()) {
                    cachedComicsDoc = root.findFile("Comics")
                }
                cachedComicsDoc
            }
            "Videos" -> {
                if (cachedVideosDoc == null || !cachedVideosDoc!!.exists()) {
                    cachedVideosDoc = root.findFile("Videos")
                }
                cachedVideosDoc
            }
            else -> root.findFile(category)
        }
    }

    /**
     * Verifikasi apakah folder kategori valid dan dapat diakses.
     */
    fun isCategoryFolderValid(rootUri: Uri, category: String): Boolean {
        return try {
            val folder = getCategoryFolder(rootUri, category)
            folder != null && folder.exists() && folder.isDirectory
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Memindai folder 'Comics' di dalam root URI yang diberikan.
     * Mendukung pembuatan cover otomatis jika file cover tidak ditemukan (Phase 6.7.1).
     */
    suspend fun scanComics(
        rootUri: Uri,
        getExistingCover: (relativePath: String, lastModified: Long) -> String? = { _, _ -> null }
    ): List<Comic> = withContext(Dispatchers.IO) {
        val comicsFolder = getCategoryFolder(rootUri, "Comics") ?: return@withContext emptyList()
        
        if (!comicsFolder.isDirectory) return@withContext emptyList()

        val comicFolders = comicsFolder.listFiles()
            .filter { it.isDirectory }
            .sortedWith { f1, f2 -> 
                naturalOrderComparator.compare(f1.name ?: "", f2.name ?: "") 
            }

        comicFolders.mapNotNull { folder ->
            val title = folder.name ?: return@mapNotNull null
            val relativePath = "Comics/$title"
            val currentLastModified = folder.lastModified()
            
            val cachedCover = getExistingCover(relativePath, currentLastModified)
            
            // Urutan Pencarian Cover:
            // 1. Ambil dari Cache DB (jika lastModified folder sama)
            // 2. Cari file cover.* di filesystem
            // 3. Generate otomatis dari chapter pertama jika tidak ditemukan
            var coverUri = cachedCover ?: findCover(folder)?.toString()
            
            if (coverUri == null) {
                coverUri = generateCover(folder)?.toString()
            }
            
            Comic(
                title = title,
                relativePath = relativePath,
                coverUri = coverUri,
                lastModified = currentLastModified
            )
        }
    }

    /**
     * Menghasilkan file cover.jpg secara otomatis dari chapter pertama.
     * Mengikuti aturan Natural Sorting untuk pemilihan chapter dan halaman.
     */
    private suspend fun generateCover(folder: DocumentFile): Uri? = withContext(Dispatchers.IO) {
        // Cari semua file .cbz dan urutkan secara natural
        val cbzFiles = folder.listFiles()
            .filter { it.isFile && it.name?.lowercase()?.endsWith(".cbz") == true }
            .sortedWith { f1, f2 ->
                naturalOrderComparator.compare(f1.name ?: "", f2.name ?: "")
            }

        // Iterasi chapter untuk mencari yang valid
        for (cbzFile in cbzFiles) {
            try {
                val cbzUri = cbzFile.uri
                // Ambil daftar halaman (CbzParser sudah menggunakan Natural Sorting secara internal)
                val pages = cbzParser.getPages(cbzUri)
                if (pages.isEmpty()) continue

                val firstPage = pages.first()
                val inputStream = cbzParser.getEntryInputStream(cbzUri, firstPage.entryPath) ?: continue

                // REVISION 6.7.6: Lakukan pengecekan ulang tepat sebelum createFile untuk mencegah race condition.
                // Jika file cover.jpg sudah ada (mungkin dibuat oleh thread lain), gunakan file tersebut.
                val existingCover = folder.findFile("cover.jpg")
                if (existingCover != null && existingCover.exists()) {
                    return@withContext existingCover.uri
                }

                // Buat file cover.jpg di folder komik
                val coverFile = folder.createFile("image/jpeg", "cover.jpg") ?: continue
                
                context.contentResolver.openOutputStream(coverFile.uri)?.use { outputStream ->
                    inputStream.use { input ->
                        input.copyTo(outputStream)
                    }
                }
                
                return@withContext coverFile.uri
            } catch (e: Exception) {
                // Jika satu chapter bermasalah (corrupt/empty), lanjut ke chapter berikutnya
                e.printStackTrace()
            }
        }
        null
    }

    /**
     * Memindai daftar chapter (file .cbz) di dalam folder komik tertentu.
     */
    suspend fun scanChapters(rootUri: Uri, comicRelativePath: String): List<Chapter> = withContext(Dispatchers.IO) {
        // Optimasi: Gunakan folder Comics dari cache sebagai base navigasi
        val comicsFolder = getCategoryFolder(rootUri, "Comics") ?: return@withContext emptyList()
        
        // Hanya cari Title folder di bawah folder Comics
        val title = comicRelativePath.substringAfter("Comics/").removeSuffix("/")
        val comicFolder = comicsFolder.findFile(title) ?: return@withContext emptyList()

        comicFolder.listFiles()
            .filter { it.isFile && it.name?.lowercase()?.endsWith(".cbz") == true }
            .sortedWith { f1, f2 -> 
                naturalOrderComparator.compare(f1.name ?: "", f2.name ?: "") 
            }
            .map { file ->
                val name = file.name ?: "Unknown Chapter"
                Chapter(
                    name = name.removeSuffix(".cbz"),
                    relativePath = "$comicRelativePath/$name",
                    lastModified = file.lastModified()
                )
            }
    }

    private fun findCover(folder: DocumentFile): Uri? {
        return folder.listFiles().find { file ->
            val fileName = file.name?.lowercase() ?: ""
            allowedImageExtensions.any { ext -> fileName == "cover.$ext" }
        }?.uri
    }
}
