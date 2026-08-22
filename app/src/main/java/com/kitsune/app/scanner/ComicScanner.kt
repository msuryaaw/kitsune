package com.kitsune.app.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kitsune.app.core.StorageHelper
import com.kitsune.app.domain.model.Chapter
import com.kitsune.app.domain.model.Comic
import com.kitsune.app.reader.CbzParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Engine for scanning comic folders offline.
 * Inherits from BaseScanner for shared SAF and sorting logic.
 * 
 * REVISION 10.2.2: Refactored to be stateless for thread safety.
 * Focused strictly on Comic/CBZ logic.
 */
class ComicScanner(
    context: Context,
    storageHelper: StorageHelper
) : BaseScanner(context, storageHelper) {

    private val cbzParser = CbzParser(context)
    private val regex3 = Regex("""^\[([^\]]+)\]\s*\[([^\]]+)\]\s*\[([^\]]+)\]\s*(.*)$""")
    private val regex2 = Regex("""^\[([^\]]+)\]\s*\[([^\]]+)\]\s*(.*)$""")

    /**
     * Scans the 'Comics' folder for comic titles.
     * Implements automatic cover generation if not found.
     * 
     * REVISION 11.2.5: Implemented Regex parsing for [LANG] [AUTHOR] Title folder patterns.
     * REVISION 11.3.1: Upgraded to flexible multi-pattern parser supporting [TYPE].
     * 
     * @param rootUri The root URI of the Kitsune library.
     * @param getExistingCover Callback to retrieve cover URI from DB if folder hasn't changed.
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
            val folderName = folder.name ?: return@mapNotNull null
            val relativePath = "Comics/$folderName"
            val currentLastModified = folder.lastModified()
            
            val cachedCover = getExistingCover(relativePath, currentLastModified)
            
            // Cover search order:
            // 1. Database Cache
            // 2. Filesystem search (cover.*)
            // 3. Auto-generation from first chapter
            var coverUri = cachedCover ?: findCover(folder)?.toString()
            
            if (coverUri == null) {
                coverUri = generateCover(folder)?.toString()
            }

            // Flexible Multi-pattern Regex Parsing (REVISION 11.3.1)
            var parsedType: String? = null
            var parsedLang: String? = null
            var parsedAuthor: String? = null
            var parsedTitle: String = folderName

            val match3 = regex3.find(folderName)
            if (match3 != null) {
                parsedType = match3.groupValues[1]
                parsedLang = match3.groupValues[2]
                parsedAuthor = match3.groupValues[3]
                parsedTitle = match3.groupValues[4].trim()
            } else {
                val match2 = regex2.find(folderName)
                if (match2 != null) {
                    parsedLang = match2.groupValues[1]
                    parsedAuthor = match2.groupValues[2]
                    parsedTitle = match2.groupValues[3].trim()
                }
            }

            // Calculate chapter count (REVISION 11.4.1)
            val chapterCount = folder.listFiles()
                .count { it.isFile && it.name?.lowercase()?.endsWith(".cbz") == true }
            
            Comic(
                title = folderName,
                displayTitle = if (parsedTitle.isEmpty()) folderName else parsedTitle,
                author = parsedAuthor,
                language = parsedLang,
                type = parsedType,
                relativePath = relativePath,
                coverUri = coverUri,
                lastModified = currentLastModified,
                searchTags = null,
                chapterCount = chapterCount
            )
        }
    }

    /**
     * Scans for chapters (.cbz files) within a specific comic folder.
     */
    suspend fun scanChapters(rootUri: Uri, comicRelativePath: String): List<Chapter> = withContext(Dispatchers.IO) {
        val comicsFolder = getCategoryFolder(rootUri, "Comics") ?: return@withContext emptyList()
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

    /**
     * Automatically generates a cover.jpg from the first chapter's first page.
     */
    private suspend fun generateCover(folder: DocumentFile): Uri? = withContext(Dispatchers.IO) {
        val cbzFiles = folder.listFiles()
            .filter { it.isFile && it.name?.lowercase()?.endsWith(".cbz") == true }
            .sortedWith { f1, f2 ->
                naturalOrderComparator.compare(f1.name ?: "", f2.name ?: "")
            }

        for (cbzFile in cbzFiles) {
            try {
                val cbzUri = cbzFile.uri
                val pages = cbzParser.getPages(cbzUri)
                if (pages.isEmpty()) continue

                val firstPage = pages.first()
                val inputStream = cbzParser.getEntryInputStream(cbzUri, firstPage.entryPath) ?: continue

                // REVISION 6.7.6: Double-check before creation to prevent race conditions.
                val existingCover = folder.findFile("cover.jpg")
                if (existingCover != null && existingCover.exists()) {
                    return@withContext existingCover.uri
                }

                val coverFile = folder.createFile("image/jpeg", "cover.jpg") ?: continue
                
                context.contentResolver.openOutputStream(coverFile.uri)?.use { outputStream ->
                    inputStream.use { input ->
                        input.copyTo(outputStream)
                    }
                }
                
                return@withContext coverFile.uri
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        null
    }
}
